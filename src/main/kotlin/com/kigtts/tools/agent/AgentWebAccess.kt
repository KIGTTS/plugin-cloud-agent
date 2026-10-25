package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal fun isOfficialDeepSeek(endpoint: String): Boolean = runCatching {
    val uri = URI(endpoint.trim())
    uri.scheme.equals("https", true) && uri.host.equals("api.deepseek.com", true) &&
        uri.port in setOf(-1, 443) && uri.userInfo == null && uri.query == null && uri.fragment == null
}.getOrDefault(false)

/** Read settings and credentials at call time so edits apply to ongoing conversations. */
internal class AgentWebAccess(
    private val settings: () -> AgentSettings = { AgentSettings() },
    private val secrets: AgentSecrets? = null,
    private val api: DeepSeekWebApi = HttpsDeepSeekWebApi(),
    private val searchFallback: suspend (String) -> ToolResult = AgentWebTools::search,
    private val fetchFallback: suspend (String) -> ToolResult = AgentWebTools::fetch,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val rejected = ConcurrentHashMap<String, Long>()
    private fun candidates(): List<AgentSettings> {
        val saved = settings()
        return (listOf(saved) + saved.customProviders.map { saved.copy(provider = Provider.CUSTOM, customProviderId = it.id) } +
            Provider.entries.filter { it != Provider.CUSTOM }.map { saved.copy(provider = it, customProviderId = null) })
            .distinctBy { it.catalogKey() }.filter { !it.profile().codexDeviceLogin && isOfficialDeepSeek(it.profile().endpoint) }
    }
    suspend fun search(query: String): ToolResult {
        val input = query.trim()
        require(input.isNotBlank() && input.codePointCount(0, input.length) <= 128) { "搜索内容需要在 1–128 个字符内" }
        return routed("search", buildJsonObject { put("query", input); put("count", 8); put("need_content", false) }) { searchFallback(input) }
    }
    suspend fun fetch(url: String): ToolResult {
        val input = url.trim()
        val uri = URI(input)
        require(input.length <= 8192 && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "请输入有效的网页地址" }
        return routed("fetch", buildJsonObject { put("url", input) }) { fetchFallback(input) }
    }
    private suspend fun routed(operation: String, body: JsonObject, fallback: suspend () -> ToolResult): ToolResult {
        var reason: String? = null
        val keys = secrets
        if (keys != null) {
            rejected.entries.removeIf { it.value <= now() }
            for (candidate in candidates()) {
                val key = runCatching { if (keys.exists(candidate)) keys.read(candidate).trim() else "" }.getOrDefault("")
                if (key.isBlank()) continue
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                if (rejected.containsKey(fingerprint)) { reason = "DeepSeek 密钥不可用，已使用备用来源"; continue }
                try {
                    val result = api.post(operation, key, body)
                    return ToolResult(normalize(operation, body, result).toString())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: DeepSeekWebFailure) {
                    reason = failure.message + "，已使用备用来源"
                    if (failure.status in setOf(401, 402, 403)) {
                        rejected[fingerprint] = now() + 5 * 60_000
                        continue
                    }
                    break
                } catch (_: Exception) {
                    reason = "DeepSeek 网页服务暂时不可用，已使用备用来源"
                    break
                }
            }
        }
        val result = fallback()
        if (reason == null) return result
        return result.copy(content = buildJsonObject {
            put("provider", if (operation == "search") "Bing" else "网页直读")
            put("notice", reason)
            put("result", PluginJson.parseToJsonElement(result.content))
        }.toString())
    }
    private fun normalize(operation: String, body: JsonObject, result: JsonObject): JsonObject = buildJsonObject {
        put("provider", "DeepSeek")
        if (operation == "fetch") {
            val text = requireNotNull(result["content"]?.jsonPrimitive?.contentOrNull) { "缺少网页内容" }
            put("url", body.getValue("url")); put("title", result["title"]?.jsonPrimitive?.contentOrNull.orEmpty().take(240))
            put("text", text.take(24_000)); put("format", "markdown"); put("truncated", text.length > 24_000)
        } else {
            val results = requireNotNull(result["results"] as? JsonArray) { "缺少搜索结果" }
            put("results", buildJsonArray { results.take(8).forEach { item ->
                val entry = item.jsonObject
                val url = entry["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (runCatching { URI(url).scheme in setOf("https", "http") }.getOrDefault(false)) add(buildJsonObject {
                    put("url", url.take(8192)); put("title", entry["title"]?.jsonPrimitive?.contentOrNull.orEmpty().take(240))
                    put("summary", entry["summary"]?.jsonPrimitive?.contentOrNull.orEmpty().take(1600))
                    put("snippet", entry["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty().take(400))
                    listOf("date", "authority", "rerank_score", "site_name").forEach { field -> entry[field]?.let { put(field, it) } }
                })
            } })
        }
    }
}
