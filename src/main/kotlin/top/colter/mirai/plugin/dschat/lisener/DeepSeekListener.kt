package top.colter.mirai.plugin.dschat.lisener

import net.mamoe.mirai.contact.Contact.Companion.sendImage
import net.mamoe.mirai.event.EventHandler
import net.mamoe.mirai.event.SimpleListenerHost
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import top.colter.mirai.plugin.dschat.DsChatPlugin
import top.colter.mirai.plugin.dschat.deepseek.*
import top.colter.mirai.plugin.dschat.draw.chatDraw
import top.colter.mirai.plugin.dschat.tools.logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

object DeepSeekListener : SimpleListenerHost() {

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        logger.error("DeepSeekListener Exception: $exception")
    }

    private data class SessionKey(val groupId: Long, val userId: Long)

    private class SessionEntry(
        val messages: ArrayDeque<ChatMessage> = ArrayDeque(),
        var lastAccessTime: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<SessionKey, SessionEntry>()

    private var cachedClient: DeepSeekClient? = null

    private fun getClient(): DeepSeekClient {
        return cachedClient ?: DeepSeekClient(
            DeepSeekConfig.api.apiKey,
            DeepSeekConfig.api.apiUrl
        ).also { cachedClient = it }
    }

    private fun now() = System.currentTimeMillis()

    @EventHandler
    suspend fun GroupMessageEvent.onMessage() {
        val filteredChain = message.filter { it !is At && it !is Image }.toMessageChain()
        val msg = filteredChain.content.trim()
        if (msg.isBlank()) return

        val prompt = extractPrompt(msg, message) ?: return

        val key = SessionKey(subject.id, sender.id)

        if (prompt in listOf("clear", "重置", "新建对话", "清空上下文")) {
            sessions.remove(key)
            subject.sendMessage(
                QuoteReply(message.source) + PlainText("上下文已清空，开始新对话。")
            )
            return
        }

        if (prompt.isBlank()) {
            subject.sendMessage(
                QuoteReply(message.source) +
                        PlainText("用法: ${DeepSeekConfig.trigger.triggerPrefix} <问题>")
            )
            return
        }

        subject.sendMessage("请稍等...")

        val config = DeepSeekConfig.chat
        val systemPrompt = readSystemPrompt()
        val messages = if (config.enableMemory) {
            buildMessagesWithContext(prompt, key, systemPrompt)
        } else {
            listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", prompt)
            )
        }

        val request = ChatRequest(
            model = DeepSeekConfig.api.model,
            messages = messages,
            maxTokens = config.maxTokens,
        )

        val result = getClient().chat(request)

        result.fold(
            onSuccess = { response ->
                val choice = response.choices.firstOrNull()
                val finishReason = choice?.finishReason ?: ""

                val content = when (finishReason) {
                    "stop" -> choice?.message?.content ?: "(空回复)"
                    "length" -> (choice?.message?.content ?: "") +
                            "\n\n---\n(回复过长，已被截断)"
                    "content_filter" -> "内容已被安全过滤，请尝试换一种表达方式。"
                    "insufficient_system_resource" -> "服务繁忙，请稍后重试。"
                    else -> choice?.message?.content ?: "未知错误，请稍后重试。"
                }

                val threshold = config.textReplyThreshold
                if (threshold > 0 && content.length > threshold) {
                    val image = chatDraw(content)
                    if (image != null) {
                        subject.sendImage(
                            image.encodeToData()!!.bytes.toExternalResource().toAutoCloseable()
                        )
                    } else {
                        subject.sendMessage(
                            QuoteReply(message.source) + PlainText(content)
                        )
                    }
                } else {
                    subject.sendMessage(
                        QuoteReply(message.source) + PlainText(content)
                    )
                }

                if (config.enableMemory) {
                    saveToSession(key, prompt, content)
                }

                logger.info(
                    "DeepSeek: [群${subject.id}/${sender.nick}] " +
                            "tokens=${response.usage?.totalTokens ?: 0}"
                )
            },
            onFailure = { e ->
                subject.sendMessage(
                    QuoteReply(message.source) +
                            PlainText("请求失败: ${e.message ?: "未知错误"}")
                )
            }
        )
    }

    private fun buildMessagesWithContext(prompt: String, key: SessionKey, systemPrompt: String): List<ChatMessage> {
        val session = getOrCreateSession(key)

        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", systemPrompt))
        messages.addAll(session.messages)
        messages.add(ChatMessage("user", prompt))

        return messages
    }

    private fun getOrCreateSession(key: SessionKey): SessionEntry {
        val ttl = DeepSeekConfig.chat.memoryTtlMinutes * 60_000L
        val existing = sessions[key]

        if (existing != null) {
            if (now() - existing.lastAccessTime > ttl) {
                sessions.remove(key)
                return SessionEntry().also { sessions[key] = it }
            }
            existing.lastAccessTime = now()
            return existing
        }

        return SessionEntry().also { sessions[key] = it }
    }

    private fun saveToSession(key: SessionKey, userPrompt: String, assistantReply: String) {
        val config = DeepSeekConfig.chat
        val session = sessions[key] ?: return

        session.messages.addLast(ChatMessage("user", userPrompt))
        session.messages.addLast(ChatMessage("assistant", assistantReply))
        session.lastAccessTime = now()

        trimSession(session, config)
    }

    private fun trimSession(session: SessionEntry, config: ChatConfig) {
        val maxMessages = config.memoryRounds * 2

        while (session.messages.size > maxMessages) {
            session.messages.removeFirst()
            session.messages.removeFirst()
        }

        while (estimateTokens(session.messages) > config.maxContextTokens
            && session.messages.size >= 2
        ) {
            session.messages.removeFirst()
            session.messages.removeFirst()
        }
    }

    private fun estimateTokens(messages: ArrayDeque<ChatMessage>): Int {
        return messages.sumOf { (it.content.length * 3L / 2L).toInt() }
    }

    private fun readSystemPrompt(): String {
        val promptFile = DsChatPlugin.dataFolderPath.resolve("system-prompt.txt").toFile()
        if (promptFile.exists() && promptFile.isFile) {
            return promptFile.readText().trim()
        }
        return DeepSeekConfig.chat.systemPrompt
    }

    private fun extractPrompt(text: String, chain: MessageChain): String? {
        val config = DeepSeekConfig.trigger

        if (text.startsWith(config.triggerPrefix)) {
            return text.removePrefix(config.triggerPrefix).trim()
        }

        if (config.enableAtTrigger) {
            val botId = chain.bot.id
            val hasAt = chain.any { it is At && it.target == botId }
            if (hasAt) {
                return chain.filter { it !is At }.toMessageChain().content.trim()
            }
        }

        return null
    }
}
