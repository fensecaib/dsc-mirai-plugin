package top.colter.mirai.plugin.dschat.agent

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import top.colter.mirai.plugin.dschat.tools.json
import top.colter.mirai.plugin.dschat.tools.logger

// 三层后备搜索客户端: Exa MCP → Parallel MCP → DDG JSON API（全部零认证）
object SearchClient {
    private const val EXA_URL = "https://mcp.exa.ai/mcp"
    private const val PARALLEL_URL = "https://search.parallel.ai/mcp"
    private const val DDG_URL = "https://api.duckduckgo.com/"

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000L
            connectTimeoutMillis = 15_000L
        }
    }

    @Serializable
    private data class McpRequest(
        val jsonrpc: String = "2.0",
        val id: Int = 1,
        val method: String,
        val params: McpParams
    )

    @Serializable
    private data class McpParams(
        val name: String,
        val arguments: JsonObject
    )

    suspend fun search(query: String): String {
        var lastError: String? = null

        // Tier 1: Exa MCP — 免费JSON-RPC，延迟 ~1.5s
        try {
            val result = searchExa(query)
            if (result.isNotBlank()) return result
        } catch (e: Exception) {
            lastError = "Exa: ${e.message}"
            logger.warning("Search Exa failed: ${e.message}")
        }

        // Tier 2: Parallel MCP — 免费JSON-RPC，延迟 ~2.5s
        try {
            val result = searchParallel(query)
            if (result.isNotBlank()) return result
        } catch (e: Exception) {
            lastError = "Parallel: ${e.message}"
            logger.warning("Search Parallel failed: ${e.message}")
        }

        // Tier 3: DDG JSON Instant Answer — 免费百科查询，不限流
        try {
            val result = searchDdgJson(query)
            if (result.isNotBlank()) return result
        } catch (e: Exception) {
            lastError = "DDG: ${e.message}"
            logger.warning("Search DDG failed: ${e.message}")
        }

        return "所有搜索后端均失败。最后错误: $lastError"
    }

    // ── Exa MCP ──────────────────────────────────────────

    private suspend fun searchExa(query: String): String {
        val args = buildJsonObject {
            put("query", query)
            put("type", "auto")
            put("numResults", 5)
            put("livecrawl", "fallback")
            put("contextMaxCharacters", 10000)
        }
        val body = json.encodeToString(McpRequest.serializer(),
            McpRequest(method = "tools/call", params = McpParams("web_search_exa", args)))

        val response = client.post(EXA_URL) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            setBody(body)
        }
        require(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        return parseSseText(response.bodyAsText()) // Exa返回SSE流
    }

    // ── Parallel MCP ─────────────────────────────────────

    private suspend fun searchParallel(query: String): String {
        val args = buildJsonObject {
            put("objective", query)
            putJsonArray("search_queries") { add(query) }
        }
        val body = json.encodeToString(McpRequest.serializer(),
            McpRequest(method = "tools/call", params = McpParams("web_search", args)))

        val response = client.post(PARALLEL_URL) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "opencode")
            setBody(body)
        }
        require(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        return extractTextContent(json.decodeFromString(JsonObject.serializer(), response.bodyAsText()))
    }

    // ── DDG JSON Instant Answer ──────────────────────────

    private suspend fun searchDdgJson(query: String): String {
        val response = client.get(DDG_URL) {
            parameter("q", query)
            parameter("format", "json")
            parameter("no_html", "1")
            parameter("skip_disambig", "1")
            header("User-Agent", "curl/8.0")
        }
        require(response.status.isSuccess()) { "HTTP ${response.status.value}" }

        val data = json.decodeFromString(JsonObject.serializer(), response.bodyAsText())
        val abstract = data["AbstractText"]?.jsonPrimitive?.content?.trim() ?: ""
        val heading = data["Heading"]?.jsonPrimitive?.content?.trim() ?: ""
        if (abstract.isEmpty()) return ""

        return if (heading.isNotEmpty()) "$heading\n\n$abstract" else abstract
    }

    // ── 公共解析 ─────────────────────────────────────────

    // SSE流式响应解析（Exa MCP使用）
    private fun parseSseText(raw: String): String {
        val sb = StringBuilder()
        for (line in raw.split("\n")) {
            if (!line.startsWith("data: ")) continue
            try {
                val data = json.decodeFromString(JsonObject.serializer(), line.substring(6))
                sb.append(extractTextContent(data))
            } catch (_: Exception) {}
        }
        return sb.toString().trim()
    }

    // 从MCP响应的 result.content 数组中提取 type=text 的内容
    private fun extractTextContent(data: JsonObject): String {
        val result = data["result"]?.jsonObject ?: return ""
        val content = result["content"]?.jsonArray ?: return ""
        val sb = StringBuilder()
        for (item in content) {
            val obj = item.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "text") {
                sb.append(obj["text"]?.jsonPrimitive?.content ?: "")
            }
        }
        return sb.toString()
    }

    fun close() = client.close()
}
