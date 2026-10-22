package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentImagesHistoryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun imageInputUsesContentPartsAndOnlyFourLatestImagesAreUploaded() {
        val messages = (0..5).map { AgentMessage(role = "user", content = "图$it", images = listOf(AgentImage("imports/chat/$it.jpg"))) }
        val request = chatRequest(AgentSettings(), Conversation(messages = messages), JsonArray(emptyList())) { "data:image/jpeg;base64," + it.path.substringAfterLast('/') }
        val wire = request.getValue("messages").jsonArray.drop(1)
        assertTrue(wire[0].jsonObject.getValue("content") is JsonPrimitive)
        val parts = wire.last().jsonObject.getValue("content").jsonArray
        assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(parts[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertEquals(4, wire.count { it.jsonObject.getValue("content") is JsonArray })
    }
    @Test fun historyDefaultsToOneHundredAndReturnsEarlierFiftyWithStablePositions() {
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root); override val assets = temporary.root
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val store = AgentStore(env.storage); val conversation = store.create()
        (0 until 240).forEach { store.message(conversation.id, AgentMessage(id = "m$it", role = "user", content = "第$it 条"), false) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transport = object : ChatTransport { override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler = error("unused") }
        val secrets = object : AgentSecrets { override fun read(provider: Provider) = ""; override fun exists(provider: Provider) = false; override fun write(provider: Provider, value: String) {} }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, scope)
        val initial = agentChat(store, runner); assertEquals(100, initial.messages.size); assertEquals(140, initial.messages.first().position)
        store.loadEarlier(conversation.id, 140)
        val page = agentChat(store, runner); assertEquals((90..139).toList(), page.messages.filter { it.position < 140 }.map { it.position })
        assertEquals("load-earlier", page.historyAction); assertEquals(240, page.historyTotal)
        // Long recent answers previously consumed the snapshot budget and hid every older message.
        (230 until 240).forEach { store.message(conversation.id, AgentMessage(id = "m$it", role = "assistant", content = "长".repeat(16000)), false) }
        store.loadEarlier(conversation.id, 90)
        val longPage = agentChat(store, runner)
        assertTrue(longPage.messages.isNotEmpty())
        assertTrue(longPage.messages.first().position < 90)
        assertTrue(longPage.messages.any { it.position == 89 })
        store.latestHistory(conversation.id)
        val latest = agentChat(store, runner)
        assertEquals(239, latest.messages.last().position)
        scope.cancel()
    }
}
