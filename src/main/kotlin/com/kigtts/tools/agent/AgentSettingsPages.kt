package com.kigtts.tools.agent

import com.kigtts.plugin.api.*

/** Settings edit connection catalogues; choosing the active model belongs to the conversation dialog. */
internal fun agentSettingsPage(id: String, store: AgentStore, secrets: AgentSecrets): PluginPage? {
    val settings = store.settings
    return when (id) {
        "settings" -> PluginPage(id, "助手设置", listOf(
            PageBlock.Group("模型服务", listOf(PageBlock.Actions(listOf(PageAction("connections", "供应商与模型", PluginIcon.SETTINGS))))),
            PageBlock.Group("使用体验", listOf(
                PageBlock.Toggle("任务通知", settings.taskNotifications, "task-notifications", "查看任务进度和结果，点按通知返回会话", PluginIcon.NONE),
                PageBlock.Toggle("常驻主页图标", settings.pinHomeIcon, "pin-home-icon", "任务结束后仍在主页显示助手按钮", PluginIcon.ROBOT2),
                PageBlock.Toggle("显示思考过程", settings.showReasoning, "reasoning", "展开查看模型提供的思考过程", PluginIcon.TEXT),
                PageBlock.Toggle("允许操作软件", settings.toolsEnabled, "tools", "在会话输入框上方切换审批模式", PluginIcon.SETTINGS)))
        ))
        "connections" -> PluginPage(id, "供应商管理", listOf(PageBlock.Actions(listOf(PageAction("add-provider", "添加自定义供应商", PluginIcon.ADD)))),
            management = ManagementList("connections", (Provider.entries.filter { it != Provider.CUSTOM || settings.profiles[it]?.endpoint?.isNotBlank() == true }.map { provider ->
                val connection = settings.copy(provider = provider, customProviderId = null).configuredConnection()
                ManagementItem(provider.name, provider.label, subtitle = if (secrets.exists(connection)) "已配置密钥 · ${connection.effectiveProfile().models.size} 个模型" else "未配置密钥", icon = PluginIcon.NONE,
                    primaryAction = "configure-provider/" + provider.name)
            } + settings.customProviders.map { custom -> ManagementItem(custom.id, custom.name, subtitle = custom.profile.endpoint, icon = PluginIcon.NONE,
                primaryAction = "configure-provider/CUSTOM/" + custom.id, actions = listOf(PageAction("remove-provider/" + custom.id, "删除", PluginIcon.CLOSE))) })))
        "connection-settings" -> PluginPage(id, settings.providerLabel(), listOf(
            PageBlock.Group("连接", (if (settings.customProviderId != null) listOf(PageBlock.Input("名称", settings.providerLabel(), "provider-name", icon = PluginIcon.NONE)) else emptyList()) + listOf(
                PageBlock.Input("服务地址", settings.profile().endpoint, "endpoint"),
                PageBlock.Input("密钥", if (secrets.exists(settings)) "已设置" else "未设置", "key", secret = true))),
            PageBlock.Group("模型", listOf(PageBlock.Actions(listOf(PageAction("models", "管理模型列表", PluginIcon.TEXT))))),
            PageBlock.Text("在会话输入框上方打开模型弹窗，选择本次会话的模型和思考强度。")) +
                if (settings.provider in setOf(Provider.OPENCODE_ZEN, Provider.OPENCODE_GO)) listOf(PageBlock.Notice("会话中仅显示目前支持的 OpenCode 模型。其它模型仍保留在管理列表中。")) else emptyList())
        "dialogue-settings" -> PluginPage(id, "对话与主页入口", listOf(PageBlock.Group("对话", listOf(
            PageBlock.Toggle("常驻主页图标", settings.pinHomeIcon, "pin-home-icon", "任务结束后仍在主页显示助手按钮", PluginIcon.ROBOT2),
            PageBlock.Toggle("显示思考过程", settings.showReasoning, "reasoning", "展开查看模型提供的思考过程", PluginIcon.TEXT),
            PageBlock.Toggle("允许操作软件", settings.toolsEnabled, "tools", "批准模式和完全访问在会话输入框上方切换", PluginIcon.SETTINGS)))))
        else -> null
    }
}
