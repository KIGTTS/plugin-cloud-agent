@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class AgentModelsDownloadsTest {
    @get:Rule val temp = TemporaryFolder()
    private class Secrets : AgentSecrets {
        val values = mutableMapOf<String, String>()
        override fun read(provider: Provider) = values[provider.name].orEmpty()
        override fun write(provider: Provider, value: String) { values[provider.name] = value }
        override fun exists(provider: Provider) = read(provider).isNotBlank()
        override fun readCustom(id: String) = values[id].orEmpty()
        override fun writeCustom(id: String, value: String) { values[id] = value }
        override fun existsCustom(id: String) = readCustom(id).isNotBlank()
    }
    @Test fun metadataDeterminesEffortSliderAndWireParameterRatherThanGuessingFromModelName() {
        val json = """{"data":[{"id":"vision-model","input_modalities":["text","image"],"effort":{"supported_levels":["low","high","max"]}},{"id":"ordinary"}]}"""
        val info = parseModelInfo(json); assertEquals(listOf("ordinary", "vision-model"), parseModelCatalog(json)); assertEquals(true, info["vision-model"]!!.vision)
        val profile = ProviderProfile(endpoint = "https://example.com/v1", model = "vision-model", models = parseModelCatalog(json), modelInfo = info, effort = "high")
        val settings = AgentSettings(profiles = AgentSettings().profiles + (Provider.DEEPSEEK to profile))
        assertEquals(listOf("auto", "low", "high", "max"), reasoningControl(settings).single().options.map { it.value })
        assertEquals("high", chatRequest(settings, Conversation(), JsonArray(emptyList()))["reasoning_effort"]!!.jsonPrimitive.content)
        val unknown = settings.copy(profiles = settings.profiles + (Provider.DEEPSEEK to profile.copy(model = "ordinary")))
        assertTrue(reasoningControl(unknown).isEmpty()); assertFalse(chatRequest(unknown, Conversation(), JsonArray(emptyList())).containsKey("reasoning_effort"))
        assertEquals(listOf("manual"), parseManualModels("""[{"id":"manual","reasoningEfforts":["low","high"]}]"""))
        assertEquals(listOf("low", "high"), parseModelInfo("""[{"id":"manual","reasoningEfforts":["low","high"]}]""")["manual"]!!.reasoningEfforts)
    }
    @Test fun automaticCatalogCacheIsPerCustomAccountAndFailurePreservesManualModels() = runTest {
        val store = AgentStore(PluginStorage(temp.root)); val secrets = Secrets()
        val first = CustomProvider(name = "A", profile = ProviderProfile("https://a.example/v1", "manual-a", listOf("manual-a")))
        val second = CustomProvider(name = "B", profile = ProviderProfile("https://b.example/v1", "manual-b", listOf("manual-b")))
        store.updateSettings { it.copy(provider = Provider.CUSTOM, customProviderId = first.id, customProviders = listOf(first, second)) }
        secrets.writeCustom(first.id, "key-a"); secrets.writeCustom(second.id, "key-b")
        val calls = mutableListOf<Pair<String, String>>()
        val catalog = AgentModelCatalog(store, secrets, {}, ModelsTransport { endpoint, key -> calls += endpoint to key
            if (key == "key-b") error("服务拒绝获取") else DiscoveredModels(listOf("fetched-a"), mapOf("fetched-a" to ModelInfo(listOf("low", "high"), true))) }, this)
        catalog.refresh(store.settings.configuredConnection()); advanceUntilIdle()
        assertEquals("https://a.example/v1/models", calls.single().first); assertEquals(listOf("fetched-a"), store.settings.customProviders.first().profile.models)
        catalog.refresh(store.settings.configuredConnection()); advanceUntilIdle(); assertEquals(1, calls.size)
        catalog.refresh(store.settings.copy(customProviderId = second.id).configuredConnection()); advanceUntilIdle()
        assertEquals("key-b", calls.last().second); assertEquals(listOf("manual-b"), store.settings.customProviders.last().profile.models)
        assertEquals("key-a", secrets.read(store.settings)); assertEquals("key-b", secrets.read(store.settings.copy(customProviderId = second.id)))
        val reloaded = AgentStore(store.storageForTest())
        assertEquals(2, reloaded.settings.customProviders.size)
        store.updateSettings { it.copy(customProviders = listOf(second)) }
        assertEquals("", store.settings.profile().endpoint)
    }
    private fun AgentStore.storageForTest() = PluginStorage(temp.root)
    @Test fun downloadReturnsActualResourceHashAndDoesNotOverwriteOrSaveBadChecksum() = runBlocking {
        val commands = mutableListOf<AppCommand>(); val bytes = byteArrayOf(1, 2, 3, 4)
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root); override val assets = temp.root
            override val appBroker = object : AppBroker { override suspend fun request(command: AppCommand): AppReply { commands += command; return AppReply.Data(buildJsonObject { put("allowed", true) }) } }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val tool = AgentDownload(env, { url -> object : HttpsURLConnection(url) {
            override fun getCipherSuite() = "test"; override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
            override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
            override fun connect() {}; override fun disconnect() {}; override fun usingProxy() = false
            override fun getResponseCode() = 200; override fun getContentLengthLong() = bytes.size.toLong()
            override fun getContentType() = "image/png"; override fun getInputStream() = bytes.inputStream()
        } }, { URI(it) })
        val reply = tool.download("https://example.com/image", "workspace/assets/picture.png")
        assertArrayEquals(bytes, env.storage.file("workspace/assets/picture.png").readBytes()); assertTrue(reply.content.contains("sha256")); assertEquals(AppCommand.Permission("network"), commands.single())
        assertTrue(runCatching { tool.download("https://example.com/image", "workspace/assets/picture.png") }.isFailure)
        assertTrue(runCatching { tool.download("https://example.com/image", "workspace/assets/bad.png", "0".repeat(64)) }.isFailure)
        assertFalse(env.storage.file("workspace/assets/bad.png").exists()); assertTrue(env.storage.temporary.listFiles().orEmpty().none { it.extension == "part" })
        assertTrue(runCatching { publicResourceUri("https://127.0.0.1/image") }.isFailure)
    }
}
