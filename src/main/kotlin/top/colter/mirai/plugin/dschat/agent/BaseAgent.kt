package top.colter.mirai.plugin.dschat.agent

import top.colter.mirai.plugin.dschat.deepseek.ChatMessage
import top.colter.mirai.plugin.dschat.deepseek.Tool

interface Agent {
    val name: String
    val tools: List<Tool>
    val maxRounds: Int

    // LLM请求前准备，可修改system消息注入工具规则
    suspend fun prepareMessages(
        messages: MutableList<ChatMessage>,
        prompt: String,
        systemPrompt: String
    )

    // 执行单个工具调用，返回结果文本
    suspend fun executeTool(
        toolName: String,
        arguments: String,
        toolCallId: String
    ): String?

    // 判断是否继续工具调用循环
    fun shouldContinue(round: Int): Boolean
}
