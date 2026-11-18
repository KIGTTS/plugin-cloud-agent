package com.kigtts.tools.agent

import com.kigtts.plugin.api.*

internal fun agentPage(id: String, store: AgentStore, runner: AgentRunner, secrets: AgentSecrets, context: PluginContext = PluginContext("", ""), copy: (String, String) -> String = { _, fallback -> fallback }): PluginPage? {
    val settings = store.settings
    return when (id) {
        "chat" -> {
            val conversation = store.selected()
            val blocks = mutableListOf<PageBlock>(PageBlock.Actions(listOf(
                PageAction("conversations", copy("chat.history", "历史会话"), PluginIcon.NONE, symbol = "history"),
                PageAction("new", copy("chat.new", "新会话"), PluginIcon.ADD), PageAction("settings", copy("entry.settings", "助手设置"), PluginIcon.SETTINGS))))
            blocks += agentChat(store, runner, secrets, context, copy)
            PluginPage(id, conversation?.title ?: copy("plugin.title", "AI 助手"), blocks, onEnterAction = conversation?.let { "chat-viewed/${it.id}" })
        }
        "conversations" -> PluginPage(id, copy("chat.list", "会话"), listOf(PageBlock.Actions(listOf(PageAction("new", copy("chat.new", "新会话"), PluginIcon.ADD)))),
            management = ManagementList("conversations", store.list().map { conversation ->
                ManagementItem(conversation.id, conversation.title,
                    subtitle = (when {
                        runner.isRunning(conversation.id) -> copy("chat.running", "正在执行") + " · "
                        store.pendingFor(conversation.id) != null -> copy("chat.waiting", "等待处理") + " · "
                        else -> ""
                    }) + copy("chat.messages", "{count} 条消息").replace("{count}", conversation.messages.count { it.role == "user" && !it.visualInput }.toString()), icon = PluginIcon.TEXT,
                    selected = settings.selectedConversation == conversation.id, primaryAction = "select/${conversation.id}",
                    actions = listOf(PageAction("export-conversation/${conversation.id}", copy("chat.export", "导出会话"), PluginIcon.EXPORT),
                        PageAction("rename/${conversation.id}", copy("chat.rename", "重命名"), PluginIcon.EDIT), PageAction("delete/${conversation.id}", copy("chat.delete", "删除"), PluginIcon.CLOSE)))
            }, emptyText = copy("chat.empty", "创建会话，开始聊天")))
        "models" -> modelPickerPage(store, secrets, copy)
        "providers" -> providersPage(store, copy)
        "provider-templates" -> providerTemplatesPage(copy)
        else -> agentSettingsPage(id, store, secrets, copy)
    }
}
internal fun clippedMessage(text: String, limit: Int) = if (text.length <= limit) text else "较长内容仅显示结尾，可导出完整会话\n\n" + text.takeLast(limit)
/** Snapshots include several pages and must stay below the SDK's 512 KiB Binder budget. */
internal fun visibleMessageCost(message: AgentMessage, calls: Map<String, ToolCall>): Int =
    message.content.takeLast(16 * 1024).length + message.reasoning.takeLast(8 * 1024).length + message.summary.length +
        calls[message.toolCallId]?.arguments.orEmpty().take(8000).length + message.images.sumOf { it.path.length + it.description.length } +
        message.files.sumOf { it.path.length + it.name.length + it.mime.length + 80 } + 128
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
