package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentWebAccessTest {
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
    private fun emptySearch() = buildJsonObject { put("results", buildJsonArray {}) }
    private fun data(result: ToolResult) = PluginJson.parseToJsonElement(result.content).jsonObject
    @Test fun identifiesTheOfficialOriginRatherThanProviderNameOrLookalikeDomains() {
        listOf("https://api.deepseek.com", "https://api.deepseek.com/v1", "https://API.DEEPSEEK.COM:443/anthropic/v1/messages").forEach { assertTrue(it, isOfficialDeepSeek(it)) }
        listOf("http://api.deepseek.com", "https://api.deepseek.com.evil.test", "https://evil.test/api.deepseek.com",
            "https://api.deepseek.com@evil.test", "https://api.deepseek.com:8443", "https://api.deepseek.com?token=x").forEach { assertFalse(it, isOfficialDeepSeek(it)) }
    }
    @Test fun addedOfficialConnectionsWorkEvenWhileChatUsesAnotherProviderAndConfigurationChangesApplyImmediately() = runTest {
        val official = CustomProvider(name = "任意名称", profile = ProviderProfile("https://api.deepseek.com/anthropic", ""))
        var settings = AgentSettings(provider = Provider.OPENAI, customProviders = listOf(official), search = AgentSearchSettings(source = SearchSource.DEEPSEEK))
        val secrets = Secrets().apply { writeCustom(official.id, "test-key-one") }
        val seen = mutableListOf<String>()
        val web = AgentWebAccess({ settings }, secrets, DeepSeekWebApi { operation, key, body ->
            seen += key; assertEquals("search", operation); assertEquals(8, body["count"]?.jsonPrimitive?.int)
            assertEquals(false, body["need_content"]?.jsonPrimitive?.boolean); emptySearch()
        }, searchFallback = { error("Official connection should be used") })
        assertEquals("DeepSeek", data(web.search("资料"))["provider"]?.jsonPrimitive?.content)
        secrets.writeCustom(official.id, "test-key-two"); web.search("资料")
        assertEquals(listOf("test-key-one", "test-key-two"), seen)
        settings = settings.copy(customProviders = listOf(official.copy(profile = official.profile.copy(endpoint = "https://proxy.example/v1"))))
        var fallback = false
        AgentWebAccess({ settings }, secrets, DeepSeekWebApi { _, _, _ -> error("Proxy is not DeepSeek") },
            searchFallback = { fallback = true; ToolResult("[]") }).search("资料")
        assertTrue(fallback)
    }
    @Test fun invalidKeysUseAnotherOfficialConnectionAndAreRetriedAfterTheKeyChanges() = runTest {
        val second = CustomProvider(profile = ProviderProfile("https://api.deepseek.com/v1", ""))
        val settings = AgentSettings(customProviders = listOf(second), search = AgentSearchSettings(source = SearchSource.DEEPSEEK))
        val secrets = Secrets().apply { write(Provider.DEEPSEEK, "test-bad"); writeCustom(second.id, "test-good") }
        val seen = mutableListOf<String>()
        val web = AgentWebAccess({ settings }, secrets, DeepSeekWebApi { _, key, _ ->
            seen += key
            if (key == "test-bad") throw DeepSeekWebFailure(401)
            emptySearch()
        })
        web.search("A"); web.search("B")
        assertEquals(listOf("test-bad", "test-good", "test-good"), seen)
        secrets.write(Provider.DEEPSEEK, "test-repaired"); web.search("C")
        assertEquals("test-repaired", seen.last())
    }
    @Test fun fallbackIsExplicitAndDoesNotLeakTheRejectedCredential() = runTest {
        var time = 0L
        var calls = 0
        val secrets = Secrets().apply { write(Provider.DEEPSEEK, "test-never-return-this") }
        val web = AgentWebAccess(settings = { AgentSettings(search = AgentSearchSettings(source = SearchSource.DEEPSEEK)) }, secrets = secrets, api = DeepSeekWebApi { _, _, _ -> calls++; throw DeepSeekWebFailure(401) },
            searchFallback = { ToolResult("[]") }, now = { time })
        repeat(2) {
            val result = web.search("A")
            assertEquals("Bing", data(result)["provider"]?.jsonPrimitive?.content)
            assertTrue(data(result).containsKey("notice")); assertFalse(result.content.contains("test-never-return-this"))
        }
        assertEquals(1, calls)
        time = 300_001; web.search("A"); assertEquals(2, calls)
    }
    @Test fun fetchKeepsMarkdownAndReportsTruncationAndCancellationDoesNotFallBack() = runTest {
        val secrets = Secrets().apply { write(Provider.DEEPSEEK, "test-key") }
        val web = AgentWebAccess(settings = { AgentSettings(search = AgentSearchSettings(source = SearchSource.DEEPSEEK)) }, secrets = secrets, api = DeepSeekWebApi { operation, _, body ->
            assertEquals("fetch", operation); assertEquals("https://example.com/article?q=1", body["url"]?.jsonPrimitive?.content)
            buildJsonObject { put("title", "资料"); put("content", "# 标题\n\n" + "文".repeat(25000)) }
        })
        val page = data(web.fetch("https://example.com/article?q=1"))
        assertEquals("markdown", page["format"]?.jsonPrimitive?.content)
        assertTrue(page["text"]!!.jsonPrimitive.content.startsWith("# 标题\n\n"))
        assertEquals(24_000, page["text"]!!.jsonPrimitive.content.length)
        assertTrue(page["truncated"]!!.jsonPrimitive.boolean)
        val cancelled = AgentWebAccess(settings = { AgentSettings(search = AgentSearchSettings(source = SearchSource.DEEPSEEK)) }, secrets = secrets, api = DeepSeekWebApi { _, _, _ -> throw CancellationException() },
            searchFallback = { error("Cancelled tools must stop") })
        try { cancelled.search("A"); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }
    @Test fun queryLimitCountsUnicodeCharactersAndRejectsCredentialedFetchUrls() = runTest {
        var calls = 0
        val web = AgentWebAccess(searchFallback = { calls++; ToolResult("[]") })
        web.search("😀".repeat(128)); assertEquals(1, calls)
        try { web.search("😀".repeat(129)); fail() } catch (_: IllegalArgumentException) { }
        try { web.fetch("https://user:password@example.com"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, calls)
    }
    @Test fun browserSearchAndFetchShareTheWebRouteAndOnlyNeedNetworkPermission() = runTest {
        val requests = mutableListOf<AppCommand>()
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root); override val assets = temp.root
            override val appBroker = object : AppBroker {
                override suspend fun request(command: AppCommand): AppReply {
                    requests += command; assertEquals(AppCommand.Permission("network"), command)
                    return AppReply.Data(buildJsonObject { put("allowed", true) })
                }
            }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val web = AgentWebAccess(searchFallback = { ToolResult("[]") }, fetchFallback = { ToolResult("{\"text\":\"正文\"}") })
        val browser = AgentBrowser(env, web)
        assertEquals("[]", browser.execute(buildJsonObject { put("operation", "search"); put("query", "资料") }).content)
        assertEquals("正文", data(browser.execute(buildJsonObject { put("operation", "fetch"); put("url", "https://example.com") }))["text"]?.jsonPrimitive?.content)
        assertEquals(2, requests.size)
        val fields = browserToolSchema["function"]!!.jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertTrue(fields.keys.containsAll(listOf("query", "url")))
    }
}
