package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import java.security.MessageDigest

/** A successful catalogue request triggers one explicit offer; presence of a key alone never does. */
internal class AgentSearchController(private val store: AgentStore, private val secrets: AgentSecrets) {
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun validated(connection: AgentSettings, usedKey: String) {
        val current = deepSeekConnections(store.settings).firstOrNull { it.catalogKey() == connection.catalogKey() } ?: return
        if (current.profile().endpoint != connection.profile().endpoint || usedKey.isBlank() ||
            runCatching { secrets.read(current) }.getOrDefault("") != usedKey) return
        store.updateSettings { saved ->
            if (saved.search.deepSeekOffered || saved.search.source == SearchSource.DEEPSEEK) saved
            else saved.copy(search = saved.search.copy(pendingDeepSeek = current.catalogKey(), pendingDeepSeekKeyHash = digest(usedKey)))
        }
    }
    fun decorate(page: PluginPage?): PluginPage? = page?.let {
        if (store.settings.search.pendingDeepSeek != null && !store.settings.search.deepSeekOffered)
            it.copy(onEnterAction = "search-offer/" + it.onEnterAction.orEmpty()) else it
    }
    private fun availableDeepSeek() = deepSeekConnections(store.settings).any {
        it.profile().models.isNotEmpty() && runCatching { secrets.exists(it) && secrets.read(it).isNotBlank() }.getOrDefault(false)
    }
    private fun pendingStillAvailable(): Boolean {
        val search = store.settings.search
        val candidate = deepSeekConnections(store.settings).firstOrNull { it.catalogKey() == search.pendingDeepSeek } ?: return false
        return candidate.profile().models.isNotEmpty() && runCatching { digest(secrets.read(candidate)) == search.pendingDeepSeekKeyHash }.getOrDefault(false)
    }
    fun page(id: String): PluginPage? {
        val search = store.settings.search
        if (id == "search-settings") return PluginPage(id, "搜索与网页读取", listOf(
            PageBlock.Group("搜索", listOf(
                PageBlock.Select("默认搜索源", SearchSource.entries.map { PageAction("search-source/${it.name}", it.label) }, "search-source/${search.source.name}", symbol = "search"),
                PageBlock.Slider(PluginControl.Slider("搜索结果数量", "search-count", search.resultCount.toFloat(), 3f, 10f, steps = 6)),
                PageBlock.Toggle("失败时使用备用来源", search.fallback, "search-fallback", "搜索使用 Bing，网页读取使用直读"))),
            PageBlock.Group("网页读取", listOf(PageBlock.Select("读取方式", WebReadSource.entries.map { PageAction("search-read/${it.name}", it.label) }, "search-read/${search.readSource.name}", symbol = "language"))),
            PageBlock.Group("搜索 API", listOf(PageBlock.Actions(SearchSource.entries.filter { it.endpoint.isNotBlank() }.map {
                PageAction("search-config/${it.name}", it.label, PluginIcon.NONE, symbol = "manage_search") }))),
            PageBlock.Text("DeepSeek 使用已添加的官方供应商密钥。其它搜索 API 可分别配置。")))
        val source = id.removePrefix("search-api/").takeIf { id.startsWith("search-api/") }
            ?.let { runCatching { SearchSource.valueOf(it) }.getOrNull() }?.takeIf { it.endpoint.isNotBlank() } ?: return null
        return PluginPage(id, source.label, listOf(PageBlock.Group("连接", listOf(
            PageBlock.Input("服务地址", search.endpoints[source] ?: source.endpoint, "search-endpoint/${source.name}"),
            PageBlock.Input("密钥", if (secrets.existsCustom(source.secretId())) "••••••••" else "", "search-key/${source.name}", secret = true))),
            PageBlock.Actions(listOf(PageAction("search-source/${source.name}", "设为默认搜索源", PluginIcon.NONE, symbol = "search")))))
    }
    fun handle(action: String, value: String): PluginOutcome? {
        when {
            action == "search-settings" -> return PluginOutcome.OpenPage(action)
            action.startsWith("search-offer/") -> {
                if (store.settings.search.deepSeekOffered || store.settings.search.pendingDeepSeek == null) return PluginOutcome.RefreshUi
                if (!pendingStillAvailable()) { store.updateSettings { it.copy(search = it.search.copy(pendingDeepSeek = null, pendingDeepSeekKeyHash = null)) }; return PluginOutcome.RefreshUi }
                store.updateSettings { it.copy(search = it.search.copy(deepSeekOffered = true)) }
                return PluginOutcome.Confirm("使用 DeepSeek 搜索？", "已连接 DeepSeek。是否将它设为默认搜索源？可随时在助手设置中更改。", PageAction("search-accept-deepseek", "设为默认"), "暂不")
            }
            action == "search-accept-deepseek" -> {
                check(pendingStillAvailable()) { "DeepSeek 连接已改变，请重新验证密钥" }
                store.updateSettings { it.copy(search = it.search.copy(source = SearchSource.DEEPSEEK, pendingDeepSeek = null, pendingDeepSeekKeyHash = null)) }
                return PluginOutcome.RefreshUi
            }
            action.startsWith("search-source/") -> {
                val source = SearchSource.valueOf(action.substringAfter('/'))
                when (source) {
                    SearchSource.DEEPSEEK -> check(availableDeepSeek()) { "请先添加可用的 DeepSeek 官方供应商" }
                    SearchSource.BING -> Unit
                    else -> check(secrets.existsCustom(source.secretId()) && secrets.readCustom(source.secretId()).isNotBlank()) { "请先配置此搜索 API 的密钥" }
                }
                store.updateSettings { it.copy(search = it.search.copy(source = source)) }
                return PluginOutcome.RefreshUi
            }
            action == "search-count" -> { val count = value.toFloat().toInt().coerceIn(3, 10); store.updateSettings { it.copy(search = it.search.copy(resultCount = count)) }; return PluginOutcome.RefreshUi }
            action == "search-fallback" -> { store.updateSettings { it.copy(search = it.search.copy(fallback = !it.search.fallback)) }; return PluginOutcome.RefreshUi }
            action.startsWith("search-read/") -> { val source = WebReadSource.valueOf(action.substringAfter('/')); store.updateSettings { it.copy(search = it.search.copy(readSource = source)) }; return PluginOutcome.RefreshUi }
            action.startsWith("search-config/") -> return PluginOutcome.OpenPage("search-api/" + action.substringAfter('/'))
            action.startsWith("search-key/") -> return PluginOutcome.TextInput("搜索 API 密钥", "search-save-key/" + action.substringAfter('/'), secret = true)
            action.startsWith("search-endpoint/") -> {
                val source = SearchSource.valueOf(action.substringAfter('/'))
                return PluginOutcome.TextInput("搜索服务地址", "search-save-endpoint/${source.name}", store.settings.search.endpoints[source] ?: source.endpoint)
            }
            action.startsWith("search-save-key/") -> { val source = SearchSource.valueOf(action.substringAfter('/')); secrets.writeCustom(source.secretId(), value); return PluginOutcome.RefreshUi }
            action.startsWith("search-save-endpoint/") -> {
                val source = SearchSource.valueOf(action.substringAfter('/')); require(source.endpoint.isNotBlank())
                val endpoint = searchApiEndpoint(value.trim())
                store.updateSettings { it.copy(search = it.search.copy(endpoints = it.search.endpoints + (source to endpoint))) }; return PluginOutcome.RefreshUi
            }
        }
        return null
    }
}
