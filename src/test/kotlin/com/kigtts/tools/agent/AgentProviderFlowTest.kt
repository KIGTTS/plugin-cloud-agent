package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentProviderFlowTest {
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
    private fun environment(app: AppBroker? = null) = object : PluginEnvironment {
        override val storage = PluginStorage(temp.root); override val assets = temp.root; override val appBroker = app
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
    }
    @Test fun emptySecretAndApiChoiceDefaultAndAllFormatsStayConsistentWithTheirActions() {
        val env = environment(); val secrets = Secrets(); val store = AgentStore(env.storage)
        val runner = AgentRunner(store, secrets, object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler = error("unused")
        }, AgentTools(env), {}, TestScope())
        val controller = AgentProviderController(store, secrets, runner, AgentModelCatalog(store, secrets, {}, ModelsTransport { _, _ -> DiscoveredModels(emptyList()) }, TestScope()))
        fun blocks() = agentSettingsPage("connection-settings", store, secrets)!!.blocks.filterIsInstance<PageBlock.Group>().flatMap { it.children }
        assertEquals("", blocks().filterIsInstance<PageBlock.Input>().single { it.secret }.value)
        val default = blocks().filterIsInstance<PageBlock.Choice>().single()
        assertTrue(default.options.any { it.id == default.selected })
        ApiFormat.entries.forEach { format ->
            controller.handle("api-format/${format.name}", PluginContext("", ""))
            val group = blocks().filterIsInstance<PageBlock.Choice>().single()
            assertEquals("api-format/${format.name}", group.selected); assertTrue(group.options.any { it.id == group.selected })
        }
        controller.handle("add-provider-save", PluginContext("", "", inputs = mapOf("value" to "OpenAI")))
        controller.handle("add-provider-save", PluginContext("", "", inputs = mapOf("value" to "OpenAI")))
        assertEquals(listOf("OpenAI", "OpenAI (2)"), store.settings.customProviders.map { it.name })
        assertTrue(runCatching { controller.handle("save-provider-name", PluginContext("", "", inputs = mapOf("value" to "openai"))) }.isFailure)
    }
    @Test fun guideReturnsActualCurrentThemeAndBrowserNewActionsRemainScopedForReview() = runTest {
        val theme = buildJsonObject { put("dark", true); put("colors", buildJsonObject { put("primary", "#abcd12") }) }
        val env = environment(object : AppBroker { override suspend fun request(command: AppCommand): AppReply {
            assertEquals(AppCommand.State, command); return AppReply.Data(buildJsonObject { put("webTheme", theme) })
        } })
        val tools = AgentTools(env)
        val guide = tools.execute(ToolCall("guide", "web_ui_guide", "{}"))
        assertEquals(theme, PluginJson.parseToJsonElement(guide.content).jsonObject["theme"])
        browserInputOperations.forEach { operation ->
            val call = ToolCall("b", "browser", """{"operation":"$operation"}""")
            assertTrue(tools.requiresApproval(call)); assertFalse(tools.readOnly(call))
        }
        listOf("analyze_audio", "analyze_video", "analyze_asset", "download_media", "fit_data").forEach { name ->
            assertTrue(tools.schemas.any { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content == name })
            assertTrue(tools.readOnly(ToolCall("t", name, "{}")))
        }
    }
    @Test fun choosingAConversationModelDoesNotDependOnTheLastEditedProvider() {
        val env = environment(); val store = AgentStore(env.storage); val secrets = Secrets()
        val a = CustomProvider(name = "A", profile = ProviderProfile("https://a.example/v1", "a", listOf("a")))
        val b = CustomProvider(name = "B", profile = ProviderProfile("https://b.example/v1", "b", listOf("b")))
        store.updateSettings { it.copy(provider = Provider.CUSTOM, customProviderId = a.id, customProviders = listOf(a, b)) }
        secrets.writeCustom(a.id, "key-a"); secrets.writeCustom(b.id, "key-b")
        val conversation = store.create(); store.modelPickerConversation = conversation.id
        store.updateSettings { it.copy(customProviderId = b.id) }
        val runner = AgentRunner(store, secrets, object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler = error("unused")
        }, AgentTools(env), {}, TestScope())
        val controller = AgentProviderController(store, secrets, runner, AgentModelCatalog(store, secrets, {}, ModelsTransport { _, _ -> DiscoveredModels(emptyList()) }, TestScope()))
        val connection = store.settings.copy(customProviderId = a.id).configuredConnection()
        val outcome = controller.handle(modelAction(connection, "a", true), PluginContext("", ""))
        assertEquals(PluginOutcome.OpenPage("chat"), outcome)
        assertEquals(a.id, store.get(conversation.id)!!.customProviderId); assertEquals("a", store.get(conversation.id)!!.model)
        assertEquals(b.id, store.settings.customProviderId)
    }
}
