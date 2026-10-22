package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import java.util.UUID

internal fun providerOptions(store: AgentStore, chat: Boolean): List<PageAction> {
    val prefix = if (chat) "chat-provider/" else "provider/"
    return Provider.entries.filter { it != Provider.CUSTOM || !store.settings.profiles[Provider.CUSTOM]?.endpoint.isNullOrBlank() }.map { PageAction(prefix + it.name, it.label, PluginIcon.NONE) } +
        store.settings.customProviders.map { PageAction(prefix + "CUSTOM/" + it.id, it.name, PluginIcon.NONE) }
}
internal fun providerAction(settings: AgentSettings, chat: Boolean) = (if (chat) "chat-provider/" else "provider/") + settings.provider.name +
    (settings.customProviderId?.takeIf { settings.provider == Provider.CUSTOM }?.let { "/$it" } ?: "")
internal fun modelAction(settings: AgentSettings, model: String, chat: Boolean = false) = (if (chat) "chat-model-select/" else "model-select/") + UUID.nameUUIDFromBytes((settings.catalogKey() + ":" + model).toByteArray())
internal fun modelOptions(store: AgentStore, current: AgentSettings): List<ChatOption> {
    val connections = Provider.entries.filter { it != Provider.CUSTOM }.map { store.settings.copy(provider = it, customProviderId = null).configuredConnection() } +
        store.settings.customProviders.map { store.settings.copy(provider = Provider.CUSTOM, customProviderId = it.id).configuredConnection() }
    return (connections + current).distinctBy { it.catalogKey() }.flatMap { settings ->
        (settings.effectiveProfile().models + settings.effectiveProfile().model + listOfNotNull(current.effectiveProfile().model.takeIf { current.catalogKey() == settings.catalogKey() }))
            .filter { it.isNotBlank() && openCodeChatModel(settings.provider, it) }.distinct().take(80)
            .map { ChatOption(modelAction(settings, it, true), it.take(160), section = settings.providerLabel()) }
    }.take(250) + listOf(ChatOption("chat-models", "管理当前供应商模型…"), ChatOption("chat-models-refresh", "刷新模型列表"), ChatOption("chat-model", "手动填写模型…"))
}
internal fun reasoningControl(settings: AgentSettings): List<ChatControl> {
    val profile = settings.effectiveProfile(); val levels = profile.modelInfo[profile.model]?.reasoningEfforts.orEmpty()
    if (levels.isEmpty()) return emptyList()
    val options = listOf(ChatOption("effort/auto", "自动", "auto")) + levels.map { ChatOption("effort/$it", when (it) { "none" -> "关闭"; "minimal" -> "极低"; "low" -> "低"; "medium" -> "中"; "high" -> "高"; "xhigh" -> "极高"; "max" -> "最高"; else -> it }, it) }
    return listOf(ChatControl("思考强度", options, "effort/" + (profile.effort?.takeIf { it in levels } ?: "auto"), ChatControlKind.REASONING))
}
internal fun modelPickerPage(store: AgentStore): PluginPage {
    val settings = store.modelPickerConversation?.let(store::get)?.let(store::connection) ?: store.settings.configuredConnection()
    val models = (listOf(settings.effectiveProfile().model) + settings.effectiveProfile().models).filter(String::isNotBlank).distinct().filter { it.contains(store.modelFilter, true) }
    val blocks = mutableListOf<PageBlock>(PageBlock.Input("搜索模型", store.modelFilter, "model-search", icon = PluginIcon.NONE))
    store.modelStatus[settings.catalogKey()]?.let { blocks += PageBlock.Notice(it) }
    blocks += PageBlock.Actions(listOf(PageAction("models-refresh", "获取模型列表", PluginIcon.SETTINGS), PageAction("edit-models", "编辑模型列表", PluginIcon.EDIT)) +
        (if (store.modelOffset > 0) listOf(PageAction("models-prev", "上一页")) else emptyList()) + (if (store.modelOffset + 40 < models.size) listOf(PageAction("models-next", "下一页")) else emptyList()))
    return PluginPage("models", settings.providerLabel() + " · 模型", blocks, management = ManagementList("models", models.drop(store.modelOffset).take(40).map {
        ManagementItem(UUID.nameUUIDFromBytes(it.toByteArray()).toString(), it.take(160), icon = PluginIcon.NONE,
            subtitle = if (!openCodeChatModel(settings.provider, it)) "此模型协议尚未支持" else settings.effectiveProfile().modelInfo[it]?.reasoningEfforts?.takeIf { it.isNotEmpty() }?.joinToString(" / ").orEmpty())
    }, emptyText = "尚无模型，可获取或手动编辑列表"))
}
internal fun providersPage(store: AgentStore) = PluginPage("providers", "自定义供应商", listOf(PageBlock.Actions(listOf(PageAction("add-provider", "添加供应商", PluginIcon.ADD)))),
    management = ManagementList("providers", store.settings.customProviders.map { ManagementItem(it.id, it.name, subtitle = it.profile.endpoint, icon = PluginIcon.NONE,
        primaryAction = "edit-provider/" + it.id, actions = listOf(PageAction("remove-provider/" + it.id, "删除", PluginIcon.CLOSE))) }, emptyText = "可添加多个供应商或同一服务的不同账户"))
