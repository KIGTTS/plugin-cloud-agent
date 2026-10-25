package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.*
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal class AgentDownload(private val environment: PluginEnvironment,
    private val connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val resolve: (String) -> URI = ::publicResourceUri) {
    suspend fun download(url: String, path: String, expected: String? = null, media: Boolean = false): ToolResult = withContext(Dispatchers.IO) { withTimeout(120_000) {
        require(path.startsWith("workspace/") && !path.endsWith('/')) { "下载资源只能写入workspace/" }
        require(expected == null || expected.matches(Regex("[a-fA-F0-9]{64}")))
        val target = environment.storage.file(path); require(!target.exists()) { "目标已存在，请使用新文件名" }
        val limit = if (media) MEDIA_LIMIT else LIMIT
        environment.requirePermission("network")
        val temp = environment.storage.file("tmp/download-${UUID.randomUUID()}.part").apply { parentFile!!.mkdirs() }
        try {
            var address = url
            repeat(5) { redirect ->
                val uri = resolve(address)
                val connection = connect(uri.toURL())
                try {
                    connection.instanceFollowRedirects = false; connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                    connection.setRequestProperty("User-Agent", "KIGTTS-Assets/1.0")
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        require(redirect < 4) { "跳转次数过多" }; address = uri.resolve(requireNotNull(connection.getHeaderField("Location"))).toString()
                    } else {
                        require(status in 200..299) { "资源下载失败（$status）" }
                        val mime = connection.contentType.orEmpty().substringBefore(';').lowercase(java.util.Locale.ROOT)
                        require(!media || mime.startsWith("audio/") || mime.startsWith("video/") || mime in setOf("application/octet-stream", "application/ogg", "")) { "地址没有返回音视频文件（$mime），请提供直链" }
                        require(connection.contentLengthLong <= limit) { "资源超过${limit / 1024 / 1024}MiB" }
                        val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
                        connection.inputStream.use { input -> temp.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) { currentCoroutineContext().ensureActive(); val read = input.read(buffer); if (read < 0) break
                                total += read; require(total <= limit) { "资源超过${limit / 1024 / 1024}MiB" }; digest.update(buffer, 0, read); output.write(buffer, 0, read) }
                        } }
                        require(total > 0) { "资源为空" }
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        require(expected == null || hash.equals(expected, true)) { "资源SHA-256不匹配" }
                        if (media) {
                            val prefix = temp.inputStream().use { input -> val bytes = ByteArray(512); val size = input.read(bytes); String(bytes, 0, size.coerceAtLeast(0), Charsets.UTF_8).trimStart().lowercase(java.util.Locale.ROOT) }
                            require(!prefix.startsWith("<!doctype html") && !prefix.startsWith("<html")) { "地址返回了网页，请换成音视频文件直链" }
                        }
                        target.parentFile!!.mkdirs(); java.nio.file.Files.move(temp.toPath(), target.toPath())
                        return@withTimeout ToolResult(buildJsonObject { put("ok", true); put("path", path); put("resource", PluginJson.encodeToJsonElement(PluginResource(path)))
                            put("bytes", total); put("sha256", hash); put("mime", connection.contentType.orEmpty().substringBefore(';').take(100)); put("url", address) }.toString())
                    }
                } finally { connection.disconnect() }
            }
            error("资源下载失败")
        } finally { temp.delete() }
    } }
    companion object { const val LIMIT = 32L * 1024 * 1024; const val MEDIA_LIMIT = 256L * 1024 * 1024 }
}
internal fun publicResourceUri(url: String): URI {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.userInfo == null && !uri.host.isNullOrBlank() && uri.port in setOf(-1, 443)) { "请使用公开HTTPS资源地址" }
    require(InetAddress.getAllByName(uri.host).all { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isSiteLocalAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }) { "不访问本机或局域网资源" }
    return uri
}
