package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Task events are serialized independently of streamed tokens and model requests. Denial never stops a task. */
internal class AgentTaskNotifications(private val store: AgentStore, private val environment: PluginEnvironment,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val copyFor: (String) -> AgentCopy = { sourceAgentCopy }) {
    private val events = Channel<Pair<String, Boolean>>(200)
    init { scope.launch {
        for ((id, running) in events) {
            if (!store.settings.taskNotifications) continue
            val conversation = store.get(id) ?: continue
            val copy = copyFor(environment.locale)
            val state = when {
                running -> copy("chat.running", "正在执行")
                store.pendingFor(id) != null -> copy("notice.waiting", "等待你的确认")
                conversation.messages.lastOrNull()?.state == MessageState.FAILED -> copy("notice.failed", "执行遇到问题")
                conversation.messages.lastOrNull()?.state == MessageState.CANCELLED -> copy("notice.stopped", "已停止")
                else -> copy("notice.completed", "已完成")
            }
            try { environment.appBroker?.request(AppCommand.Notifications("post", PluginNotification(
                id = "task-$id", title = copy("plugin.title", "AI 助手") + " · " + state, text = conversation.title,
                kind = if (running) PluginNotificationKind.PROGRESS else PluginNotificationKind.MESSAGE,
                ongoing = running, live = running, indeterminate = running, symbol = "robot_2",
                contentAction = PluginNotificationAction(copy("notice.open", "查看会话"), actionId = "notification-open/$id"),
                actions = if (running) listOf(PluginNotificationAction(copy("notice.stop", "停止"), actionId = "notification-stop/$id")) else emptyList()))) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Notification availability never changes model/tool results. */ }
        }
    } }
    fun changed(id: String, running: Boolean) { events.trySend(id to running) }
    suspend fun clear() { environment.appBroker?.request(AppCommand.Notifications("cancel-all")) }
    fun close() { events.close(); scope.cancel() }
}
