package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class AgentCompactionTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun compactsBalancedOldCallsPersistsFactsAndPreservesFullHistoryAndLatestInstruction() = runTest {
        val store = AgentStore(PluginStorage(temp.root)); val chat = store.create()
        val messages = buildList {
            add(AgentMessage(id = "goal", role = "user", content = "创建页面，保留全部用户照片"))
            repeat(35) { i ->
                add(AgentMessage(role = "assistant", calls = listOf(ToolCall("call$i", "read_file", "{}"))))
                add(AgentMessage(role = "tool", toolCallId = "call$i", content = "已读取日志 $i ".repeat(700)))
            }
            add(AgentMessage(id = "latest", role = "user", content = "请保留蓝色风格"))
        }
        store.save(chat.copy(messages = messages))
        var requests = 0
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                requests++; assertFalse(request.containsKey("tools"))
                return StreamAssembler().apply { accept("""{"choices":[{"delta":{"content":"目标：创建页面，保留照片和蓝色风格。已读取文件；没有删除或安装操作。"},"finish_reason":"stop"}]}"""); accept("[DONE]") }
            }
        }
        val compactor = AgentCompactor(store, transport, "https://example.com", "test", store.settings, JsonArray(emptyList()))
        assertTrue(compactor.compact(chat.id))
        val saved = AgentStore(PluginStorage(temp.root)).get(chat.id)!!
        assertEquals(messages, saved.messages); assertTrue(saved.contextSummary.contains("保留照片"))
        val current = conversationContext(saved); assertEquals("goal", current.first().id); assertEquals("latest", current.last().id)
        val calls = current.flatMap { it.calls }.map { it.id }.toSet()
        assertTrue(current.filter { it.role == "tool" }.all { it.toolCallId in calls })
        val request = chatRequest(store.settings, saved, JsonArray(emptyList()))
        assertTrue(request.toString().contains("事实交接摘要")); assertTrue(request.toString().contains("请保留蓝色风格"))
        assertEquals(1, requests)
    }
    @Test fun failedSummaryLeavesCursorAndAllMessagesIntactAndDoesNotLoop() = runTest {
        val store = AgentStore(PluginStorage(temp.root)); val chat = store.create()
        store.save(chat.copy(messages = (0..25).map { AgentMessage(role = "user", content = "保留原记录".repeat(500)) }))
        var requests = 0
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler { requests++; error("offline") }
        }
        val before = store.get(chat.id)
        val compactor = AgentCompactor(store, transport, "https://example.com", "test", store.settings, JsonArray(emptyList()))
        assertFalse(compactor.compact(chat.id)); assertFalse(compactor.compact(chat.id))
        assertEquals(before, store.get(chat.id)); assertEquals(1, requests)
    }
    @Test fun assistedModeRequiresAnnotatedApprovalForRiskyOperationsAndUnknownRequests() {
        assertNull(assistedRisk(ToolCall("1", "create_page", "{}")))
        assertNotNull(assistedRisk(ToolCall("2", "deliver_lua", """{"path":"drafts/game"}""")))
        assertNotNull(assistedRisk(ToolCall("3", "quick_text", """{"command":{"type":"commit"}}""")))
        assertNotNull(assistedRisk(ToolCall("4", "unrecognized", "{}")))
        assertTrue(isContextOverflow("context_length_exceeded")); assertFalse(isContextOverflow("invalid model"))
    }
}
