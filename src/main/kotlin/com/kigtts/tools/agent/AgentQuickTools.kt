package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

/** High level partial updates retain attachment, color, playback and metadata; revisions prevent lost edits. */
internal class AgentQuickTools(private val environment: PluginEnvironment) {
    private val broker get() = requireNotNull(environment.quickTextBroker) { "宿主没有快捷文本能力" }
    suspend fun execute(call: ToolCall, args: JsonObject): ToolResult = when (call.name) {
        "list_quick_text", "read_quick_text" -> query(args, call.name == "read_quick_text")
        "edit_quick_text" -> edit(call.id, args)
        "read_page_file" -> readPage(args)
        "write_page_file" -> writePage(args)
        "write_quick_image" -> writeImage(call.id, args)
        else -> error("未知快捷文本工具")
    }
    private suspend fun query(args: JsonObject, single: Boolean): ToolResult {
        val id = args["id"]?.jsonPrimitive?.content
        val search = args["query"]?.jsonPrimitive?.content.orEmpty()
        val group = args["groupId"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        var offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 50)
        var revision: Long? = null
        val found = mutableListOf<QuickEntry>(); val attachments = mutableListOf<QuickMedia>()
        var groups = emptyList<QuickGroup>(); var next: Int? = null
        do {
            val reply = broker.request(QuickTextCommand.Query(group, offset, 50, revision))
            if (reply !is QuickTextReply.Snapshot) return result(reply)
            val snapshot = reply.value; revision = snapshot.revision; groups = snapshot.groups
            for ((index, entry) in snapshot.entries.withIndex()) {
                if ((id == null || entry.id == id) && (search.isBlank() || entry.text.contains(search, true) || entry.title.contains(search, true))) {
                    found += entry; attachments += snapshot.media.filter { it.id == entry.mediaId }
                    if (single || found.size == limit) { next = (offset + index + 1).takeIf { it < snapshot.total }; break }
                }
            }
            if (single && found.isNotEmpty() || found.size >= limit) break
            next = snapshot.nextOffset; offset = next ?: break
        } while (true)
        if (single && found.isEmpty()) return result(QuickTextReply.Failure(QuickTextError.NOT_FOUND, "找不到此条目，请list_quick_text取得真实id"))
        return ToolResult(buildJsonObject {
            put("ok", true); put("revision", revision); put("groups", PluginJson.encodeToJsonElement(groups))
            put("entries", PluginJson.encodeToJsonElement(found)); put("media", PluginJson.encodeToJsonElement(attachments.distinctBy { it.id }))
            next?.let { put("nextOffset", it) }
        }.toString())
    }
    private suspend fun edit(callId: String, args: JsonObject): ToolResult {
        val transaction = "agent-edit-" + UUID.nameUUIDFromBytes(callId.toByteArray())
        when (val receipt = broker.request(QuickTextCommand.Transaction(transaction))) {
            is QuickTextReply.Applied -> return result(receipt)
            is QuickTextReply.Prepared -> return result(broker.request(QuickTextCommand.Commit(receipt.value.token)))
            is QuickTextReply.Failure -> if (receipt.code != QuickTextError.NOT_FOUND) return result(receipt)
            else -> error("事务结果不明确")
        }
        val read = query(buildJsonObject { put("id", args.string("id")) }, true)
        val snapshot = PluginJson.parseToJsonElement(read.content).jsonObject
        if (snapshot["ok"]?.jsonPrimitive?.booleanOrNull != true) return read
        val revision = snapshot.getValue("revision").jsonPrimitive.long
        if (revision != toolInput { args.getValue("revision").jsonPrimitive.long }) return result(QuickTextReply.Failure(QuickTextError.CONFLICT, "快捷文本已经变化，请重新read_quick_text后修改", revision))
        val original = snapshot.getValue("entries").jsonArray.single().jsonObject
        val patch = toolInput {
            args.getValue("patch").jsonObject.also { patch ->
                require(patch.isNotEmpty() && patch.keys.all { it in setOf("text", "title", "groupId", "kind", "mediaId", "colorKind", "color", "richText", "formats", "autoPlay", "audioPolicy", "metadata") }) { "patch包含不支持字段" }
            }
        }
        val merged = original.toMutableMap().apply { putAll(patch)
            (patch["metadata"] as? JsonObject)?.let { metadata ->
                put("metadata", JsonObject(((original["metadata"] as? JsonObject).orEmpty() + metadata).filterValues { it != JsonNull }))
            }
            if (patch["text"] != null && patch["text"] != original["text"] && "formats" !in patch) put("formats", JsonArray(emptyList()))
        }
        val entry = decodeToolCommand<QuickEntry>(JsonObject(merged))
        val prepared = broker.request(QuickTextCommand.Prepare(transaction, revision, listOf(QuickTextEdit.PutEntry(entry))))
        return if (prepared is QuickTextReply.Prepared) result(broker.request(QuickTextCommand.Commit(prepared.value.token))) else result(prepared)
    }
    private suspend fun readPage(args: JsonObject): ToolResult {
        val target = "workspace/reads/" + UUID.randomUUID() + ".txt"
        val reply = broker.request(QuickTextCommand.GetFile(args.string("mediaId"), args.string("path"), target))
        if (reply !is QuickTextReply.Resource) return result(reply)
        val file = environment.storage.file(target)
        require(file.length() <= 512 * 1024) { "文本过大，请使用文件导出" }
        val text = file.readText(Charsets.UTF_8); file.delete()
        val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, text.length)
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 12000).coerceIn(1, 16000)
        return ToolResult(buildJsonObject { put("ok", true); put("text", text.drop(offset).take(limit)); put("offset", offset); put("total", text.length)
            if (offset + limit < text.length) put("nextOffset", offset + limit)
        }.toString())
    }
    private suspend fun writePage(args: JsonObject): ToolResult {
        val text = args.string("text"); require(text.toByteArray().size <= 512 * 1024)
        val path = "workspace/edits/" + UUID.randomUUID() + ".txt"
        val file = environment.storage.file(path); file.parentFile!!.mkdirs(); file.writeText(text, Charsets.UTF_8)
        return result(broker.request(QuickTextCommand.PutFile(args.string("mediaId"), args.string("path"), PluginResource(path), args.getValue("version").jsonPrimitive.long)))
    }
    private suspend fun writeImage(callId: String, args: JsonObject): ToolResult {
        // Replayed tool callbacks must return the previous mutation before publishing another file.
        val transaction = "agent-edit-" + UUID.nameUUIDFromBytes(callId.toByteArray())
        when (val receipt = broker.request(QuickTextCommand.Transaction(transaction))) {
            is QuickTextReply.Applied -> return result(receipt)
            is QuickTextReply.Prepared -> return result(broker.request(QuickTextCommand.Commit(receipt.value.token)))
            is QuickTextReply.Failure -> if (receipt.code != QuickTextError.NOT_FOUND) return result(receipt)
            else -> error("事务结果不明确")
        }
        val read = query(buildJsonObject { put("id", args.string("id")) }, true)
        val snapshot = PluginJson.parseToJsonElement(read.content).jsonObject
        if (snapshot["ok"]?.jsonPrimitive?.booleanOrNull != true) return read
        val revision = args.getValue("revision").jsonPrimitive.long
        if (snapshot.getValue("revision").jsonPrimitive.long != revision)
            return result(QuickTextReply.Failure(QuickTextError.CONFLICT, "快捷文本已更新，请重新读取", snapshot.getValue("revision").jsonPrimitive.long))
        val path = args.string("path"); require(path.startsWith("workspace/") || path.startsWith("imports/"))
        val source = environment.storage.file(path); require(source.isFile && source.length() in 1..32L*1024*1024)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(source.path, bounds); require(bounds.outWidth > 0 && bounds.outHeight > 0) { "请选择真实图片文件" }
        val extension = when (bounds.outMimeType) {
            "image/png" -> "png"; "image/webp" -> "webp"; "image/jpeg" -> "jpg"
            "image/gif" -> "gif"; "image/avif" -> "avif"; "image/heif" -> "heif"; "image/heic" -> "heic"; "image/bmp" -> "bmp"
            else -> source.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "image"
        }
        val owned = "workspace/quick-images/" + UUID.randomUUID() + "." + extension
        environment.storage.file(owned).apply { parentFile!!.mkdirs(); source.copyTo(this) }
        val media = broker.request(QuickTextCommand.PublishMedia(SubtitleContent.Image(PluginResource(owned), args["description"]?.jsonPrimitive?.content.orEmpty())))
        if (media !is QuickTextReply.Media) return result(media)
        return edit(callId, buildJsonObject { put("id", args.string("id")); put("revision", revision)
            put("patch", buildJsonObject { put("kind", "MEDIA"); put("mediaId", media.value.id) }) })
    }
    private fun result(reply: QuickTextReply): ToolResult {
        val token = (reply as? QuickTextReply.Failure)?.takeIf { it.code == QuickTextError.CONFIRMATION_REQUIRED }?.reviewToken
        return ToolResult(PluginJson.encodeToString(reply), token, token != null)
    }
}
