package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AgentFileActions(private val environment: PluginEnvironment, private val store: AgentStore) {
    suspend fun handle(action: String, context: PluginContext): PluginOutcome? = when {
        action == "attach-file" -> {
            val conversation = store.target(context) ?: store.create()
            require(conversation.draftFiles.size < 8) { "每条消息最多8个文件" }
            PluginOutcome.ImportResource("添加文件", "imports/chat-files/${UUID.randomUUID()}", "attach-file-complete/${conversation.id}", multiple = true)
        }
        action.startsWith("attach-file-complete/") -> withContext(Dispatchers.IO) {
            val id = action.substringAfter('/')
            val paths = context.inputs["resourcePaths"]?.let { PluginJson.decodeFromString<List<String>>(it) }
                ?: listOf(requireNotNull(context.inputs["resourcePath"]))
            require(paths.all { it.startsWith("imports/chat-files/") }) { "文件来源无效" }
            try {
                val files = AgentFiles(environment.storage) { path -> require(path.startsWith("imports/chat-files/")); path }
                val refs = paths.map(files::reference)
                store.modify(id) { current ->
                    require(current.draftFiles.size + refs.size <= 8) { "每条消息最多8个文件" }
                    require(current.draftFiles.sumOf { it.bytes } + refs.sumOf { it.bytes } <= AGENT_MESSAGE_FILE_LIMIT) { "附件总大小不能超过128MB" }
                    current.copy(draftFiles = current.draftFiles + refs)
                }
                PluginOutcome.RefreshUi
            } catch (error: Exception) { paths.forEach { environment.storage.file(it).delete() }; throw error }
        }
        action == "remove-file" -> {
            val conversation = store.target(context) ?: store.create()
            store.modify(conversation.id) { it.copy(draftFiles = it.draftFiles.filterNot { file -> file.path == context.inputs["value"] }) }
            PluginOutcome.RefreshUi
        }
        action.startsWith("export-file/") -> {
            val path = String(Base64.getUrlDecoder().decode(action.substringAfter('/')), Charsets.UTF_8)
            // UI actions can only export files actually referenced in this caller's conversations.
            val file = store.list().asSequence().flatMap { c -> (c.draftFiles + c.messages.flatMap { it.files } + c.queued.flatMap { it.files }).asSequence() }
                .firstOrNull { it.path == path } ?: error("文件已移除")
            require(environment.storage.file(file.path).isFile) { "文件已丢失，请重新添加" }
            PluginOutcome.ExportResource("保存文件", PluginResource(file.path), file.name, file.mime)
        }
        else -> null
    }
}
