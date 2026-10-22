package com.kigtts.tools.agent

import com.kigtts.plugin.api.*

internal fun agentPage(id: String, store: AgentStore, runner: AgentRunner, secrets: AgentSecrets): PluginPage? {
    val settings = store.settings
    return when (id) {
        "chat" -> {
            val conversation = store.selected()
            val blocks = mutableListOf<PageBlock>(PageBlock.Actions(listOf(
                PageAction("conversations", "历史会话", PluginIcon.NONE, symbol = "history"),
                PageAction("new", "新会话", PluginIcon.ADD), PageAction("settings", "助手设置", PluginIcon.SETTINGS))))
            blocks += agentChat(store, runner)
            PluginPage(id, conversation?.title ?: "AI 助手", blocks)
        }
        "conversations" -> PluginPage(id, "会话", listOf(PageBlock.Actions(listOf(PageAction("new", "新会话", PluginIcon.ADD)))),
            management = ManagementList("conversations", store.list().map { conversation ->
                ManagementItem(conversation.id, conversation.title,
                    subtitle = (when {
                        runner.isRunning(conversation.id) -> "正在执行 · "
                        store.pendingFor(conversation.id) != null -> "等待处理 · "
                        else -> ""
                    }) + "${conversation.messages.count { it.role == "user" && !it.visualInput }} 条消息", icon = PluginIcon.TEXT,
                    selected = settings.selectedConversation == conversation.id, primaryAction = "select/${conversation.id}",
                    actions = listOf(PageAction("export-conversation/${conversation.id}", "导出会话", PluginIcon.EXPORT),
                        PageAction("rename/${conversation.id}", "重命名", PluginIcon.EDIT), PageAction("delete/${conversation.id}", "删除", PluginIcon.CLOSE)))
            }, emptyText = "创建会话，开始聊天"))
        "models" -> modelPickerPage(store)
        "providers" -> providersPage(store)
        else -> agentSettingsPage(id, store, secrets)
    }
}
internal fun clippedMessage(text: String, limit: Int) = if (text.length <= limit) text else "较长内容仅显示结尾，可导出完整会话\n\n" + text.takeLast(limit)
/** Snapshots include several pages and must stay below the SDK's 512 KiB Binder budget. */
internal fun visibleMessageCost(message: AgentMessage, calls: Map<String, ToolCall>): Int =
    message.content.takeLast(16 * 1024).length + message.reasoning.takeLast(8 * 1024).length + message.summary.length +
        calls[message.toolCallId]?.arguments.orEmpty().take(8000).length + message.images.sumOf { it.path.length + it.description.length } + 128
internal fun visibleMessages(messages: List<AgentMessage>, maximum: Int = 40 * 1024,
    calls: Map<String, ToolCall> = messages.flatMap { it.calls }.associateBy { it.id }): List<AgentMessage> {
    var budget = maximum
    return messages.takeLast(100).asReversed().takeWhile { message ->
        budget -= visibleMessageCost(message, calls)
        budget >= 0
    }.asReversed()
}
internal fun toolSummary(content: String): String = when {
    content.contains("CONFIRMATION_REQUIRED") || content.contains("\"pending\":true") || content.contains("\"requiresConfirmation\":true") -> "等待你审核操作"
    content.contains("\"type\":\"prepared\"") -> "修改已准备好"
    content.contains("discarded_after_user_review") || content.contains("\"type\":\"discarded\"") -> "操作已取消"
    content.contains("OUTCOME_UNKNOWN") || content.contains("outcome_unknown") -> "操作状态待确认"
    content.contains("\"error\"") || content.contains("\"type\":\"failure\"") -> "操作未完成"
    content.contains("not_executed_pending_review") -> "后续操作已暂缓"
    else -> "已完成操作"
}
