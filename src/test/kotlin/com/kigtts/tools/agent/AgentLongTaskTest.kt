@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentLongTaskTest {
    @get:Rule val temp = TemporaryFolder()
    private val secrets = object : AgentSecrets {
        override fun read(provider: Provider) = "test-key"
        override fun exists(provider: Provider) = true
        override fun write(provider: Provider, value: String) {}
    }
    private fun environment() = object : PluginEnvironment {
        override val storage = PluginStorage(temp.root); override val assets = temp.root
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
    }
    @Test fun taskContinuesPastEightRoundsAndHistoryRemainsQueryable() = runTest {
        val env = environment(); val store = AgentStore(env.storage); val conversation = store.create(); var rounds = 0
        val tools = AgentTools(env, historyReader = { store.get(conversation.id) })
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                rounds++
                val update = if (rounds <= 18) """{"tool_calls":[{"index":0,"id":"call-$rounds","type":"function","function":{"name":"list_files","arguments":"{\"path\":\"workspace\"}"}}]}""" else """{"content":"任务完成"}"""
                return StreamAssembler().apply { accept("""{"choices":[{"delta":$update,"finish_reason":"${if (rounds <= 18) "tool_calls" else "stop"}"}]}"""); accept("[DONE]") }
            }
        }
        val runner = AgentRunner(store, secrets, transport, tools, {}, this)
        runner.send(conversation.id, "持续执行完整任务"); advanceUntilIdle()
        assertEquals(19, rounds); assertEquals("任务完成", store.get(conversation.id)!!.messages.last().content); assertFalse(runner.running)
        val history = tools.execute(ToolCall("history", "conversation_history", """{"offset":1,"limit":2}"""))
        assertEquals(3, PluginJson.parseToJsonElement(history.content).jsonObject["nextOffset"]!!.jsonPrimitive.int)
    }
    @Test fun moreThanAThousandMessagesUseStableChunksAndOldJsonMigratesWithoutDataLoss() {
        val env = environment(); val id = "legacy-chat"
        val original = Conversation(id = id, messages = (0..1205).map { AgentMessage(id = "m$it", role = "user", content = "历史 $it") })
        env.storage.file("conversations/$id.json").apply { parentFile!!.mkdirs(); writeText(PluginJson.encodeToString(original)) }
        val store = AgentStore(env.storage); assertEquals(original, store.get(id)); store.save(original)
        val directory = env.storage.directory("conversations/$id"); val oldChunks = directory.listFiles()!!.associate { it.name to it.lastModified() }
        store.message(id, AgentMessage(role = "assistant", content = "新结果"))
        val after = AgentStore(env.storage).get(id)!!
        assertEquals(1207, after.messages.size); assertEquals(original.messages, after.messages.dropLast(1))
        assertEquals(12, oldChunks.count { (name, modified) -> File(directory, name).lastModified() == modified })
        val unrelated = store.create(); store.delete(id); assertNotNull(AgentStore(env.storage).get(unrelated.id)); assertFalse(directory.exists())
    }
    @Test fun contextWindowKeepsOriginalAndLatestRequestsAndEverySelectedToolResultHasItsCall() {
        val messages = mutableListOf(AgentMessage(id = "goal", role = "user", content = "最初目标"))
        repeat(40) { index ->
            messages += AgentMessage(role = "assistant", calls = listOf(ToolCall("c$index", "read_file", "{}")))
            messages += AgentMessage(role = "tool", toolCallId = "c$index", content = "结果".repeat(1000))
        }
        messages += AgentMessage(id = "latest", role = "user", content = "保持最新修正")
        val window = contextWindow(messages, 12000)
        assertTrue(window.omitted); assertEquals("goal", window.messages.first().id); assertEquals("latest", window.messages.last().id)
        val calls = window.messages.flatMap { it.calls }.map { it.id }.toSet()
        assertTrue(window.messages.filter { it.role == "tool" }.all { it.toolCallId in calls })
    }
    @Test fun modelAndProviderOrderStayStableAndConnectionManagementCannotSelectAModel() {
        val env = environment(); val store = AgentStore(env.storage)
        store.changeProfile { it.copy(models = listOf("a", "b"), model = "a") }
        val a = store.create(); val before = modelOptions(store, store.connection(a)).map { it.action }
        store.modify(a.id) { it.copy(model = "b") }
        val after = modelOptions(store, store.connection(store.get(a.id)!!)).map { it.action }
        assertEquals(before, after)
        store.updateSettings { it.copy(provider = Provider.GLM) }
        assertEquals(before, modelOptions(store, store.connection(store.get(a.id)!!)).map { it.action })
        assertEquals(Provider.DEEPSEEK, store.connection(store.get(a.id)!!).provider)
        store.modelPickerConversation = null
        assertTrue(modelPickerPage(store).management!!.items.all { it.primaryAction == null && !it.selected })
        assertEquals("https://opencode.ai/inference/go/v1/models", modelsEndpoint(Provider.OPENCODE_GO.endpoint))
        assertTrue(openCodeChatModel(Provider.OPENCODE_GO, "kimi-k3")); assertFalse(openCodeChatModel(Provider.OPENCODE_GO, "gpt-6-luna"))
    }
}
