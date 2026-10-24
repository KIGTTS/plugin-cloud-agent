package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentPagesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun hugeChatAndReasoningStayWithinSnapshotAndMaskApiKey() {
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root); override val assets = temporary.root
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val secrets = object : AgentSecrets {
            override fun read(provider: Provider) = error("UI must not decrypt keys")
            override fun exists(provider: Provider) = true
            override fun write(provider: Provider, value: String) {}
        }
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler = error("unused")
        }
        val store = AgentStore(env.storage); val conversation = store.create()
        repeat(50) { store.message(conversation.id, AgentMessage(role = "assistant", content = "文".repeat(70_000), reasoning = "思".repeat(70_000)), persist = false) }
        store.updateSettings { it.copy(showReasoning = true) }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, TestScope())
        val pages = listOfNotNull(agentPage("chat", store, runner, secrets), agentPage("settings", store, runner, secrets), agentPage("connection-settings", store, runner, secrets), agentPage("conversations", store, runner, secrets))
        val encoded = PluginJson.encodeToString(PluginSnapshot(PluginDescriptor("tools.cloud-agent", "助手", "测试"), PluginContributions(), pages))
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size < 512 * 1024)
        assertTrue(encoded.contains("较长内容仅显示结尾"))
        assertTrue(encoded.contains("已设置"))
        val chatActions = agentPage("chat", store, runner, secrets)!!.blocks.filterIsInstance<PageBlock.Actions>().flatMap { it.actions }
        assertTrue(chatActions.any { it.id == "conversations" && it.label == "历史会话" })
        assertFalse(chatActions.any { it.id.startsWith("export-conversation") })
        val old = store.create(); store.select(conversation.id)
        val rows = agentPage("conversations", store, runner, secrets)!!.management!!.items
        assertTrue(rows.single { it.id == old.id }.actions.any { it.id == "export-conversation/${old.id}" })
        val settingsJson = PluginJson.encodeToString(agentPage("settings", store, runner, secrets))
        assertFalse(settingsJson.contains("import-cards")); assertFalse(settingsJson.contains("export-cards"))
        val question = AgentMessage(id = "question-123", role = "assistant", content = "选择一种样式",
            options = listOf(ChatOption("original", "简洁", "simple"), ChatOption("original", "明亮", "bright")))
        store.message(conversation.id, question, persist = false)
        val options = agentChat(store, runner).messages.single { it.id == question.id }.options
        assertEquals(listOf("option/question-123/0", "option/question-123/1"), options.map { it.action })
        assertEquals(listOf("simple", "bright"), options.map { it.value })
    }
}
