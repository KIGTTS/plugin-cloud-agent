package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.serialization.encodeToString

/** Only task boundaries update unread markers; streaming tokens never write this file. */
internal class AgentTaskBadges(private val store: AgentStore, storage: PluginStorage) {
    private val file = storage.file("config/task-badges.json")
    private val unread = runCatching { PluginJson.decodeFromString<Map<String, BadgeTone>>(file.readText()) }
        .getOrDefault(emptyMap()).toMutableMap()

    @Synchronized fun changed(id: String, running: Boolean) {
        unread.remove(id)
        if (!running && store.pendingFor(id) == null) {
            val last = store.get(id)?.messages?.lastOrNull { it.role == "assistant" }
            when (last?.state) {
                MessageState.FAILED -> unread[id] = BadgeTone.WARNING
                MessageState.COMPLETE -> if (last.calls.isEmpty()) unread[id] = BadgeTone.THEME
                else -> Unit
            }
        }
        save()
    }

    @Synchronized fun viewed(id: String) { if (unread.remove(id) != null) save() }

    @Synchronized fun badge(): PluginBadge? {
        val ids = store.list().map { it.id }.toSet()
        return when {
            unread.any { it.key in ids && it.value == BadgeTone.WARNING } -> PluginBadge(description = "AI 任务出错", tone = BadgeTone.WARNING)
            ids.any { store.pendingFor(it) != null } -> PluginBadge(description = "AI 等待审核", tone = BadgeTone.CAUTION)
            unread.any { it.key in ids && it.value == BadgeTone.THEME } -> PluginBadge(description = "AI 任务已完成")
            else -> null
        }
    }

    @Synchronized fun attentionConversation(): String? {
        val conversations = store.list()
        return conversations.firstOrNull { unread[it.id] == BadgeTone.WARNING }?.id
            ?: conversations.firstOrNull { store.pendingFor(it.id) != null }?.id
            ?: conversations.firstOrNull { unread[it.id] == BadgeTone.THEME }?.id
    }

    private fun save() {
        // A storage failure must never interrupt the task queue or hide its result.
        runCatching {
            val ids = store.list().map { it.id }.toSet()
            unread.keys.retainAll(ids)
            file.parentFile?.mkdirs()
            file.writeText(PluginJson.encodeToString(unread.toMap()), Charsets.UTF_8)
        }
    }
}
