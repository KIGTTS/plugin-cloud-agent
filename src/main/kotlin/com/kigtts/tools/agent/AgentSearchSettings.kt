package com.kigtts.tools.agent

import kotlinx.serialization.Serializable

@Serializable enum class SearchSource(val label: String, val endpoint: String = "") {
    BING("Bing（无需密钥）"), DEEPSEEK("DeepSeek"),
    BOCHA("博查", "https://api.bochaai.com/v1/web-search"),
    TAVILY("Tavily", "https://api.tavily.com/search"),
    BRAVE("Brave", "https://api.search.brave.com/res/v1/web/search"),
    SERPER("Serper", "https://google.serper.dev/search")
}
@Serializable enum class WebReadSource(val label: String) { AUTO("跟随搜索源"), DIRECT("直接读取网页"), DEEPSEEK("DeepSeek Fetch") }
@Serializable data class AgentSearchSettings(
    val source: SearchSource = SearchSource.BING,
    val readSource: WebReadSource = WebReadSource.AUTO,
    val resultCount: Int = 8,
    val fallback: Boolean = true,
    val endpoints: Map<SearchSource, String> = emptyMap(),
    val deepSeekOffered: Boolean = false,
    val pendingDeepSeek: String? = null,
    val pendingDeepSeekKeyHash: String? = null,
)
internal fun SearchSource.secretId(): String = when (this) {
    SearchSource.BOCHA -> "00000000-0000-0000-0000-000000000001"
    SearchSource.TAVILY -> "00000000-0000-0000-0000-000000000002"
    SearchSource.BRAVE -> "00000000-0000-0000-0000-000000000003"
    SearchSource.SERPER -> "00000000-0000-0000-0000-000000000004"
    else -> error("此搜索源使用现有连接")
}
internal fun deepSeekConnections(saved: AgentSettings): List<AgentSettings> =
    (listOf(saved) + saved.customProviders.map { saved.copy(provider = Provider.CUSTOM, customProviderId = it.id) } +
        Provider.entries.filter { it != Provider.CUSTOM }.map { saved.copy(provider = it, customProviderId = null) })
        .distinctBy { it.catalogKey() }.filter { !it.profile().codexDeviceLogin && isOfficialDeepSeek(it.profile().endpoint) }
