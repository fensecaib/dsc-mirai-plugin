package top.colter.mirai.plugin.dschat.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.colter.mirai.plugin.dschat.deepseek.*
import top.colter.mirai.plugin.dschat.tools.json
import top.colter.mirai.plugin.dschat.tools.logger
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// 联网搜索Agent：ReAct循环 + current_time / web_search / web_fetch 三工具
class SearchAgent(config: WebSearchConfig) : Agent {
    override val name = "search"
    override val maxRounds = config.maxRounds

    private val fetchMaxChars = config.fetchMaxChars

    override val tools: List<Tool> = listOf(
        Tool(
            type = "function",
            function = FunctionDef(
                name = "current_time",
                description = "获取当前精确日期和时间。用户询问日期、时间、星期几时必须调用。返回北京时间。",
                parameters = ParametersDef(
                    type = "object",
                    properties = mapOf(
                        "dummy" to PropertyDef("string", "忽略，不需要传参")
                    ),
                    required = emptyList()
                )
            )
        ),
        Tool(
            type = "function",
            function = FunctionDef(
                name = "web_search",
                description = "搜索互联网获取实时信息。用于新闻、事件、事实核查、以及任何训练数据无法覆盖的最新信息。不要自己猜测搜索词中的日期或数字——如果不知道当前日期，先调用 current_time。",
                parameters = ParametersDef(
                    type = "object",
                    properties = mapOf(
                        "query" to PropertyDef("string", "搜索关键词。不要编造日期或数字，不知道时用泛称如'recent''latest'")
                    ),
                    required = listOf("query")
                )
            )
        ),
        Tool(
            type = "function",
            function = FunctionDef(
                name = "web_fetch",
                description = "获取指定URL的完整网页内容。当搜索结果摘要信息不足时使用，用于阅读原文获取详情。",
                parameters = ParametersDef(
                    type = "object",
                    properties = mapOf(
                        "url" to PropertyDef("string", "要获取的网页URL")
                    ),
                    required = listOf("url")
                )
            )
        )
    )

    // 向system消息注入工具使用规则
    override suspend fun prepareMessages(
        messages: MutableList<ChatMessage>,
        prompt: String,
        systemPrompt: String
    ) {
        val hint = """
        |可用工具: current_time(当前时间) web_search(搜索) web_fetch(读页面)
        |规则:
        |- **触发条件** → 在截止风险/验证时执行`web_search`和`web_fetch`。范围：身份、聊天、代码、调试。
        |- **日期/时间** → 先调 current_time，禁止猜测。
        |- **策略** → 精准查询。中英文混合。关键词："2026"、SOTA。
        |- **不确定的事实** → web_search 验证，至少两次不同角度查询交叉确认。
        |- **语言权重** → 强制混合中英文搜索以打破信息孤岛。官方文档/Stack Overflow采用英文；本地化模式/社区解决方案采用中文。
        |- **搜到版本号/Hash/下载链** → 必须 web_fetch 官方页面确认，不以聚合站/论坛为准。
        |- **时间锚点** → 关键词必须包含“2026”或“最新”以锁定当前一代最优技术。拒绝过时模式。
        """.trimMargin()

        val original = messages.firstOrNull { it.role == "system" } ?: return
        val idx = messages.indexOf(original)
        messages[idx] = ChatMessage("system", "${original.content}\n\n$hint")
    }

    override fun shouldContinue(round: Int): Boolean = round < maxRounds

    // 工具调用分发
    override suspend fun executeTool(
        toolName: String,
        arguments: String,
        toolCallId: String
    ): String? {
        return try {
            when (toolName) {
                "current_time" -> {
                    val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                    val fmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日 EEEE HH:mm:ss (zzzz)")
                    now.format(fmt)
                }
                "web_search" -> {
                    val query = parseArg(arguments, "query")
                        ?: return "错误: 缺少搜索关键词"
                    logger.info("WebSearch: $query")
                    SearchClient.search(query)
                }
                "web_fetch" -> {
                    val url = parseArg(arguments, "url")
                        ?: return "错误: 缺少URL"
                    logger.info("WebFetch: $url")
                    FetchService.fetch(url, fetchMaxChars)
                }
                else -> "未知工具: $toolName"
            }
        } catch (e: Exception) {
            logger.error("Tool execution failed: $toolName", e)
            "执行 $toolName 失败: ${e.message}"
        }
    }

    // 从LLM返回的arguments JSON字符串中提取指定key
    private fun parseArg(argsJson: String, key: String): String? {
        return try {
            val obj = json.decodeFromString(JsonObject.serializer(), argsJson)
            obj[key]?.jsonPrimitive?.content
        } catch (e: Exception) {
            logger.warning("Failed to parse tool args: $argsJson")
            null
        }
    }
}