package top.colter.mirai.plugin.dschat.agent

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
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
            if (text.isBlank()) return "页面无有效文本内容"

            return if (text.length > maxChars) "${text.take(maxChars)}\n\n[...内容过长，已截断]"
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

    fun close() = client.close()
}
