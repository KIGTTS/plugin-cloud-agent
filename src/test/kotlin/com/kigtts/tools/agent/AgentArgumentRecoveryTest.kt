package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentArgumentRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun incompletePublishDoesNotReachBrokerOrPauseFollowingTools() = runTest {
        val received = mutableListOf<QuickTextCommand>()
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root)
            override val assets = temporary.root
            override val quickTextBroker = object : QuickTextBroker {
                override fun changes(afterRevision: Long) = emptyFlow<QuickTextChange>()
                override suspend fun request(command: QuickTextCommand): QuickTextReply {
                    received += command; return QuickTextReply.Discarded("read-only-probe")
                }
            }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("No network")
        }
        val store = AgentStore(env.storage).apply { updateSettings { it.copy(access = AgentAccessMode.FULL) } }
        val conversation = store.create()
        var requests = 0
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject,
                delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                requests++
                val response = StreamAssembler()
                if (requests == 1) response.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"invalid","function":{"name":"quick_text","arguments":"{\"command\":{\"type\":\"publish-page\"}}"}},{"index":1,"id":"read","function":{"name":"quick_text","arguments":"{\"command\":{\"type\":\"describe\"}}"}}]},"finish_reason":"tool_calls"}]}""")
                else {
                    assertTrue(request.toString().contains("INVALID_ARGUMENTS"))
                    assertTrue(request.toString().contains("executed"))
                    response.accept("""{"choices":[{"delta":{"content":"参数已修正，可以继续"},"finish_reason":"stop"}]}""")
                }
                response.accept("[DONE]"); return response
            }
        }
        val secrets = object : AgentSecrets {
            override fun read(provider: Provider) = "test-key"
            override fun exists(provider: Provider) = true
            override fun write(provider: Provider, value: String) {}
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(conversation.id, "create"); advanceUntilIdle()
        assertEquals(2, requests); assertEquals(listOf(QuickTextCommand.Describe), received)
        assertNull(store.pendingFor(conversation.id))
        assertEquals("参数已修正，可以继续", store.get(conversation.id)!!.messages.last().content)
    }
    @Test fun wrongResourceShapeReportsUnexecutedValidationError() {
        val value = PluginJson.parseToJsonElement("""{"type":"publish-page","root":"workspace/example"}""")
        val error = assertThrows(AgentToolArgumentError::class.java) { decodeToolCommand<QuickTextCommand>(value) }
        val result = invalidToolArguments(ToolCall("test", "quick_text", "{}"), error)
        assertFalse(result.pause)
        assertFalse(PluginJson.parseToJsonElement(result.content).jsonObject.getValue("executed").jsonPrimitive.boolean)
        assertTrue(result.content.contains("root"))
    }
    @Test fun invalidWritePathIsReportedBeforeCreatingAnyFile() = runTest {
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root)
            override val assets = temporary.root
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("No network")
        }
        try {
            AgentTools(env).execute(ToolCall("bad-path", "write_file", """{"path":"moto-racer/index.html","text":"hello"}"""))
            fail("Invalid path must not execute")
        } catch (error: AgentToolArgumentError) {
            assertTrue(error.message!!.contains("workspace"))
        }
        assertFalse(File(temporary.root, "moto-racer").exists())
    }
    @Test fun nestedIconMetadataFailsBeforePrepareAndReturnsTheCorrectContract() = runTest {
        val received = mutableListOf<QuickTextCommand>()
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root)
            override val assets = temporary.root
            override val quickTextBroker = object : QuickTextBroker {
                override fun changes(afterRevision: Long) = emptyFlow<QuickTextChange>()
                override suspend fun request(command: QuickTextCommand): QuickTextReply {
                    received += command
                    return when (command) {
                        is QuickTextCommand.Transaction -> QuickTextReply.Failure(QuickTextError.NOT_FOUND, "none")
                        is QuickTextCommand.Query -> QuickTextReply.Snapshot(QuickTextSnapshot(40,
                            listOf(QuickGroup("group", "Test")), listOf(QuickEntry("entry", "group", "body"))))
                        else -> error("Invalid arguments must never prepare or commit")
                    }
                }
            }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("No network")
        }
        val call = ToolCall("icon", "edit_quick_text", """{"id":"entry","revision":40,"patch":{"metadata":{"icon":{"symbol":"pets","mode":"icon"}}}}""")
        try {
            AgentTools(env).execute(call); fail("Nested metadata should be rejected")
        } catch (error: AgentToolArgumentError) {
            val result = invalidToolArguments(call, error)
            assertFalse(result.pause); assertTrue(result.content.contains("icon.symbol"))
            assertFalse(PluginJson.parseToJsonElement(result.content).jsonObject.getValue("executed").jsonPrimitive.boolean)
        }
        assertEquals(2, received.size)
        assertTrue(received.none { it is QuickTextCommand.Prepare || it is QuickTextCommand.Commit })
    }
}
