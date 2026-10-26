package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal fun searchApiEndpoint(value: String): String {
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "服务地址需要使用 HTTPS，且不能包含帐号或查询参数" }
    return uri.toASCIIString().trimEnd('/')
}
internal data class SearchApiRequest(val url: String, val headers: Map<String, String>, val body: JsonObject?)
internal fun searchApiRequest(source: SearchSource, endpoint: String, key: String, query: String, count: Int): SearchApiRequest {
    val base = searchApiEndpoint(endpoint)
    val limit = count.coerceIn(3, 10)
    val body = buildJsonObject {
        when (source) {
            SearchSource.BOCHA -> { put("query", query); put("count", limit); put("summary", true); put("freshness", "noLimit") }
            SearchSource.TAVILY -> { put("query", query); put("max_results", limit); put("search_depth", "basic"); put("include_answer", false) }
            SearchSource.SERPER -> {
                put("q", query); put("num", limit)
                Locale.getDefault().language.takeIf { it.isNotBlank() }?.let { put("hl", it) }
                Locale.getDefault().country.takeIf { it.length == 2 }?.let { put("gl", it.lowercase(Locale.ROOT)) }
            }
            SearchSource.BRAVE -> Unit
            else -> error("此搜索源无需单独 API 配置")
        }
    }
    val header = when (source) { SearchSource.BRAVE -> "X-Subscription-Token"; SearchSource.SERPER -> "X-API-KEY"; else -> "Authorization" }
    return SearchApiRequest(if (source == SearchSource.BRAVE) "$base?q=${URLEncoder.encode(query, "UTF-8")}&count=$limit" else base,
        mapOf(header to (if (header == "Authorization") "Bearer $key" else key)), body.takeUnless { source == SearchSource.BRAVE })
}
internal fun normalizeSearchApi(source: SearchSource, root: JsonObject, count: Int): JsonObject {
    if (source == SearchSource.BOCHA) check(root["code"]?.jsonPrimitive?.intOrNull.let { it == null || it == 200 }) { "博查未返回有效结果，请检查密钥和账户额度" }
    val entries = when (source) {
        SearchSource.BOCHA -> ((root["data"] as? JsonObject ?: root)["webPages"] as? JsonObject)?.get("value")
        SearchSource.BRAVE -> (root["web"] as? JsonObject)?.get("results")
        SearchSource.SERPER -> root["organic"]
        SearchSource.TAVILY -> root["results"]
        else -> null
    } as? JsonArray ?: error("${source.label} 未返回搜索结果")
    return buildJsonObject {
        put("provider", source.label)
        put("results", buildJsonArray { entries.take(count.coerceIn(3, 10)).forEach { item ->
            val entry = item as? JsonObject ?: return@forEach
            fun text(vararg keys: String) = keys.firstNotNullOfOrNull { entry[it]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
            val url = text("url", "link")
            if (runCatching { val uri = URI(url); uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null }.getOrDefault(false)) add(buildJsonObject {
                put("url", url.take(8192)); put("title", text("title", "name").take(240))
                put("summary", text("summary", "content", "description", "snippet").take(1600))
                text("datePublished", "published_date", "date").takeIf(String::isNotBlank)?.let { put("date", it.take(100)) }
            })
        } })
    }
}
internal fun interface SearchApiTransport { suspend fun search(source: SearchSource, endpoint: String, key: String, query: String, count: Int): JsonObject }
internal class HttpsSearchApi : SearchApiTransport {
    override suspend fun search(source: SearchSource, endpoint: String, key: String, query: String, count: Int): JsonObject = withContext(Dispatchers.IO) {
        val request = searchApiRequest(source, endpoint, key, query, count)
        val connection = URL(request.url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 10_000; connection.readTimeout = 25_000
            connection.requestMethod = if (request.body == null) "GET" else "POST"
            request.headers.forEach(connection::setRequestProperty)
            connection.setRequestProperty("Accept", "application/json")
            request.body?.let { body ->
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode == 200) { "${source.label} 搜索失败（${connection.responseCode}）" }
            require(connection.contentLengthLong <= 1024 * 1024) { "搜索结果过大" }
            val bytes = connection.inputStream.use { input -> java.io.ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer); if (size < 0) break
                    require(output.size() + size <= 1024 * 1024) { "搜索结果过大" }; output.write(buffer, 0, size)
                }
                output.toByteArray()
            } }
            normalizeSearchApi(source, PluginJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject, count)
        } finally { connection.disconnect() }
    }
}
