package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest

@Serializable private data class ConversationRecord(val conversation: Conversation, val chunks: List<String>)

/** Immutable message chunks plus an atomically replaced index; a tool round never rewrites all history. */
internal class AgentConversationFiles(private val root: File) {
    private val cached = mutableMapOf<String, List<Pair<List<AgentMessage>, String>>>()
    fun read(file: File): Conversation? = runCatching {
        val text = file.readText(Charsets.UTF_8)
        val record = runCatching { PluginJson.decodeFromString<ConversationRecord>(text) }.getOrNull()
        if (record == null) PluginJson.decodeFromString<Conversation>(text) else {
            require(record.conversation.id == file.nameWithoutExtension)
            record.conversation.copy(messages = record.chunks.flatMap { hash ->
                require(hash.matches(Regex("[a-f0-9]{64}")))
                PluginJson.decodeFromString<List<AgentMessage>>(File(root, "${record.conversation.id}/$hash.json").readText(Charsets.UTF_8))
            })
        }
    }.getOrNull()
    fun save(conversation: Conversation) {
        val directory = File(root, conversation.id).apply { mkdirs() }
        val previous = cached[conversation.id].orEmpty()
        val prepared = conversation.messages.chunked(100).mapIndexed { index, messages ->
            previous.getOrNull(index)?.takeIf { it.first == messages }?.let { return@mapIndexed it }
            val bytes = PluginJson.encodeToString(messages).toByteArray(Charsets.UTF_8)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val target = File(directory, "$hash.json")
            if (!target.exists()) atomicWrite(target, bytes)
            messages to hash
        }
        val chunks = prepared.map { it.second }
        atomicWrite(File(root, "${conversation.id}.json"), PluginJson.encodeToString(ConversationRecord(conversation.copy(messages = emptyList()), chunks)).toByteArray(Charsets.UTF_8))
        cached[conversation.id] = prepared
        // Only files owned by this conversation, after the new index is durable.
        directory.listFiles()?.filter { it.extension == "json" && it.nameWithoutExtension !in chunks }?.forEach { it.delete() }
    }
    fun delete(id: String) { cached.remove(id); File(root, "$id.json").delete(); File(root, id).deleteRecursively() }
    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val stage = File(target.parentFile, "${target.name}.tmp")
        java.io.FileOutputStream(stage).use { it.write(bytes); it.fd.sync() }
        java.nio.file.Files.move(stage.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
