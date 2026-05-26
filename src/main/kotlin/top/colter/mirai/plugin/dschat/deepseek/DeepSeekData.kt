package top.colter.mirai.plugin.dschat.deepseek

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val thinking: ThinkingConfig? = ThinkingConfig(),
    val stream: Boolean = false,
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
)

@Serializable
data class ThinkingConfig(
    val type: String = "disabled"
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatResponse(
    val id: String = "",
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

@Serializable
data class ChatChoice(
    @SerialName("finish_reason")
    val finishReason: String = "",
    val message: ChatMessage? = null,
)

@Serializable
data class ChatUsage(
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0,
)
