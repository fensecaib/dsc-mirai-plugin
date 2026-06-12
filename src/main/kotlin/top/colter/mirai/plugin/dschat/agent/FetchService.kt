package top.colter.mirai.plugin.dschat.agent

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import top.colter.mirai.plugin.dschat.deepseek.DeepSeekConfig
import top.colter.mirai.plugin.dschat.tools.json
import top.colter.mirai.plugin.dschat.tools.logger

// URL正文抓取服务：GET页面 → jsoup提取文本 → 截断返回
object FetchService {
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000L
            connectTimeoutMillis = 10_000L
        }
    }

    suspend fun fetch(url: String, maxChars: Int = 8000): String {
        try {
            val response = client.get(url) {
                header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36")
            }

            if (!response.status.isSuccess()) return "获取页面失败: HTTP ${response.status.value}"

            val ct = response.contentType()?.contentType ?: ""
            if (ct.isNotEmpty() && !ct.contains("text/html") && !ct.contains("text/plain")) {
                return "不支持的内容类型: $ct"
            }

            val text = extractText(response.bodyAsText())
            // maxChars=0 表示不截断，用于用户指定URL的预抓取场景
            if (text.isBlank()) return "页面无有效文本内容"
            return if (maxChars > 0 && text.length > maxChars) "${text.take(maxChars)}\n\n[...内容过长，已截断]"
            else text
        } catch (e: Exception) {
            logger.warning("WebFetch failed: $url - ${e.message}")
            return "获取页面失败: ${e.message}"
        }
    }

    // jsoup正文提取，失败时降级为正则脱标签
    private fun extractText(html: String): String {
        return try {
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("script, style, nav, footer, header, " +
                "iframe, noscript, .sidebar, .advertisement, " +
                "[role=navigation], [role=banner]").remove()

            (doc.body()?.wholeText() ?: doc.wholeText())
                .replace(Regex("\\n{3,}"), "\n\n")
                .replace(Regex("[\\t ]+"), " ")
                .trim()
        } catch (_: Exception) {
            html.replace(Regex("<[^>]*>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    // Tavily专用客户端：用户URL批量提取，JS渲染SPA页面需较长超时
    private val tavilyClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 90_000L
            connectTimeoutMillis = 15_000L
        }
    }

    // Tavily批量提取结果
    data class TavilyResult(
        val url: String,
        val success: Boolean,
        val content: String = ""
    )

    // 用户指定URL时直接调Tavily批量提取，跳过Ktor/jsoup解决SPA空壳问题
    suspend fun fetchBatchViaTavily(urls: List<String>): List<TavilyResult> {
        if (urls.isEmpty()) return emptyList()
        val apiKey = DeepSeekConfig.webSearch.tavilyApiKey
        if (apiKey.isBlank()) {
            logger.warning("Tavily API key未配置，回退到LLM自行处理")
            return urls.map { TavilyResult(it, false) }
        }
        try {
            val body = json.encodeToString(JsonObject.serializer(), buildJsonObject {
                putJsonArray("urls") { urls.forEach { add(it) } }
                put("extract_depth", "advanced")
                put("format", "text")
                put("chunks_per_source", 5)
                put("timeout", 60)
            })
            val response = tavilyClient.post("https://api.tavily.com/extract") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(body)
            }
            if (!response.status.isSuccess()) {
                logger.warning("Tavily extract HTTP ${response.status.value}")
                return urls.map { TavilyResult(it, false) }
            }
            val data = json.decodeFromString(JsonObject.serializer(), response.bodyAsText())
            val results = data["results"]?.jsonArray ?: return urls.map { TavilyResult(it, false) }
            return urls.map { url ->
                val match = results.find {
                    it.jsonObject["url"]?.jsonPrimitive?.content == url
                }
                if (match != null) {
                    val content = match.jsonObject["raw_content"]?.jsonPrimitive?.content ?: ""
                    TavilyResult(url, content.isNotBlank(), content)
                } else {
                    TavilyResult(url, false) // 含failed_results中的URL
                }
            }
        } catch (e: Exception) {
            logger.warning("Tavily extract error: ${e.message}")
            return urls.map { TavilyResult(it, false) }
        }
    }

    fun close() {
        client.close()
        tavilyClient.close()
    }
}
