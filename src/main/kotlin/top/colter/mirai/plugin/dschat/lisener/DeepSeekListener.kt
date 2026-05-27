package top.colter.mirai.plugin.dschat.lisener

import net.mamoe.mirai.contact.Contact.Companion.sendImage
import net.mamoe.mirai.event.EventHandler
import net.mamoe.mirai.event.SimpleListenerHost
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import top.colter.mirai.plugin.dschat.DsChatPlugin
import top.colter.mirai.plugin.dschat.agent.AgentRouter
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

        // 提取prompt（/ds前缀 或 @Bot + 文本）
        val prompt = extractPrompt(msg, message) ?: return

        val key = SessionKey(subject.id, sender.id)

        // 清空上下文指令
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

        // 构建消息列表（含历史上下文）
        var messages: MutableList<ChatMessage>
        if (config.enableMemory) {
            messages = buildMessagesWithContext(prompt, key, systemPrompt)
        } else {
            messages = mutableListOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", prompt)
            )
        }

        // Agent路由：根据配置选择纯对话或联网搜索Agent
        val agent = AgentRouter.create(DeepSeekConfig)
        agent.prepareMessages(messages, prompt, systemPrompt)

        var round = 0
        var finalContent: String? = null

        // ReAct工具调用循环
        while (agent.shouldContinue(round)) {
            val request = ChatRequest(
                model = DeepSeekConfig.api.model,
                messages = messages.toList(),
                maxTokens = config.maxTokens,
                tools = agent.tools.takeIf { it.isNotEmpty() },
                toolChoice = if (agent.tools.isNotEmpty()) "auto" else null,
            )

            val result = getClient().chat(request)

            result.fold(
                onSuccess = { response ->
                    val choice = response.choices.firstOrNull()
                    val replyMsg = choice?.message

                    // LLM调用了工具 → 执行工具、追加结果、继续循环
                    if (replyMsg?.toolCalls?.isNotEmpty() == true) {
                        messages.add(replyMsg)

                        for (call in replyMsg.toolCalls) {
                            val toolResult = agent.executeTool(
                                call.function.name,
                                call.function.arguments,
                                call.id
                            ) ?: "工具执行返回空结果"

                            messages.add(ChatMessage(
                                role = "tool",
                                content = toolResult,
                                toolCallId = call.id,
                                name = call.function.name,
                            ))
                        }
                        round++
                        logger.info(
                            "DeepSeek: [群${subject.id}/${sender.nick}] " +
                            "round=$round tools=${replyMsg.toolCalls.size} " +
                            "tokens=${response.usage?.totalTokens ?: 0}"
                        )
                        return@fold
                    }

                    // 无工具调用 → LLM最终回复
                    finalContent = when (choice?.finishReason) {
                        "stop" -> replyMsg?.content ?: "(空回复)"
                        "length" -> (replyMsg?.content ?: "") +
                                "\n\n---\n(回复过长，已被截断)"
                        "content_filter" -> "内容已被安全过滤，请尝试换一种表达方式。"
                        "insufficient_system_resource" -> "服务繁忙，请稍后重试。"
                        else -> replyMsg?.content ?: "未知错误，请稍后重试。"
                    }

                    logger.info(
                        "DeepSeek: [群${subject.id}/${sender.nick}] " +
                        "round=$round tokens=${response.usage?.totalTokens ?: 0}"
                    )
                },
                onFailure = { e ->
                    finalContent = "请求失败: ${e.message ?: "未知错误"}"
                }
            )

            if (finalContent != null) break
        }

        val content = finalContent ?: "(搜索轮次超限，请简化问题重试)"

        // 长文本转图片输出
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

        // 持久化对话历史（不保存工具调用中间结果）
        if (config.enableMemory) {
            saveToSession(key, prompt, content)
        }
    }

    // 拼接system + 历史 + 新user消息
    private fun buildMessagesWithContext(prompt: String, key: SessionKey, systemPrompt: String): MutableList<ChatMessage> {
        val session = getOrCreateSession(key)

        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", systemPrompt))
        messages.addAll(session.messages)
        messages.add(ChatMessage("user", prompt))

        return messages
    }

    // 获取或创建会话，自动清除过期会话
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

    // 保存本轮对话到会话历史
    private fun saveToSession(key: SessionKey, userPrompt: String, assistantReply: String) {
        val config = DeepSeekConfig.chat
        val session = sessions[key] ?: return

        session.messages.addLast(ChatMessage("user", userPrompt))
        session.messages.addLast(ChatMessage("assistant", assistantReply))
        session.lastAccessTime = now()

        trimSession(session, config)
    }

    // 双层溢出保护：轮次限制 + Token限制，均成对删除
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

    // 粗略Token估算：中英文混合约1.5字符/token
    private fun estimateTokens(messages: ArrayDeque<ChatMessage>): Int {
        return messages.sumOf { ((it.content ?: "").length * 3L / 2L).toInt() }
    }

    // 提示词优先级：data/system-prompt.txt > config.systemPrompt
    private fun readSystemPrompt(): String {
        val promptFile = DsChatPlugin.dataFolderPath.resolve("system-prompt.txt").toFile()
        if (promptFile.exists() && promptFile.isFile) {
            return promptFile.readText().trim()
        }
        return DeepSeekConfig.chat.systemPrompt
    }

    // 从消息中提取prompt：/ds前缀 或 @Bot
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
