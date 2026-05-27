package top.colter.mirai.plugin.dschat.agent

import top.colter.mirai.plugin.dschat.deepseek.ChatMessage
import top.colter.mirai.plugin.dschat.deepseek.Tool

// 纯LLM对话Agent，无工具
class ChatAgent : Agent {
    override val name = "chat"
    override val tools: List<Tool> = emptyList()
    override val maxRounds = 0

    override suspend fun prepareMessages(messages: MutableList<ChatMessage>, prompt: String, systemPrompt: String) {}

    override suspend fun executeTool(toolName: String, arguments: String, toolCallId: String): String? = null

    override fun shouldContinue(round: Int): Boolean = false
}
