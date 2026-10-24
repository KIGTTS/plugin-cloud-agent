package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun environment(app: AppBroker? = null, quick: QuickTextBroker? = null) = object : PluginEnvironment {
        override val storage = PluginStorage(temporary.root)
        override val assets = temporary.root
        override val appBroker = app
        override val quickTextBroker = quick
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("No network in this test")
    }
    @Test fun forwardsTypedStateWithoutInventingCaller() = runTest {
        var captured: AppCommand? = null
        val app = object : AppBroker { override suspend fun request(command: AppCommand): AppReply { captured = command; return AppReply.Data(buildJsonObject { put("ready", true) }) } }
        AgentTools(environment(app)).execute(ToolCall("call", "app", "{\"command\":{\"type\":\"state\"}}"))
        assertEquals(AppCommand.State, captured)
    }
    @Test fun mutationReviewStopsTheRunnerAtTheHostBoundary() = runTest {
        val quick = object : QuickTextBroker {
            override fun changes(afterRevision: Long) = emptyFlow<QuickTextChange>()
            override suspend fun request(command: QuickTextCommand): QuickTextReply = QuickTextReply.Failure(QuickTextError.CONFIRMATION_REQUIRED, "审核", reviewToken = "token")
        }
        val result = AgentTools(environment(quick = quick)).execute(ToolCall("call", "quick_text", "{\"command\":{\"type\":\"commit\",\"token\":\"token\"}}"))
        assertTrue(result.pause); assertEquals("token", result.reviewToken)
    }
    @Test fun askUserReturnsChoiceOptionsWithoutEnteringPermissionReview() = runTest {
        val tools = AgentTools(environment())
        val choices = buildJsonArray {
            add(buildJsonObject { put("label", "预览"); put("value", "preview") })
            add(buildJsonObject { put("label", "发布"); put("value", "publish") })
        }
        val result = tools.execute(ToolCall("call", "ask_user",
            buildJsonObject { put("question", "选择模式"); put("options", choices.toString()) }.toString()))
        assertTrue(result.pause)
        assertEquals("选择模式", result.summary)
        assertEquals(listOf("预览", "发布"), result.options.map { it.label })
        assertFalse(tools.requiresApproval(ToolCall("call", "ask_user", "{}")))
    }
    @Test fun fileToolsCannotReachSecretsOrOtherPluginDirectories() = runTest {
        val tools = AgentTools(environment())
        listOf("config/key-DEEPSEEK.bin", "workspace/../config/key-DEEPSEEK.bin", "D:/other", "../elsewhere").forEach { path ->
            try { tools.execute(ToolCall("call", "read_file", buildJsonObject { put("path", path) }.toString())); fail(path) }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun htmlFilesStayInOwnedWorkspaceAndReturnScopedResource() = runTest {
        val tools = AgentTools(environment())
        val result = tools.execute(ToolCall("call", "write_file", "{\"path\":\"workspace/calculator/index.html\",\"text\":\"<h1>计算器</h1>\"}"))
        assertEquals("<h1>计算器</h1>", File(temporary.root, "workspace/calculator/index.html").readText())
        assertTrue(result.content.contains("workspace/calculator/index.html"))
    }
    @Test fun brokerResourcePublishingCannotLeakConfigOrOverwriteSecrets() = runTest {
        var called = false
        val quick = object : QuickTextBroker {
            override fun changes(afterRevision: Long) = emptyFlow<QuickTextChange>()
            override suspend fun request(command: QuickTextCommand): QuickTextReply { called = true; return QuickTextReply.Discarded("x") }
        }
        val tools = AgentTools(environment(quick = quick))
        listOf(
            "{\"type\":\"publish-page\",\"root\":{\"path\":\"config\"}}",
            "{\"type\":\"get-file\",\"mediaId\":\"m\",\"path\":\"index.html\",\"destination\":\"config/key-DEEPSEEK.bin\"}",
            "{\"type\":\"publish-audio\",\"source\":{\"path\":\"workspace/test.mp3\",\"pluginId\":\"another.plugin\"}}"
        ).forEach { command ->
            try { tools.execute(ToolCall("call", "quick_text", "{\"command\":$command}")); fail(command) }
            catch (_: IllegalArgumentException) { }
        }
        assertFalse(called)
    }
    @Test fun luaDraftDoesNotInstallAndDeliveryReportsReviewNotSuccess() = runTest {
        var captured: AppCommand? = null
        val app = object : AppBroker { override suspend fun request(command: AppCommand): AppReply { captured = command; return AppReply.Data(buildJsonObject { put("accepted", true); put("pending", true) }) } }
        val tools = AgentTools(environment(app))
        val draft = tools.execute(ToolCall("call", "draft_lua", "{\"manifest\":{\"id\":\"tools.sample\",\"name\":\"示例\",\"version\":\"0.1.0\",\"permissions\":[]},\"source\":\"return {}\"}"))
        assertTrue(draft.content.contains("\"installed\":false")); assertNull(captured)
        val delivered = tools.execute(ToolCall("call2", "deliver_lua", "{\"path\":\"drafts/tools.sample\"}"))
        assertEquals(AppCommand.DeliverResource("runtime.lua", PluginResource("drafts/tools.sample"), "agent-install"), captured)
        assertTrue(delivered.pause)
    }
}
