package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentRuntimeFeedbackTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun validationErrorReachesTheNextModelRequestAndTheAgentCanRepairAndInstall() = runTest {
        var requests = 0; var installs = 0
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root); override val assets = temp.root
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
            override val appBroker = object : AppBroker {
                override suspend fun request(command: AppCommand): AppReply {
                    val diagnostic = if (command is AppCommand.PluginCall)
                        """{"ok":false,"phase":"pages","line":72,"error":"attempt to call nil","outcomeUnknown":false}"""
                    else { installs++; """{"ok":true,"installed":true,"enabled":true}""" }
                    return AppReply.Data(buildJsonObject { put("completed", true); put("outcome", PluginJson.encodeToJsonElement(PluginOutcome.serializer(), PluginOutcome.Dialog("结果", diagnostic))) })
                }
            }
        }
        val store = AgentStore(env.storage); store.updateSettings { it.copy(access = AgentAccessMode.FULL) }; val chat = store.create()
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                requests++
                assertTrue(request["messages"].toString().contains("pages(ctx)直接返回页面数组"))
                val call = when (requests) {
                    1 -> ToolCall("test", "lua", """{"operation":"test","project":"test.game"}""")
                    2 -> {
                        val tool = request.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
                        val error = PluginJson.parseToJsonElement(tool).jsonObject
                        assertEquals(72, error.getValue("line").jsonPrimitive.int)
                        assertEquals("pages", error.getValue("phase").jsonPrimitive.content)
                        ToolCall("fix", "draft_lua", """{"manifest":{"id":"test.game","name":"Game","version":"2","permissions":[]},"source":"return {pages=function(c) return {} end}"}""")
                    }
                    3 -> ToolCall("install", "deliver_lua", """{"path":"drafts/test.game"}""")
                    else -> null
                }
                val json = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                    put("delta", buildJsonObject {
                        if (call == null) put("content", "已修复") else put("tool_calls", buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("id", call.id); put("function", buildJsonObject { put("name", call.name); put("arguments", call.arguments) })
                        }) })
                    }); put("finish_reason", if (call == null) "stop" else "tool_calls")
                }) }) }.toString()
                return StreamAssembler().apply { accept(json); accept("[DONE]") }
            }
        }
        val secrets = object : AgentSecrets { override fun read(provider: Provider) = "test"; override fun exists(provider: Provider) = true; override fun write(provider: Provider, value: String) {} }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(chat.id, "修复插件"); advanceUntilIdle()
        assertEquals(4, requests); assertEquals(1, installs); assertNull(store.pending)
        assertEquals("已修复", store.get(chat.id)!!.messages.last().content)
    }
}
