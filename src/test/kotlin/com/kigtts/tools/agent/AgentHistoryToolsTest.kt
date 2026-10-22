package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentHistoryToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun historyQueriesExcludeTheirOwnRecursiveRepliesAndCanFindOriginalCalls() {
        val history = Conversation(messages = listOf(
            AgentMessage(role = "user", content = "Make game"),
            AgentMessage(role = "assistant", calls = listOf(ToolCall("source", "write_file", "source-for-game"))),
            AgentMessage(role = "assistant", calls = listOf(ToolCall("query", "conversation_history", "{}"))),
            AgentMessage(role = "tool", toolCallId = "query", content = "recursive-echo".repeat(10000))))
        val tool = AgentHistoryTools(PluginStorage(temporary.root)) { history }
        val all = PluginJson.parseToJsonElement(tool.query(buildJsonObject {}).content).jsonObject
        assertEquals(2, all.getValue("total").jsonPrimitive.int)
        assertFalse(all.toString().contains("recursive-echo"))
        val exact = PluginJson.parseToJsonElement(tool.query(buildJsonObject { put("query", "source") }).content).jsonObject
        assertEquals(1, exact.getValue("total").jsonPrimitive.int)
        assertEquals(1, exact.getValue("messages").jsonArray.single().jsonObject.getValue("position").jsonPrimitive.int)
    }
    @Test fun recoveryCopiesCompleteOriginalSourceOnlyFromTheBoundConversation() {
        val source = "<main>original</main>".repeat(2000)
        val call = ToolCall("source", "write_file", buildJsonObject { put("path", "missing-prefix/game.html"); put("text", source) }.toString())
        val history = Conversation(messages = listOf(AgentMessage(role = "assistant", calls = listOf(call))))
        val tool = AgentHistoryTools(PluginStorage(temporary.root)) { history }
        tool.recover(buildJsonObject { put("callId", "source"); put("path", "workspace/game/index.html") })
        assertEquals(source, temporary.root.resolve("workspace/game/index.html").readText())
        assertThrows(AgentToolArgumentError::class.java) {
            tool.recover(buildJsonObject { put("callId", "another-conversations-call"); put("path", "workspace/other.html") })
        }
        assertFalse(temporary.root.resolve("workspace/other.html").exists())
    }
    @Test fun contextRetainsRecentEvidenceButDoesNotResendOlderHugeHistoryEchoes() {
        val messages = (1..4).flatMap { index -> listOf(
            AgentMessage(role = "assistant", calls = listOf(ToolCall("q$index", "conversation_history", "{}"))),
            AgentMessage(role = "tool", toolCallId = "q$index", content = "history-$index".repeat(10000))) }
        val compact = historyContext(messages)
        assertEquals(messages.map { it.id }, compact.map { it.id })
        assertFalse(compact[1].content.contains("history-1"))
        assertTrue(compact.last().content.contains("history-4"))
        assertTrue(compact.last().content.length < 24200)
        assertEquals(90000, messages.last().content.length)
    }
}
