package top.colter.mirai.plugin.dschat.deepseek

import kotlinx.serialization.Serializable
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.ValueDescription
import net.mamoe.mirai.console.data.value

object DeepSeekConfig : AutoSavePluginConfig("DeepSeekConfig") {

    @ValueDescription(
        """
        DeepSeek API 配置:
          apiKey: API 密钥，从 https://platform.deepseek.com/api_keys 获取
          apiUrl: API 地址，默认 https://api.deepseek.com/chat/completions
          model: 模型名称，可选 deepseek-v4-flash / deepseek-v4-pro
    """
    )
    val api: ApiConfig by value()

    @ValueDescription(
        """
        对话配置:
          systemPrompt: 系统提示词
          maxTokens: 最大输出 token 数
          textReplyThreshold: 长文本转为图片输出的字符数阈值 (0=始终发文本)
    """
    )
    val chat: ChatConfig by value()

    @ValueDescription(
        """
        触发配置:
          triggerPrefix: 触发前缀，默认 /ds
          enableAtTrigger: 是否支持 @Bot 触发
    """
    )
    val trigger: TriggerConfig by value()

    @ValueDescription(
        """
        图片配置:
          defaultColor: 默认绘图主题色，支持多个值自定义渐变，中间用分号;分隔
          factor: 图片倍率，默认 1 倍
    """
    )
    val image: ImageConfig by value()

}

@Serializable
data class ApiConfig(
    var apiKey: String = "",
    var apiUrl: String = "https://api.deepseek.com/chat/completions",
    var model: String = "deepseek-v4-flash",
)

@Serializable
data class ChatConfig(
    var systemPrompt: String = "你是一个有帮助的AI助手。",
    var maxTokens: Int = 4096,
    var textReplyThreshold: Int = 1200,
    var enableMemory: Boolean = true,
    var memoryRounds: Int = 10,
    var memoryTtlMinutes: Int = 30,
    var maxContextTokens: Int = 128000,
)

@Serializable
data class TriggerConfig(
    var triggerPrefix: String = "/ds",
    var enableAtTrigger: Boolean = true,
)

@Serializable
data class ImageConfig(
    var defaultColor: String = "#667eea;#764ba2",
    var factor: Float = 1f
)
