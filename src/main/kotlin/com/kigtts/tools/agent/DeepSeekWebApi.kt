package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class DeepSeekWebFailure(val status: Int) : Exception(when (status) {
    401, 403 -> "DeepSeek 密钥不可用"
    402 -> "DeepSeek 余额不足"
    429 -> "DeepSeek 请求较多，请稍后再试"
    else -> "DeepSeek 网页服务暂时不可用（$status）"
})

internal fun interface DeepSeekWebApi {
    suspend fun post(operation: String, key: String, body: JsonObject): JsonObject
}

/** Credentials are sent only to this fixed official origin, never to fetched pages or redirects. */
internal class HttpsDeepSeekWebApi : DeepSeekWebApi {
    override suspend fun post(operation: String, key: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        require(operation in setOf("search", "fetch"))
        val connection = URL("https://api.deepseek.com/v1/tools/$operation").openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 25_000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status != 200) throw DeepSeekWebFailure(status)
            require(connection.contentLengthLong <= 1024 * 1024) { "网页结果过大" }
            val text = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024) { "网页结果过大" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            PluginJson.parseToJsonElement(text).jsonObject
        } finally { connection.disconnect() }
    }
}
