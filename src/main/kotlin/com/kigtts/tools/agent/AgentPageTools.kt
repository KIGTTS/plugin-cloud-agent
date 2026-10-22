package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

/** Publish files and commit a real PAGE entry, with a stable receipt for this tool call. */
internal class AgentPageTools(private val environment: PluginEnvironment) {
    suspend fun create(callId: String, args: JsonObject): ToolResult {
        val broker = requireNotNull(environment.quickTextBroker) { "宿主没有快捷文本能力" }
        val key = UUID.nameUUIDFromBytes(callId.toByteArray()).toString()
        val transaction = "agent-page-$key"
        when (val receipt = broker.request(QuickTextCommand.Transaction(transaction))) {
            is QuickTextReply.Applied -> return result(receipt)
            is QuickTextReply.Prepared -> return result(broker.request(QuickTextCommand.Commit(receipt.value.token)))
            is QuickTextReply.Failure -> require(receipt.code == QuickTextError.NOT_FOUND) { receipt.message }
            else -> error("事务状态不明确，请先检查")
        }
        val html = args.string("html"); require(html.toByteArray().size in 1..512 * 1024)
        val title = args["title"]?.jsonPrimitive?.content.orEmpty().take(160)
        val text = args["text"]?.jsonPrimitive?.content.orEmpty().take(16000)
        val path = "workspace/pages/$key"
        environment.storage.directory(path)
        environment.storage.file("$path/index.html").writeText(html, Charsets.UTF_8)
        val publication = broker.request(QuickTextCommand.PublishPage(PluginResource(path), name = title.ifBlank { "网页" }))
        if (publication !is QuickTextReply.Media) return result(publication)
        val queried = broker.request(QuickTextCommand.Query(limit = 0))
        if (queried !is QuickTextReply.Snapshot) return result(queried)
        val snapshot = queried.value
        val requested = args["groupId"]?.jsonPrimitive?.content.orEmpty()
        val group = if (requested.isNotBlank()) snapshot.groups.firstOrNull { it.id == requested }
            else snapshot.groups.firstOrNull { it.id == snapshot.selectedGroupId } ?: snapshot.groups.firstOrNull()
        requireNotNull(group) { "请先创建快捷文本分组，或使用query取得正确groupId" }
        val entry = QuickEntry(id = key, groupId = group.id, title = title, text = text,
            kind = QuickCardKind.PAGE, mediaId = publication.value.id)
        val prepared = broker.request(QuickTextCommand.Prepare(transaction, snapshot.revision, listOf(QuickTextEdit.PutEntry(entry))))
        return if (prepared is QuickTextReply.Prepared) result(broker.request(QuickTextCommand.Commit(prepared.value.token))) else result(prepared)
    }
    private fun result(reply: QuickTextReply): ToolResult {
        val token = (reply as? QuickTextReply.Failure)?.takeIf { it.code == QuickTextError.CONFIRMATION_REQUIRED }?.reviewToken
        return ToolResult(PluginJson.encodeToString(reply), token, token != null)
    }
}
