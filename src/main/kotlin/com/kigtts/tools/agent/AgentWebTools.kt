package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.*
import javax.net.ssl.HttpsURLConnection
import javax.xml.parsers.DocumentBuilderFactory

/** Public HTTPS research, bounded replies, no provider key forwarded to websites. */
internal object AgentWebTools {
    suspend fun search(query: String): ToolResult = withContext(Dispatchers.IO) {
        require(query.isNotBlank() && query.length <= 500)
        val url = "https://www.bing.com/search?format=rss&q=" + URLEncoder.encode(query, "UTF-8")
        val xml = get(url).second
        ToolResult(parseSearch(xml).toString())
    }
    suspend fun searchImages(query: String): ToolResult = withContext(Dispatchers.IO) {
        require(query.isNotBlank() && query.length <= 300)
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=8&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=640&gsrsearch=" + URLEncoder.encode(query + " filetype:bitmap", "UTF-8")
        val json = PluginJson.parseToJsonElement(get(url).second).jsonObject
        val pages = json["query"]?.jsonObject?.get("pages")?.jsonObject.orEmpty()
        ToolResult(buildJsonObject { put("provider", "Wikimedia Commons"); put("query", query)
            put("images", buildJsonArray { pages.values.take(8).forEach { page ->
                val item = page.jsonObject; val image = item["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@forEach
                val metadata = image["extmetadata"]?.jsonObject.orEmpty()
                fun meta(key: String) = plain(metadata[key]?.jsonObject?.get("value")?.jsonPrimitive?.content.orEmpty()).take(500)
                add(buildJsonObject { put("title", item["title"]?.jsonPrimitive?.content.orEmpty()); put("url", image["url"]?.jsonPrimitive?.content.orEmpty())
                    put("thumbnail", image["thumburl"]?.jsonPrimitive?.content.orEmpty()); put("source", image["descriptionurl"]?.jsonPrimitive?.content.orEmpty())
                    put("artist", meta("Artist")); put("license", meta("LicenseShortName")); put("licenseUrl", meta("LicenseUrl")) })
            } })
        }.toString())
    }
    internal fun parseSearch(xml: String): JsonArray {
        val factory = DocumentBuilderFactory.newInstance()
        listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false).forEach { (key, value) -> runCatching { factory.setFeature(key, value) } }
        factory.isExpandEntityReferences = false
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val items = document.getElementsByTagName("item")
        return buildJsonArray {
            for (index in 0 until minOf(items.length, 8)) {
                val item = items.item(index) as org.w3c.dom.Element
                fun value(key: String) = item.getElementsByTagName(key).item(0)?.textContent.orEmpty()
                val url = value("link")
                if (url.startsWith("https://") || url.startsWith("http://")) add(buildJsonObject {
                    put("title", plain(value("title")).take(240)); put("url", url); put("summary", plain(value("description")).take(1200))
                })
            }
        }
    }
    suspend fun fetch(url: String): ToolResult = withContext(Dispatchers.IO) {
        val (source, html) = get(url)
        ToolResult(buildJsonObject {
            put("url", source); put("text", plain(html).take(16000)); put("images", htmlImageLinks(source, html))
        }.toString())
    }
    internal fun plain(html: String): String = html.replace(Regex("(?is)<(script|style|noscript)\\b[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ").trim()
    private fun get(input: String): Pair<String, String> {
        var url = input
        repeat(4) {
            val uri = URI(url)
            require(uri.scheme == "https" && uri.userInfo == null && !uri.host.isNullOrBlank() && uri.port in setOf(-1, 443)) { "网页地址需要使用公开HTTPS地址" }
            require(InetAddress.getAllByName(uri.host).all { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isSiteLocalAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }) { "不访问本机或局域网地址" }
            val connection = URL(url).openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false; connection.connectTimeout = 10_000; connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "KIGTTSNEXT/0.9.6 (https://github.com/LHT02/KIGTTSNEXT)")
                if (connection.responseCode in 300..399) {
                    url = uri.resolve(requireNotNull(connection.getHeaderField("Location"))).toString()
                } else {
                    require(connection.responseCode == 200) { "网页请求失败（${connection.responseCode}）" }
                    require(!connection.contentType.orEmpty().startsWith("image/", true)) { "这是图片地址，请使用download_image保存，再read_image查看；不要把图片当网页正文读取" }
                    require(connection.contentLengthLong <= 1024 * 1024) { "网页过大" }
                    val bytes = connection.inputStream.use { stream ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val count = stream.read(buffer); if (count < 0) break; require(out.size() + count <= 1024 * 1024) { "网页过大" }; out.write(buffer, 0, count) }
                        out.toByteArray()
                    }
                    return url to bytes.toString(Charsets.UTF_8)
                }
            } finally { connection.disconnect() }
        }
        error("网页跳转次数过多")
    }
}
