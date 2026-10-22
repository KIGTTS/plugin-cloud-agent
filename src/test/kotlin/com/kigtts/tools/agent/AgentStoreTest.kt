package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun storage() = PluginStorage(temporary.root)
    @Test fun conversationsAndSelectedProfilePersistIndependently() {
        val store = AgentStore(storage()); val first = store.create(); val second = store.create()
        store.message(first.id, AgentMessage(role = "user", content = "first"))
        store.message(second.id, AgentMessage(role = "user", content = "second"))
        store.select(first.id); store.updateSettings { it.copy(provider = Provider.GLM) }
        val restored = AgentStore(storage())
        assertEquals(first.id, restored.selected()?.id)
        assertEquals("first", restored.get(first.id)?.messages?.single()?.content)
        assertEquals("second", restored.get(second.id)?.messages?.single()?.content)
        assertEquals(Provider.GLM, restored.settings.provider)
    }
    @Test fun interruptedStreamRestoresAsCancelledAndPreservesText() {
        val store = AgentStore(storage()); val conversation = store.create()
        store.message(conversation.id, AgentMessage(role = "assistant", content = "已收到", state = MessageState.STREAMING))
        val restored = AgentStore(storage()).get(conversation.id)!!.messages.single()
        assertEquals(MessageState.CANCELLED, restored.state); assertEquals("已收到", restored.content)
    }
    @Test fun replacingReviewedToolResultDoesNotDuplicateWireCall() {
        val store = AgentStore(storage()); val conversation = store.create()
        store.message(conversation.id, AgentMessage(role = "tool", content = "pending", toolCallId = "same"))
        store.message(conversation.id, AgentMessage(role = "tool", content = "applied", toolCallId = "same"))
        assertEquals(1, store.get(conversation.id)!!.messages.size)
        assertEquals("applied", store.get(conversation.id)!!.messages.single().content)
    }
    @Test fun pendingReviewSurvivesProcessRestartAndDeleteClearsIt() {
        val store = AgentStore(storage()); val conversation = store.create()
        store.setPending(PendingReview(conversation.id, "call", "token", "summary"))
        val restored = AgentStore(storage()); assertEquals("token", restored.pending?.token)
        restored.delete(conversation.id)
        assertNull(AgentStore(storage()).pending)
    }
    @Test fun legacyReviewDefaultsToQuickTextWithoutMisroutingItsToken() {
        val restored = PluginJson.decodeFromString<PendingReview>("""{"conversationId":"chat","toolCallId":"call","token":"old-token","summary":"审核"}""")
        assertEquals(ReviewKind.QUICK_TEXT, restored.kind)
    }
    @Test fun reasoningIsRoundTrippedEvenWithUiDisplayDisabled() {
        val conversation = Conversation(messages = listOf(
            AgentMessage(role = "assistant", reasoning = "provider data", calls = listOf(ToolCall("id", "app", "{}"))),
            AgentMessage(role = "tool", content = "ok", toolCallId = "id")))
        val request = chatRequest(AgentSettings(showReasoning = false), conversation, JsonArray(emptyList()))
        val messages = request.getValue("messages").jsonArray
        assertEquals("provider data", messages[1].jsonObject.getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("id", messages[2].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
        assertFalse(request.containsKey("thinking"))
    }
    @Test fun nonHttpsCredentialsAndQueryEndpointsAreRejected() {
        listOf("http://example.com", "https://key@example.com", "https://example.com?key=secret", "https://example.com/#x").forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) { completionEndpoint(endpoint) }
        }
        assertEquals("https://api.example.com/v1/chat/completions", completionEndpoint("https://api.example.com/v1/"))
        assertEquals("https://api.example.com/chat/completions", completionEndpoint("https://api.example.com/chat/completions"))
    }
}
