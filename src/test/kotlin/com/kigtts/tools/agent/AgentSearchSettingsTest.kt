package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentSearchSettingsTest {
    @get:Rule val temp = TemporaryFolder()
    private class Secrets : AgentSecrets {
        val keys = mutableMapOf<String, String>()
        override fun read(provider: Provider) = keys[provider.name].orEmpty()
        override fun exists(provider: Provider) = read(provider).isNotBlank()
        override fun write(provider: Provider, value: String) { keys[provider.name] = value }
        override fun readCustom(id: String) = keys[id].orEmpty()
        override fun existsCustom(id: String) = readCustom(id).isNotBlank()
        override fun writeCustom(id: String, value: String) { keys[id] = value }
    }
    @Test fun firstSuccessfulOfficialConnectionOffersOnceWithoutSilentlyChangingTheDefault() = runTest {
        val store = AgentStore(PluginStorage(temp.root)); val keys = Secrets().apply { write(Provider.DEEPSEEK, "key") }
        val controller = AgentSearchController(store, keys)
        val catalog = AgentModelCatalog(store, keys, {}, ModelsTransport { _, _ -> DiscoveredModels(listOf("model")) }, this, controller::validated)
        assertNull(controller.decorate(PluginPage("settings", "设置", emptyList()))!!.onEnterAction)
        catalog.refresh(store.settings, true); advanceUntilIdle()
        assertEquals(SearchSource.BING, store.settings.search.source)
        assertTrue(controller.decorate(PluginPage("chat", "对话", emptyList(), onEnterAction = "chat-viewed/test"))!!.onEnterAction!!.startsWith("search-offer/chat-viewed/"))
        val confirm = controller.handle("search-offer/", "") as PluginOutcome.Confirm
        assertEquals("暂不", confirm.cancelLabel); assertEquals(SearchSource.BING, store.settings.search.source)
        catalog.refresh(store.settings, true); advanceUntilIdle()
        assertNull(controller.decorate(PluginPage("settings", "设置", emptyList()))!!.onEnterAction)
        controller.handle(confirm.action.id, ""); assertEquals(SearchSource.DEEPSEEK, store.settings.search.source)
        assertTrue(AgentStore(PluginStorage(temp.root)).settings.search.deepSeekOffered)
    }
    @Test fun invalidOrStaleKeysAndLookalikeHostsNeverTriggerTheOffer() = runTest {
        val store = AgentStore(PluginStorage(temp.root)); val keys = Secrets().apply { write(Provider.DEEPSEEK, "new") }
        val controller = AgentSearchController(store, keys)
        controller.validated(store.settings, "old"); assertNull(store.settings.search.pendingDeepSeek)
        val custom = CustomProvider(profile = ProviderProfile("https://api.deepseek.com.evil.test", ""))
        store.updateSettings { it.copy(customProviders = listOf(custom), provider = Provider.CUSTOM, customProviderId = custom.id) }
        keys.writeCustom(custom.id, "proxy")
        controller.validated(store.settings, "proxy"); assertNull(store.settings.search.pendingDeepSeek)
        val catalog = AgentModelCatalog(store, keys, {}, ModelsTransport { _, _ -> error("401") }, this, controller::validated)
        catalog.refresh(store.settings, true); advanceUntilIdle(); assertNull(store.settings.search.pendingDeepSeek)
    }
    @Test fun anOpenOfferCannotApproveAnUnvalidatedReplacementKey() = runTest {
        val store = AgentStore(PluginStorage(temp.root)); val keys = Secrets().apply { write(Provider.DEEPSEEK, "valid") }
        val controller = AgentSearchController(store, keys)
        val catalog = AgentModelCatalog(store, keys, {}, ModelsTransport { _, _ -> DiscoveredModels(listOf("model")) }, this, controller::validated)
        catalog.refresh(store.settings, true); advanceUntilIdle()
        val confirm = controller.handle("search-offer/", "") as PluginOutcome.Confirm
        keys.write(Provider.DEEPSEEK, "unverified")
        try { controller.handle(confirm.action.id, ""); fail() } catch (_: IllegalStateException) { }
        assertEquals(SearchSource.BING, store.settings.search.source)
        assertNull(controller.page("search-api/BING")); assertNull(controller.page("search-api/unknown"))
    }
    @Test fun configuredSearchDoesNotUseAChatKeyAndChangesApplyToTheNextCall() = runTest {
        val store = AgentStore(PluginStorage(temp.root)); val keys = Secrets().apply { write(Provider.DEEPSEEK, "chat-key") }
        var bing = 0; var searches = 0
        val web = AgentWebAccess({ store.settings }, keys, DeepSeekWebApi { _, _, _ -> error("DS is not selected") },
            searchFallback = { bing++; ToolResult("[]") }, searchApi = SearchApiTransport { source, endpoint, key, query, count ->
                searches++; assertEquals(SearchSource.TAVILY, source); assertEquals("search-key", key)
                assertEquals(source.endpoint, endpoint); assertEquals("资料", query); assertEquals(5, count)
                buildJsonObject { put("results", JsonArray(emptyList())) }
            })
        web.search("资料"); assertEquals(1, bing)
        val controller = AgentSearchController(store, keys)
        controller.handle("search-save-key/TAVILY", "search-key"); controller.handle("search-source/TAVILY", ""); controller.handle("search-count", "5")
        web.search("资料"); assertEquals(1, searches); assertEquals("chat-key", keys.read(Provider.DEEPSEEK))
        assertFalse(PluginJson.encodeToString(store.settings).contains("search-key"))
        controller.handle("search-source/BING", ""); web.search("资料"); assertEquals(2, bing)
    }
    @Test fun fetchAndFallbackRespectUserChoicesAndCancellationAlwaysPropagates() = runTest {
        var settings = AgentSettings(search = AgentSearchSettings(source = SearchSource.TAVILY, fallback = false))
        val keys = Secrets().apply { writeCustom(SearchSource.TAVILY.secretId(), "key") }
        var direct = 0
        val web = AgentWebAccess({ settings }, keys, fetchFallback = { direct++; ToolResult("{}") }, searchFallback = { error("Fallback disabled") },
            searchApi = SearchApiTransport { _, _, _, _, _ -> throw IllegalStateException("secret must not leak") })
        web.fetch("https://example.com"); assertEquals(1, direct)
        try { web.search("资料"); fail() } catch (e: IllegalStateException) { assertFalse(e.message!!.contains("secret")) }
        settings = settings.copy(search = settings.search.copy(fallback = true))
        val cancelled = AgentWebAccess({ settings }, keys, searchFallback = { error("Never fallback on cancel") }, searchApi = SearchApiTransport { _, _, _, _, _ -> throw CancellationException() })
        try { cancelled.search("资料"); fail() } catch (_: CancellationException) { }
    }
    @Test fun providerRequestsUseTheDocumentedPayloadsAndResponsesAreBounded() {
        for (source in listOf(SearchSource.BOCHA, SearchSource.TAVILY, SearchSource.BRAVE, SearchSource.SERPER)) {
            val request = searchApiRequest(source, source.endpoint, "secret", "中文 &", 5)
            assertFalse(request.url.contains("secret"))
            if (source == SearchSource.BRAVE) { assertNull(request.body); assertEquals("secret", request.headers["X-Subscription-Token"]); assertTrue(request.url.contains("count=5")) }
            else assertNotNull(request.body)
        }
        val entry = buildJsonObject { put("title", "t"); put("url", "https://example.com"); put("content", "x".repeat(3000)) }
        val result = normalizeSearchApi(SearchSource.TAVILY, buildJsonObject { put("results", JsonArray(List(20) { entry })) }, 5)
        assertEquals(5, result["results"]!!.jsonArray.size); assertEquals(1600, result["results"]!!.jsonArray[0].jsonObject["summary"]!!.jsonPrimitive.content.length)
        val bocha = buildJsonObject { put("code", 200); put("data", buildJsonObject { put("webPages", buildJsonObject { put("value", buildJsonArray { add(buildJsonObject { put("name", "资料"); put("url", "https://example.com"); put("snippet", "摘要") }) }) }) }) }
        assertEquals("资料", normalizeSearchApi(SearchSource.BOCHA, bocha, 5)["results"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        listOf("http://example.com", "https://user:key@example.com", "https://example.com?q=x").forEach {
            try { searchApiEndpoint(it); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun braveAndSerperNormalizeTheirOwnResponseEnvelopesAndDiscardUnsafeLinks() {
        val brave = PluginJson.parseToJsonElement("""{"web":{"results":[{"title":"Brave","url":"https://example.com","description":"摘要"},{"url":"javascript:alert(1)"}]}}""").jsonObject
        val serper = PluginJson.parseToJsonElement("""{"organic":[{"title":"Serper","link":"https://example.com","snippet":"摘要"},{"link":"https://user:password@example.com"}]}""").jsonObject
        for ((source, root) in listOf(SearchSource.BRAVE to brave, SearchSource.SERPER to serper)) {
            val result = normalizeSearchApi(source, root, 8)["results"]!!.jsonArray
            assertEquals(1, result.size); assertEquals("摘要", result.single().jsonObject["summary"]!!.jsonPrimitive.content)
        }
    }
}
