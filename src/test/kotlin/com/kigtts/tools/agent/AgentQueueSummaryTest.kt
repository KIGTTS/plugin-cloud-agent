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

class AgentQueueSummaryTest {
    @Test fun reasoningAndPartialToolArgumentsDoNotRequireAFinishedStreamAndRetireThinkingState() = runTest {
        val env = environment(); val store = AgentStore(env.storage); val chat = store.create(); var requests = 0
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                requests++
                val stream = StreamAssembler()
                if (requests == 1) {
                    stream.accept("""{"choices":[{"delta":{"reasoning_content":"先查询目录"}}]}"""); delta(stream)
                    assertFalse(store.get(chat.id)!!.messages.last().reasoningDone)
                    stream.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"list_files","arguments":"{"}}]}}]}"""); delta(stream)
                    // No call/result parsing is allowed while its JSON is still incomplete.
                    stream.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"path\":\"workspace\"}"}}]},"finish_reason":"tool_calls"}]}"""); delta(stream)
                } else stream.accept("""{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}""")
                stream.accept("[DONE]"); return stream
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(chat.id, "检查目录"); advanceUntilIdle()
        val message = store.get(chat.id)!!.messages.first { it.role == "assistant" }
        assertEquals(MessageState.COMPLETE, message.state); assertTrue(message.reasoningDone)
        assertFalse(agentChat(store, runner).messages.first { it.id == message.id }.thinking)
    }
    @get:Rule val temporary = TemporaryFolder()
    private val secrets = object : AgentSecrets {
        override fun read(provider: Provider) = "test-key"; override fun exists(provider: Provider) = true
        override fun write(provider: Provider, value: String) {}
    }
    private fun environment() = object : PluginEnvironment {
        override val storage = PluginStorage(temporary.root); override val assets = temporary.root
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
    }
    @Test fun interruptRunsFirstThenQueuedMessagesInOrderAndPreservesCancellation() = runTest {
        val env = environment(); val store = AgentStore(env.storage); val chat = store.create(); var count = 0
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                if (count++ == 0) { delta(StreamAssembler().apply { accept("""{"choices":[{"delta":{"content":"未完成"}}]}""") }); awaitCancellation() }
                return StreamAssembler().apply { accept("""{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}"""); accept("[DONE]") }
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(chat.id, "first"); runCurrent()
        runner.enqueue(chat.id, "next1"); runner.enqueue(chat.id, "next2"); runner.enqueue(chat.id, "urgent", true)
        advanceUntilIdle()
        val messages = store.get(chat.id)!!.messages
        assertEquals(listOf("first", "urgent", "next1", "next2"), messages.filter { it.role == "user" }.map { it.content })
        assertEquals(MessageState.CANCELLED, messages[1].state); assertTrue(store.get(chat.id)!!.queued.isEmpty()); assertFalse(runner.running)
    }
    @Test fun summaryIsAnIndependentModelResultAndIsStoredWithoutReplacingReasoning() = runTest {
        val env = environment(); val store = AgentStore(env.storage); val chat = store.create(); val requests = mutableListOf<JsonObject>()
        val original = "先检查项目状态。中间比较两套布局的利弊，最后决定保留原生控件。"
        val summary = "围绕可用性比较布局，选择复用原生控件以降低交互成本。"
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                requests += request
                return StreamAssembler().apply { accept(buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                    put("delta", buildJsonObject { put("content", if (requests.size == 1) "答复" else summary); if (requests.size == 1) put("reasoning_content", original) }); put("finish_reason", "stop")
                }) }) }.toString()); accept("[DONE]") }
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(chat.id, "分析布局"); advanceUntilIdle()
        val message = store.get(chat.id)!!.messages.last()
        assertEquals(original, message.reasoning); assertEquals(summary, message.summary); assertEquals("答复", message.content)
        assertFalse(requests[1].containsKey("tools")); assertEquals(original, requests[1].getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(summary, AgentStore(env.storage).get(chat.id)!!.messages.last().summary)
    }
    @Test fun queueSurvivesReloadAndCanBeReorderedOrRemovedWithoutSending() {
        val env = environment(); val store = AgentStore(env.storage); val chat = store.create()
        store.save(chat.copy(queued = listOf(AgentQueuedMessage(id = "a", text = "A"), AgentQueuedMessage(id = "b", text = "B"))))
        val restored = AgentStore(env.storage); assertEquals(2, restored.get(chat.id)!!.queued.size)
        restored.setPending(PendingReview(chat.id, "call", "review", "等待审批"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transport = object : ChatTransport { override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler = error("must not send") }
        val runner = AgentRunner(restored, secrets, transport, AgentTools(env), {}, scope)
        runner.manageQueue(chat.id, "b", true); assertEquals(listOf("b", "a"), restored.get(chat.id)!!.queued.map { it.id })
        runner.manageQueue(chat.id, "a", false); assertEquals(listOf("b"), restored.get(chat.id)!!.queued.map { it.id }); scope.cancel()
    }
}
