package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Task events are serialized independently of streamed tokens and model requests. Denial never stops a task. */
internal class AgentTaskNotifications(private val store: AgentStore, private val environment: PluginEnvironment,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val events = Channel<Pair<String, Boolean>>(200)
    init { scope.launch {
        for ((id, running) in events) {
            if (!store.settings.taskNotifications) continue
            val conversation = store.get(id) ?: continue
            val state = when {
                running -> "正在执行"
                store.pendingFor(id) != null -> "等待你的确认"
                conversation.messages.lastOrNull()?.state == MessageState.FAILED -> "执行遇到问题"
                conversation.messages.lastOrNull()?.state == MessageState.CANCELLED -> "已停止"
                else -> "已完成"
            }
            try { environment.appBroker?.request(AppCommand.Notifications("post", PluginNotification(
                id = "task-$id", title = "AI 助手 · $state", text = conversation.title,
                kind = if (running) PluginNotificationKind.PROGRESS else PluginNotificationKind.MESSAGE,
                ongoing = running, live = running, indeterminate = running, symbol = "robot_2",
                contentAction = PluginNotificationAction("查看会话", actionId = "notification-open/$id"),
                actions = if (running) listOf(PluginNotificationAction("停止", actionId = "notification-stop/$id")) else emptyList()))) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Notification availability never changes model/tool results. */ }
        }
    } }
    fun changed(id: String, running: Boolean) { events.trySend(id to running) }
    suspend fun clear() { environment.appBroker?.request(AppCommand.Notifications("cancel-all")) }
    fun close() { events.close(); scope.cancel() }
}
