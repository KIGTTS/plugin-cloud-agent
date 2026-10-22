package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val secrets = object : AgentSecrets { override fun read(provider: Provider) = "test-not-a-key"; override fun exists(provider: Provider) = true; override fun write(provider: Provider, value: String) {} }
    private fun environment(quick: QuickTextBroker? = null) = object : PluginEnvironment {
        override val storage = PluginStorage(temporary.root); override val assets = temporary.root
        override val quickTextBroker = quick
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("disabled")
    }
    @Test fun streamsFinalTextWithoutReloadingConversation() = runTest {
        val env = environment(); val store = AgentStore(env.storage); store.updateSettings { it.copy(access = AgentAccessMode.FULL) }; val conversation = store.create()
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                val result = StreamAssembler(); result.accept("""{"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}]}""")
                delta(result); result.accept("[DONE]"); return result
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(conversation.id, "hello"); advanceUntilIdle()
        assertEquals("你好", store.get(conversation.id)!!.messages.last().content)
        assertEquals(MessageState.COMPLETE, store.get(conversation.id)!!.messages.last().state)
        assertFalse(runner.running); assertNull(runner.runningConversation)
    }
    @Test fun immediateAnswerDoesNotLeaveUiPermanentlyRunning() {
        val env = environment(); val store = AgentStore(env.storage); store.updateSettings { it.copy(access = AgentAccessMode.FULL) }; val conversation = store.create()
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                val result = StreamAssembler(); result.accept("[DONE]"); return result
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, scope)
        runner.send(conversation.id, "hello"); assertFalse(runner.running)
        runBlocking { runner.close() }
    }
    @Test fun cancellationKeepsPartialTextAndDoesNotContinueRequests() = runTest {
        val env = environment(); val store = AgentStore(env.storage); store.updateSettings { it.copy(access = AgentAccessMode.FULL) }; val conversation = store.create(); var calls = 0; var cancelled = false
        val transport = object : ChatTransport {
            override fun cancel() { cancelled = true }
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                calls++; val result = StreamAssembler(); result.accept("""{"choices":[{"delta":{"content":"partial"}}]}"""); delta(result); awaitCancellation()
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(conversation.id, "hello"); runCurrent(); runner.stop()
        assertThrows(IllegalStateException::class.java) { runner.send(conversation.id, "too soon") }
        advanceUntilIdle()
        assertEquals(1, calls); assertTrue(cancelled)
        assertEquals("partial", store.get(conversation.id)!!.messages.last().content)
        assertEquals(MessageState.CANCELLED, store.get(conversation.id)!!.messages.last().state)
    }
    @Test fun fullAccessReturnsHostConfirmationToModelWithoutReplayingOrWaiting() = runTest {
        var commits = 0
        val quick = object : QuickTextBroker {
            override fun changes(afterRevision: Long) = emptyFlow<QuickTextChange>()
            override suspend fun request(command: QuickTextCommand): QuickTextReply {
                commits++; return QuickTextReply.Failure(QuickTextError.CONFIRMATION_REQUIRED, "审核", reviewToken = "token")
            }
        }
        val env = environment(quick); val store = AgentStore(env.storage); store.updateSettings { it.copy(access = AgentAccessMode.FULL) }; val conversation = store.create()
        var requests = 0
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                requests++; val result = StreamAssembler()
                if (requests > 1) {
                    assertTrue(request.toString().contains("HOST_CONFIRMATION_REQUIRED"))
                    assertTrue(request.toString().contains("not_executed_reconcile_first"))
                    result.accept("""{"choices":[{"delta":{"content":"宿主仍要求确认，已返回具体限制"},"finish_reason":"stop"}]}"""); result.accept("[DONE]"); return result
                }
                result.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"quick_text","arguments":"{\"command\":{\"type\":\"commit\",\"token\":\"token\"}}"}},{"index":1,"id":"c2","function":{"name":"quick_text","arguments":"{\"command\":{\"type\":\"commit\",\"token\":\"other\"}}"}}]},"finish_reason":"tool_calls"}]}""")
                result.accept("[DONE]"); delta(result); return result
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(conversation.id, "change"); advanceUntilIdle()
        assertEquals(1, commits); assertEquals(2, requests); assertNull(store.pending)
        assertEquals(2, store.get(conversation.id)!!.messages.count { it.role == "tool" })
        assertTrue(store.get(conversation.id)!!.messages.any { it.content.contains("not_executed_reconcile_first") })
    }
    @Test fun cancellationDuringToolLeavesValidCallResultPairsWithoutRetry() = runTest {
        var attempts = 0
        val app = object : AppBroker { override suspend fun request(command: AppCommand): AppReply { attempts++; awaitCancellation() } }
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root); override val assets = temporary.root; override val appBroker = app
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("disabled")
        }
        val store = AgentStore(env.storage); store.updateSettings { it.copy(access = AgentAccessMode.FULL) }; val conversation = store.create()
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                val result = StreamAssembler()
                result.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"app","arguments":"{\"command\":{\"type\":\"text\",\"text\":\"x\"}}"}}]},"finish_reason":"tool_calls"}]}""")
                result.accept("[DONE]"); return result
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(conversation.id, "update"); runCurrent(); runner.stop(); advanceUntilIdle()
        assertEquals(1, attempts)
        val messages = store.get(conversation.id)!!.messages
        assertEquals(MessageState.COMPLETE, messages.first { it.role == "assistant" }.state)
        assertEquals("c1", messages.last().toolCallId)
        assertTrue(messages.last().content.contains("outcome_unknown"))
    }
    @Test fun fullAccessReturnsUnknownOutcomeForModelReconciliationWithoutReplayingMutation() = runTest {
        var attempts = 0; var requests = 0
        val app = object : AppBroker { override suspend fun request(command: AppCommand): AppReply { attempts++; throw java.io.IOException("connection lost after request") } }
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root); override val assets = temporary.root; override val appBroker = app
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("disabled")
        }
        val store = AgentStore(env.storage); store.updateSettings { it.copy(access = AgentAccessMode.FULL) }; val conversation = store.create()
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                requests++; val result = StreamAssembler()
                if (requests > 1) {
                    assertTrue(request.toString().contains("OUTCOME_UNKNOWN"))
                    assertTrue(request.toString().contains("connection lost after request"))
                    result.accept("""{"choices":[{"delta":{"content":"已核对状态，继续执行"},"finish_reason":"stop"}]}"""); result.accept("[DONE]"); return result
                }
                result.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"app","arguments":"{\"command\":{\"type\":\"text\",\"text\":\"x\"}}"}}]},"finish_reason":"tool_calls"}]}""")
                result.accept("[DONE]"); return result
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(conversation.id, "update"); advanceUntilIdle()
        assertEquals(1, attempts); assertEquals(2, requests)
        assertNull(store.pending)
        assertTrue(store.get(conversation.id)!!.messages.any { it.content.contains("OUTCOME_UNKNOWN") })
    }
    @Test fun disabledOperationsRejectUnexpectedProviderToolCalls() = runTest {
        var attempts = 0
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root); override val assets = temporary.root
            override val appBroker = object : AppBroker {
                override suspend fun request(command: AppCommand): AppReply { attempts++; error("Operations disabled") }
            }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("disabled")
        }
        val store = AgentStore(env.storage); store.updateSettings { it.copy(toolsEnabled = false) }; val conversation = store.create()
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                assertFalse(request.containsKey("tools"))
                val result = StreamAssembler()
                result.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"disabled-call","function":{"name":"app","arguments":"{\"command\":{\"type\":\"text\",\"text\":\"x\"}}"}}]},"finish_reason":"tool_calls"}]}""")
                result.accept("[DONE]"); return result
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(conversation.id, "hello"); advanceUntilIdle()
        assertEquals(0, attempts); assertNull(store.pending)
        assertEquals("disabled-call", store.get(conversation.id)!!.messages.last().toolCallId)
        assertTrue(store.get(conversation.id)!!.messages.last().content.contains("OPERATIONS_DISABLED"))
    }
    @Test fun stickerDeletionPersistsItsReviewAndDefersFollowingMutation() = runTest {
        var attempts = 0; var requests = 0
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root); override val assets = temporary.root
            override val stickerBroker = object : StickerBroker {
                override fun changes(afterRevision: Long) = emptyFlow<StickerChange>()
                override suspend fun request(command: StickerCommand): StickerReply {
                    attempts++; assertTrue(command is StickerCommand.Prepare)
                    return StickerReply.Prepared(StickerPreview("sticker-token", 7, listOf("删除"), 1, true))
                }
            }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("disabled")
        }
        val store = AgentStore(env.storage); store.updateSettings { it.copy(access = AgentAccessMode.REQUEST_APPROVAL) }; val conversation = store.create()
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                requests++; val result = StreamAssembler()
                result.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"delete","function":{"name":"stickers","arguments":"{\"command\":{\"type\":\"prepare\",\"transactionId\":\"tx\",\"revision\":7,\"edits\":[{\"type\":\"item.remove\",\"ids\":[\"photo\"]}]}}"}},{"index":1,"id":"later","function":{"name":"stickers","arguments":"{\"command\":{\"type\":\"commit\",\"token\":\"other\"}}"}}]},"finish_reason":"tool_calls"}]}""")
                result.accept("[DONE]"); return result
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(conversation.id, "delete photo"); advanceUntilIdle()
        assertEquals(1, attempts); assertEquals(1, requests)
        val restored = AgentStore(env.storage)
        assertEquals("sticker-token", restored.pending?.token); assertEquals(ReviewKind.STICKERS, restored.pending?.kind)
        assertEquals("tx", restored.pending?.transactionId)
        assertEquals(2, restored.get(conversation.id)!!.messages.count { it.role == "tool" })
        assertTrue(restored.get(conversation.id)!!.messages.last().content.contains("not_executed_pending_review"))
    }
}
