@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private val secrets = object : AgentSecrets {
        override fun read(provider: Provider) = "test-key"
        override fun exists(provider: Provider) = true
        override fun write(provider: Provider, value: String) {}
    }
    @Test fun defaultApprovalPausesBeforeMutationAndResumesTheOriginalCallOnlyOnce() = runTest {
        var calls = 0; var requests = 0
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root); override val assets = temporary.root
            override val appBroker = object : AppBroker { override suspend fun request(command: AppCommand): AppReply {
                calls++; assertEquals(AppCommand.SetText("approved"), command); return AppReply.Data(buildJsonObject { put("accepted", true) })
            } }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val store = AgentStore(env.storage); val conversation = store.create()
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                requests++
                val result = StreamAssembler()
                if (requests == 1) result.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"write","function":{"name":"app","arguments":"{\"command\":{\"type\":\"text\",\"text\":\"approved\"}}"}}]},"finish_reason":"tool_calls"}]}""")
                else result.accept("""{"choices":[{"delta":{"content":"已完成"},"finish_reason":"stop"}]}""")
                result.accept("[DONE]"); return result
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(conversation.id, "更新字幕"); advanceUntilIdle()
        assertEquals(0, calls); assertEquals(1, requests)
        val pending = requireNotNull(store.pending)
        assertEquals(ReviewKind.TOOL, pending.kind); assertEquals("write", pending.call?.id)
        assertEquals(pending, AgentStore(env.storage).pending)
        runner.approve(pending); advanceUntilIdle()
        assertEquals(1, calls); assertEquals(2, requests); assertNull(store.pending)
        assertEquals("已完成", store.get(conversation.id)!!.messages.last().content)
    }
    @Test fun cancellingAnApprovedMutationKeepsTheUnknownOutcomeWithoutReplayingIt() = runTest {
        var calls = 0
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root); override val assets = temporary.root
            override val appBroker = object : AppBroker { override suspend fun request(command: AppCommand): AppReply { calls++; kotlinx.coroutines.awaitCancellation() } }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val store = AgentStore(env.storage); val conversation = store.create()
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                val result = StreamAssembler()
                result.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"change","function":{"name":"app","arguments":"{\"command\":{\"type\":\"text\",\"text\":\"new\"}}"}}]},"finish_reason":"tool_calls"}]}""")
                result.accept("[DONE]"); return result
            }
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        runner.send(conversation.id, "change"); advanceUntilIdle()
        runner.approve(requireNotNull(store.pending)); runCurrent()
        assertEquals(1, calls); assertEquals(ReviewKind.EXTERNAL, store.pending?.kind)
        runner.stop(); advanceUntilIdle()
        assertEquals(1, calls); assertEquals(ReviewKind.EXTERNAL, AgentStore(env.storage).pending?.kind)
        assertTrue(store.get(conversation.id)!!.messages.last().content.contains("outcome_unknown"))
    }
    @Test fun eachConversationKeepsItsModelAndSettingsMigrationDefaultsToApproval() {
        val store = AgentStore(PluginStorage(temporary.root)); val first = store.create()
        store.save(first.copy(provider = Provider.GLM, model = "custom-model"))
        val second = store.create(); store.save(second.copy(provider = Provider.KIMI, model = "other-model"))
        val restored = AgentStore(PluginStorage(temporary.root))
        assertEquals("custom-model", restored.connection(requireNotNull(restored.get(first.id))).profiles[Provider.GLM]?.model)
        assertEquals("other-model", restored.connection(requireNotNull(restored.get(second.id))).profiles[Provider.KIMI]?.model)
        assertEquals(AgentAccessMode.REQUEST_APPROVAL, restored.settings.access)
    }
    @Test fun researchParserReturnsCitableSourcesAndRemovesExecutableMarkup() {
        val parsed = AgentWebTools.parseSearch("""<rss><channel><item><title>资料 &amp; 示例</title><link>https://example.com/article</link><description><![CDATA[<b>正文摘要</b>]]></description></item></channel></rss>""")
        assertEquals("https://example.com/article", parsed[0].jsonObject["url"]?.jsonPrimitive?.content)
        assertEquals("正文摘要", parsed[0].jsonObject["summary"]?.jsonPrimitive?.content)
        assertEquals("正文", AgentWebTools.plain("<script>bad()</script><style>body{}</style><p>正文</p>"))
    }
}
