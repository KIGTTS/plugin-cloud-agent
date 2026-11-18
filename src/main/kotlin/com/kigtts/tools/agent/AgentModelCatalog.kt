package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal fun modelsEndpoint(base: String) = openCodeCatalogEndpoint(base) ?: (completionEndpoint(base).removeSuffix("/chat/completions")
    .removeSuffix("/chat/completions").removeSuffix("/responses").removeSuffix("/messages") + "/models")
internal fun parseModelCatalog(text: String): List<String> {
    if (!(text.toByteArray().size <= 2 * 1024 * 1024)) throw ModelCatalogFailure(AgentNotice("model.catalog_large", "模型列表过大"))
    val value = PluginJson.parseToJsonElement(text)
    val array = if (value is JsonArray) value else value.jsonObject.let { it["data"] as? JsonArray ?: it["models"] as? JsonArray ?: (it["data"] as? JsonObject)?.get("models") as? JsonArray } ?: throw ModelCatalogFailure(AgentNotice("model.catalog_no_list", "服务没有返回模型列表，请检查服务地址和密钥"))
    val models = array.mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull ?: (item as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
        .filter { it.length in 1..256 && it.none(Char::isISOControl) }.distinct().sorted().take(1000)
    if (!(models.isNotEmpty())) throw ModelCatalogFailure(AgentNotice("model.catalog_no_models", "服务没有返回可用模型，请检查账户的模型权限")); return models
}
internal fun parseManualModels(value: String): List<String> {
    val items = if (value.trimStart().startsWith('[')) PluginJson.parseToJsonElement(value).jsonArray.map { if (it is JsonObject) it.getValue("id").jsonPrimitive.content else it.jsonPrimitive.content } else value.lines().map(String::trim).filter(String::isNotBlank)
    require(items.size <= 1000 && items.all { it.length in 1..256 && it.none(Char::isISOControl) }) { "每行一个模型ID，最多1000项，每项不超过256字符" }
    return items.distinct()
}
internal data class DiscoveredModels(val models: List<String>, val info: Map<String, ModelInfo> = emptyMap())
internal fun parseModelInfo(text: String): Map<String, ModelInfo> {
    val root = PluginJson.parseToJsonElement(text)
    val array = if (root is JsonArray) root else root.jsonObject.let { it["data"] as? JsonArray ?: it["models"] as? JsonArray } ?: return emptyMap()
    return array.mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        val id = model["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val efforts = (model["effort"] as? JsonObject)?.get("supported_levels") as? JsonArray ?: model["reasoningEfforts"] as? JsonArray ?: model["supported_reasoning_efforts"] as? JsonArray
        val levels = efforts.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.matches(Regex("[a-z0-9_-]{1,32}")) }.distinct().take(12)
        val modalities = model["input_modalities"] as? JsonArray ?: (model["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray
        val vision = model["vision"]?.jsonPrimitive?.booleanOrNull ?: modalities?.any { it.jsonPrimitive.content == "image" }
        val capacity = (model["context_length"] ?: model["context_window"] ?: model["max_input_tokens"])?.jsonPrimitive?.intOrNull?.takeIf { it in 4096..2_000_000 }
        id to ModelInfo(levels, vision, capacity)
    }.toMap()
}
internal fun interface ModelsTransport { suspend fun list(endpoint: String, key: String): DiscoveredModels }
internal class HttpsModelsTransport(private val beforeRequest: suspend () -> Unit = {}) : ModelsTransport {
    override suspend fun list(endpoint: String, key: String): DiscoveredModels = withContext(Dispatchers.IO) {
        beforeRequest()
        val connection = URL(endpoint).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"; connection.instanceFollowRedirects = false; connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $key"); connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-api-key", key); connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.setRequestProperty("User-Agent", "KIGTTSNEXT-Agent/0.9.5")
            when (connection.responseCode) {
                200 -> Unit
                401, 403 -> throw ModelCatalogFailure(AgentNotice("model.catalog_auth", "密钥无效或无模型列表权限，已保留原列表"))
                404, 405 -> throw ModelCatalogFailure(AgentNotice("model.catalog_address", "服务地址不支持获取模型列表，请检查地址"))
                else -> throw ModelCatalogFailure(AgentNotice("model.catalog_http", "模型列表获取失败（HTTP {code}），已保留原列表", mapOf("code" to connection.responseCode.toString())))
            }
            val bytes = connection.inputStream.use { input -> java.io.ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 2 * 1024 * 1024); output.write(buffer, 0, count) }
                output.toByteArray()
            } }
            if (!(bytes.size <= 2 * 1024 * 1024)) throw ModelCatalogFailure(AgentNotice("model.catalog_large", "模型列表过大"))
            bytes.toString(Charsets.UTF_8).let { DiscoveredModels(parseModelCatalog(it), parseModelInfo(it)) }
        } finally { connection.disconnect() }
    }
}
internal class AgentModelCatalog(private val store: AgentStore, private val secrets: AgentSecrets, private val changed: () -> Unit,
    private val transport: ModelsTransport = HttpsModelsTransport(), private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onValidated: (AgentSettings, String) -> Unit = { _, _ -> }) {
    private val jobs = mutableMapOf<String, Job>()
    @Synchronized fun refresh(settings: AgentSettings, force: Boolean = false) {
        val key = settings.catalogKey(); val profile = settings.effectiveProfile()
        if (!secrets.exists(settings) || profile.endpoint.isBlank()) {
            if (force) { store.modelStatus = store.modelStatus + (key to AgentNotice("model.catalog_setup", "请先填写此供应商的地址和密钥")); changed() }
            return
        }
        if (!force && (jobs[key]?.isActive == true || System.currentTimeMillis() - profile.modelsUpdatedAt < 24 * 60 * 60 * 1000L)) return
        jobs.remove(key)?.cancel()
        store.modelStatus = store.modelStatus + (key to AgentNotice("model.catalog_loading", "正在获取模型列表…")); changed()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val usedKey = secrets.read(settings)
                val discovered = transport.list(modelsEndpoint(profile.endpoint), usedKey)
                val models = discovered.models
                currentCoroutineContext().ensureActive()
                store.updateSettings { current ->
                    if (settings.provider == Provider.CUSTOM && settings.customProviderId != null) current.copy(customProviders = current.customProviders.map {
                        if (it.id == settings.customProviderId && it.profile.endpoint == profile.endpoint) it.copy(profile = it.profile.copy(models = models, modelsUpdatedAt = System.currentTimeMillis(), modelInfo = it.profile.modelInfo.filterKeys { id -> id in models } + discovered.info)) else it
                    }) else {
                        val old = requireNotNull(current.profiles[settings.provider])
                        if (old.endpoint != profile.endpoint) current else current.copy(profiles = current.profiles + (settings.provider to old.copy(models = models, modelsUpdatedAt = System.currentTimeMillis(), modelInfo = old.modelInfo.filterKeys { it in models } + discovered.info)))
                    }
                }
                if (models.isNotEmpty()) onValidated(settings, usedKey)
                store.modelStatus = store.modelStatus + (key to AgentNotice("model.catalog_count", "已获取 {count} 个模型", mapOf("count" to models.size.toString())))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { store.modelStatus = store.modelStatus + (key to ((error as? ModelCatalogFailure)?.notice ?: AgentNotice("model.catalog_failed", "模型列表获取失败，已保留原列表"))) }
            finally { changed() }
        }
        jobs[key] = job; job.start()
    }
    fun close() { scope.cancel() }
}
