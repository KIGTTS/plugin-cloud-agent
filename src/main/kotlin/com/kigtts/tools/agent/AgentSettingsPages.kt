package com.kigtts.tools.agent

import com.kigtts.plugin.api.*

/** Settings edit connection catalogues; choosing the active model belongs to the conversation dialog. */
internal fun agentSettingsPage(id: String, store: AgentStore, secrets: AgentSecrets, copy: (String, String) -> String = { _, fallback -> fallback }): PluginPage? {
    val settings = store.settings
    return when (id) {
        "settings" -> PluginPage(id, copy("entry.settings", "助手设置"), listOf(
            PageBlock.Group(copy("settings.models", "模型服务"), listOf(PageBlock.Actions(listOf(PageAction("connections", copy("settings.connections", "供应商与模型"), PluginIcon.SETTINGS))))),
            PageBlock.Group(copy("settings.internet", "联网"), listOf(PageBlock.Actions(listOf(PageAction("search-settings", copy("settings.search", "搜索与网页读取"), PluginIcon.NONE, symbol = "manage_search"))))),
            PageBlock.Group(copy("settings.shortcuts", "快捷入口"), listOf(PageBlock.Actions(listOf(PageAction("overlay-add", copy("settings.overlay", "添加到悬浮窗"), PluginIcon.NONE, symbol = "bubble_chart"))))),
            PageBlock.Group(copy("settings.experience", "使用体验"), listOf(
                PageBlock.Toggle(copy("settings.notifications", "任务通知"), settings.taskNotifications, "task-notifications", copy("settings.notifications.help", "查看任务进度和结果，点按通知返回会话"), PluginIcon.NONE),
                PageBlock.Toggle(copy("settings.pin", "常驻主页图标"), settings.pinHomeIcon, "pin-home-icon", copy("settings.pin.help", "任务结束后仍在主页显示助手按钮"), PluginIcon.ROBOT2),
                PageBlock.Toggle(copy("settings.reasoning", "显示思考过程"), settings.showReasoning, "reasoning", copy("settings.reasoning.help", "展开查看模型提供的思考过程"), PluginIcon.TEXT),
                PageBlock.Toggle(copy("settings.actions", "允许操作软件"), settings.toolsEnabled, "tools", copy("settings.actions.help", "在输入框下方切换审批模式"), PluginIcon.SETTINGS)))
        ))
        "connections" -> PluginPage(id, copy("settings.connections", "供应商与模型"), listOf(PageBlock.Actions(listOf(PageAction("add-provider", copy("settings.add_provider", "添加供应商"), PluginIcon.ADD)))),
            management = ManagementList("connections", (Provider.entries.filter { provider ->
                provider != Provider.CUSTOM && runCatching {
                    val connection = settings.copy(provider = provider, customProviderId = null).configuredConnection()
                    secrets.exists(connection) && connection.effectiveProfile().models.isNotEmpty()
                }.getOrDefault(false)
            }.map { provider ->
                val connection = settings.copy(provider = provider, customProviderId = null).configuredConnection()
                ManagementItem(provider.name, provider.label, subtitle = if (secrets.exists(connection)) copy("settings.key_models", "已配置密钥 · {count} 个模型").replace("{count}", connection.effectiveProfile().models.size.toString()) else copy("settings.no_key", "未配置密钥"), icon = PluginIcon.NONE,
                    primaryAction = "configure-provider/" + provider.name)
            } + settings.customProviders.map { custom -> ManagementItem(custom.id, custom.name, subtitle = custom.profile.endpoint, icon = PluginIcon.NONE,
                primaryAction = "configure-provider/CUSTOM/" + custom.id, actions = listOf(PageAction("remove-provider/" + custom.id, copy("chat.delete", "删除"), PluginIcon.CLOSE))) })))
        "connection-settings" -> PluginPage(id, settings.providerLabel(), listOf(
            PageBlock.Group(copy("settings.connection", "连接"), (if (settings.customProviderId != null) listOf(PageBlock.Input(copy("settings.name", "名称"), settings.providerLabel(), "provider-name", icon = PluginIcon.NONE)) else emptyList()) + listOf(
                PageBlock.Input(copy("settings.endpoint", "服务地址"), settings.profile().endpoint, "endpoint"),
                PageBlock.Input(copy("settings.key", "密钥"), if (secrets.exists(settings)) "••••••••" else "", "key", secret = true))),
            PageBlock.Group(copy("settings.model", "模型"), listOf(PageBlock.Choice(copy("settings.api_format", "API 格式"), ApiFormat.entries.map { PageAction("api-format/${it.name}", when (it) {
                ApiFormat.ANTHROPIC_MESSAGES -> "Messages"; ApiFormat.CHAT_COMPLETIONS -> "Chat Completions"; ApiFormat.RESPONSES -> "Responses"
            }) }, "api-format/${settings.profile().apiFormat.name}", PluginIcon.SETTINGS),
                PageBlock.Actions(listOf(PageAction("models", copy("settings.manage_models", "管理模型"), PluginIcon.NONE, symbol = "tune"))))),
            PageBlock.Text(copy("settings.choose_model", "在会话输入框下方选择本次会话的模型和思考强度"))) +
                if (settings.provider in setOf(Provider.OPENCODE_ZEN, Provider.OPENCODE_GO)) listOf(PageBlock.Notice(copy("settings.opencode", "会话只显示当前支持的 OpenCode 模型。其它模型仍保留在管理列表"))) else emptyList())
        "dialogue-settings" -> PluginPage(id, copy("settings.dialogue", "对话与主页入口"), listOf(PageBlock.Group(copy("settings.chat_group", "对话"), listOf(
            PageBlock.Toggle(copy("settings.pin", "常驻主页图标"), settings.pinHomeIcon, "pin-home-icon", copy("settings.pin.help", "任务结束后仍在主页显示助手按钮"), PluginIcon.ROBOT2),
            PageBlock.Toggle(copy("settings.reasoning", "显示思考过程"), settings.showReasoning, "reasoning", copy("settings.reasoning.help", "展开查看模型提供的思考过程"), PluginIcon.TEXT),
            PageBlock.Toggle(copy("settings.actions", "允许操作软件"), settings.toolsEnabled, "tools", copy("settings.actions.help", "在输入框下方切换审批模式"), PluginIcon.SETTINGS)))))
        else -> null
    }
}
