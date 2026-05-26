package top.colter.mirai.plugin.dschat.deepseek

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.delay
import top.colter.mirai.plugin.dschat.tools.json
import top.colter.mirai.plugin.dschat.tools.logger
import kotlin.time.Duration.Companion.seconds

class DeepSeekClient(private val apiKey: String, private val apiUrl: String) {

    private val client = HttpClient(OkHttp) {
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000L
            connectTimeoutMillis = 30_000L
            socketTimeoutMillis = 120_000L
        }
        expectSuccess = false
    }

    suspend fun chat(request: ChatRequest, retries: Int = 2): Result<ChatResponse> {
        val requestBody = json.encodeToString(ChatRequest.serializer(), request)

        var lastException: Exception? = null
        repeat(retries + 1) { attempt ->
            if (attempt > 0) {
                delay((attempt * 2L).seconds)
            }
            try {
                val response: HttpResponse = client.post(apiUrl) {
                    setBody(requestBody)
                }

                if (response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    val chatResponse = json.decodeFromString(ChatResponse.serializer(), body)
                    return Result.success(chatResponse)
                }

                val errorBody = response.bodyAsText()
                val statusCode = response.status.value

                if (statusCode in 500..599) {
                    logger.warning("DeepSeek API server error ($statusCode), attempt ${attempt + 1}")
                    lastException = IOException("Server error: $statusCode")
                    return@repeat
                }

                logger.error("DeepSeek API error: $statusCode, body: $errorBody")
                return Result.failure(Exception("API 请求失败 ($statusCode): $errorBody"))
            } catch (e: IOException) {
                logger.warning("DeepSeek network error, attempt ${attempt + 1}: ${e.message}")
                lastException = e
            } catch (e: Exception) {
                logger.error("DeepSeek request failed", e)
                return Result.failure(e)
            }
        }
        return Result.failure(lastException ?: Exception("请求失败"))
    }

    fun close() {
        client.close()
    }
}
