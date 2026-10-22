package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginJson
import com.kigtts.plugin.api.PluginStorage
import kotlinx.serialization.encodeToString
import java.io.File

/** Synchronizes UI reads with IO worker updates. Completed chunks are checkpointed, never replayed. */
class AgentStore(private val storage: PluginStorage) {
    private val settingsFile = storage.file("config/agent.json")
    private val conversationDirectory = storage.directory("conversations")
    private val conversationFiles = AgentConversationFiles(conversationDirectory)
    @Volatile var settings: AgentSettings = (read<AgentSettings>(settingsFile) ?: AgentSettings()).let { it.copy(profiles = AgentSettings().profiles + it.profiles) }
        private set
    private val pendingReviews = (read<Map<String, PendingReview>>(storage.file("config/pending-reviews.json")) ?: emptyMap()).toMutableMap()
        .apply { read<PendingReview>(storage.file("config/pending-review.json"))?.let { putIfAbsent(it.conversationId, it) } }
    val pending: PendingReview? @Synchronized get() = settings.selectedConversation?.let(pendingReviews::get)
    @Synchronized fun pendingFor(id: String): PendingReview? = pendingReviews[id]
    @Volatile var modelPickerConversation: String? = null
    @Volatile var modelFilter = ""
    @Volatile var modelOffset = 0
    @Volatile var modelStatus: Map<String, String> = emptyMap()
    private val history = mutableMapOf<String, Int>()
    @Synchronized fun historyBefore(id: String): Int? = history[id]
    @Synchronized fun latestHistory(id: String) { history.remove(id) }
    @Synchronized fun loadEarlier(id: String, before: Int) {
        require(before in 0..requireNotNull(conversations[id]).messages.size)
        history[id] = before
    }
    private val conversations = linkedMapOf<String, Conversation>()
    init {
        conversationDirectory.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
            conversationFiles.read(file)?.let { saved ->
                conversations[saved.id] = saved.copy(messages = saved.messages.map { message ->
                    if (message.state == MessageState.STREAMING) {
                        if (message.role == "tool") message.copy(state = MessageState.COMPLETE, content = "{\"status\":\"interrupted_outcome_unknown\"}")
                        else message.copy(state = MessageState.CANCELLED)
                    } else message
                })
            }
        }
    }
    @Synchronized fun updateSettings(change: (AgentSettings) -> AgentSettings) {
        val next = change(settings)
        if (next.provider != settings.provider || next.customProviderId != settings.customProviderId) {
            // Legacy sessions inherited the settings provider; pin it before editing another connection.
            conversations.values.toList().filter { it.provider == null }.forEach { old ->
                save(old.copy(provider = settings.provider, customProviderId = settings.customProviderId,
                    model = old.model ?: settings.profile().model))
            }
        }
        settings = next; write(settingsFile, settings)
    }
    @Synchronized fun list(): List<Conversation> = conversations.values.sortedByDescending { it.updatedAt }
    @Synchronized fun get(id: String): Conversation? = conversations[id]
    @Synchronized fun selected(): Conversation? = settings.selectedConversation?.let(conversations::get)
    @Synchronized fun create(): Conversation {
        check(conversations.size < 100) { "会话已达上限，请先删除不需要的会话" }
        val connection = settings.configuredConnection()
        val conversation = Conversation(provider = connection.provider, customProviderId = connection.customProviderId,
            model = connection.effectiveProfile().model); save(conversation)
        updateSettings { it.copy(selectedConversation = conversation.id) }; return conversation
    }
    @Synchronized fun select(id: String) { require(conversations.containsKey(id)); updateSettings { it.copy(selectedConversation = id) } }
    @Synchronized fun save(conversation: Conversation) {
        require(conversation.id.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        conversationFiles.save(conversation)
        conversations[conversation.id] = conversation
    }
    @Synchronized fun modify(id: String, change: (Conversation) -> Conversation): Conversation {
        val updated = change(requireNotNull(conversations[id])); save(updated); return updated
    }
    @Synchronized fun message(id: String, message: AgentMessage, persist: Boolean = true) {
        val conversation = requireNotNull(conversations[id]); val items = conversation.messages.toMutableList()
        val index = items.indexOfFirst { it.id == message.id || (message.role == "tool" && message.toolCallId != null && it.toolCallId == message.toolCallId) }
        if (index < 0) items += message else items[index] = message.copy(id = items[index].id)
        val updated = conversation.copy(messages = items, updatedAt = System.currentTimeMillis())
        if (persist) save(updated) else conversations[id] = updated
    }
    @Synchronized fun delete(id: String) {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        conversations.remove(id); conversationFiles.delete(id)
        if (settings.selectedConversation == id) updateSettings { it.copy(selectedConversation = list().firstOrNull()?.id) }
        setPending(null, id)
    }
    @Synchronized fun setPending(value: PendingReview?, conversationId: String? = null) {
        val id = value?.conversationId ?: conversationId ?: settings.selectedConversation ?: return
        if (value == null) pendingReviews.remove(id) else pendingReviews[id] = value
        write(storage.file("config/pending-reviews.json"), pendingReviews.toMap())
        storage.file("config/pending-review.json").delete()
    }
    private inline fun <reified T> read(file: File): T? = runCatching {
        if (file.length() > 16 * 1024 * 1024) null else PluginJson.decodeFromString<T>(file.readText(Charsets.UTF_8))
    }.getOrNull()
    private inline fun <reified T> write(file: File, value: T) {
        val json = PluginJson.encodeToString(value)
        require(json.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024) { "此会话已达保存上限，请新建会话" }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json, Charsets.UTF_8)
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
