package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.serialization.json.*

/** Query original evidence, excluding history-query echoes which otherwise grow recursively. */
internal class AgentHistoryTools(private val storage: PluginStorage, private val read: () -> Conversation?) {
    fun query(args: JsonObject): ToolResult {
        val conversation = requireNotNull(read()) { "当前无执行中的会话" }
        val historyCalls = conversation.messages.flatMap { it.calls }.filter { it.name == "conversation_history" }.map { it.id }.toSet()
        val query = args["query"]?.jsonPrimitive?.content.orEmpty()
        val rows = conversation.messages.mapIndexedNotNull { position, message ->
            if (message.toolCallId in historyCalls) return@mapIndexedNotNull null
            val calls = message.calls.filterNot { it.id in historyCalls }
            if (message.role == "assistant" && message.content.isBlank() && message.calls.isNotEmpty() && calls.isEmpty()) return@mapIndexedNotNull null
            if (query.isNotBlank() && !message.content.contains(query, true) && calls.none { it.id == query || it.name.contains(query, true) || it.arguments.contains(query, true) }) return@mapIndexedNotNull null
            buildJsonObject {
                put("position", position); put("id", message.id); put("role", message.role)
                message.toolCallId?.let { put("toolCallId", it) }
                put("content", message.content.take(6000)); put("contentTruncated", message.content.length > 6000)
                put("calls", buildJsonArray { calls.take(8).forEach { call -> add(buildJsonObject {
                    put("id", call.id); put("name", call.name); put("arguments", call.arguments.take(1500))
                    put("argumentsTruncated", call.arguments.length > 1500)
                }) } })
                put("callsTruncated", calls.size > 8)
                put("images", PluginJson.encodeToJsonElement(message.images))
            }
        }
        val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, rows.size)
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 20)
        var bytes = 0
        val page = rows.drop(offset).take(limit).takeWhile { row ->
            val size = row.toString().length
            (bytes == 0 || bytes + size <= 24000).also { if (it) bytes += size }
        }
        return ToolResult(buildJsonObject {
            put("total", rows.size); put("nextOffset", offset + page.size)
            put("messages", JsonArray(page))
            put("note", "仅当前会话；历史查询回声不重复返回。用query定向查找。大段旧write_file源码可用recover_file按真实callId恢复到workspace，不手工拼接截断片段。")
        }.toString())
    }
    fun recover(args: JsonObject): ToolResult {
        val call = requireNotNull(read()).messages.flatMap { it.calls }.firstOrNull { it.id == args.string("callId") }
        val (path, text) = toolInput {
            require(call?.name == "write_file") { "只能恢复当前会话真实write_file调用的文本" }
            val original = PluginJson.parseToJsonElement(requireNotNull(call).arguments).jsonObject
            val path = args.string("path")
            require(path.startsWith("workspace/")) { "恢复目标必须在workspace/内" }
            val text = original.string("text")
            require(text.toByteArray(Charsets.UTF_8).size in 1..512 * 1024)
            storage.file(path)
            path to text
        }
        val file = storage.file(path)
        file.parentFile?.mkdirs(); file.writeText(text, Charsets.UTF_8)
        return ToolResult(buildJsonObject { put("ok", true); put("path", path); put("bytes", file.length()); put("sourceCallId", call!!.id) }.toString())
    }
}

/** Preserve fresh query evidence while retiring older echoes from API context, not from disk. */
internal fun historyContext(messages: List<AgentMessage>): List<AgentMessage> {
    val calls = messages.flatMap { it.calls }.filter { it.name == "conversation_history" }.map { it.id }.toSet()
    val recent = messages.asReversed().filter { it.toolCallId in calls }.take(2).map { it.id }.toSet()
    return messages.map { message ->
        if (message.toolCallId !in calls) message
        else if (message.id in recent) message.copy(content = message.content.take(24000) +
            if (message.content.length > 24000) "\n[历史结果过大已截断；用query定向查原记录，不重复扫描。]" else "")
        else message.copy(content = "[较早历史查询结果已从API上下文压缩，原始记录保留。需要特定记录时用conversation_history的query定向检索。]")
    }
}
