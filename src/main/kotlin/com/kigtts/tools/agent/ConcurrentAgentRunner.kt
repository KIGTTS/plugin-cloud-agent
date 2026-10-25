package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import java.util.concurrent.ConcurrentHashMap

/** Every conversation owns its transport, queue, tool/browser state, compactor and history reader. */
internal class ConcurrentAgentRunner(private val store: AgentStore, secrets: AgentSecrets,
    private val environment: PluginEnvironment, private val notify: () -> Unit,
    private val taskChanged: (String, Boolean) -> Unit = { _, _ -> }) : AgentRunner(store, secrets,
    HttpsChatTransport { environment.requirePermission("network") }, AgentTools(environment, webSettings = { store.settings }, webSecrets = secrets), notify) {
    private val keys = secrets
    private val sessions = ConcurrentHashMap<String, AgentRunner>()
    private fun session(id: String) = sessions.computeIfAbsent(id) {
        AgentRunner(store, keys, HttpsChatTransport { environment.requirePermission("network") },
            AgentTools(environment, historyReader = { store.get(id) }, webSettings = { store.settings }, webSecrets = keys) { store.settings.access == AgentAccessMode.FULL },
            notify, boundConversation = id, taskChanged = taskChanged)
    }
    override val running get() = sessions.values.any { it.running }
    override val runningConversation get() = store.selected()?.id?.takeIf(::isRunning)
    override fun isRunning(id: String) = sessions[id]?.running == true
    override fun send(conversationId: String, text: String) = session(conversationId).send(conversationId, text)
    override fun continueAfterReview(conversationId: String) = session(conversationId).continueAfterReview(conversationId)
    override fun approve(pending: PendingReview) = session(pending.conversationId).approve(pending)
    override fun enqueue(id: String, text: String, interrupt: Boolean) = session(id).enqueue(id, text, interrupt)
    override fun manageQueue(id: String, messageId: String, front: Boolean) = session(id).manageQueue(id, messageId, front)
    override fun reorderQueue(id: String, order: List<String>) = session(id).reorderQueue(id, order)
    override fun steerQueued(id: String, messageId: String) = session(id).steerQueued(id, messageId)
    override fun stop() { store.selected()?.id?.let { sessions[it]?.stop() } }
    override fun stopConversation(id: String) { sessions[id]?.stop() }
    override suspend fun close() { sessions.values.forEach { it.close() }; sessions.clear(); super.close() }
}
