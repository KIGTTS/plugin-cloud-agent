package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import java.util.UUID

internal fun providerOptions(store: AgentStore, chat: Boolean, secrets: AgentSecrets? = null): List<PageAction> {
    val prefix = if (chat) "chat-provider/" else "provider/"
    val configured = Provider.entries.filter { it != Provider.CUSTOM && (secrets == null || runCatching {
        val settings = store.settings.copy(provider = it, customProviderId = null).configuredConnection()
        secrets.exists(settings) && settings.effectiveProfile().models.isNotEmpty()
    }.getOrDefault(false)) }
    val options = configured.map { PageAction(prefix + it.name, it.label, PluginIcon.NONE) } +
        store.settings.customProviders.filter { custom -> secrets == null || runCatching {
            val settings = store.settings.copy(provider = Provider.CUSTOM, customProviderId = custom.id).configuredConnection()
            secrets.exists(settings) && settings.effectiveProfile().models.isNotEmpty()
        }.getOrDefault(false) }.map { PageAction(prefix + "CUSTOM/" + it.id, it.name, PluginIcon.NONE) }
    return options.ifEmpty { listOf(PageAction("settings", "添加供应商", PluginIcon.ADD)) }
}
internal fun providerAction(settings: AgentSettings, chat: Boolean) = (if (chat) "chat-provider/" else "provider/") + settings.provider.name +
    (settings.customProviderId?.takeIf { settings.provider == Provider.CUSTOM }?.let { "/$it" } ?: "")
internal fun modelAction(settings: AgentSettings, model: String, chat: Boolean = false) = (if (chat) "chat-model-select/" else "model-select/") + UUID.nameUUIDFromBytes((settings.catalogKey() + ":" + model).toByteArray())
internal fun modelOptions(store: AgentStore, current: AgentSettings, secrets: AgentSecrets? = null): List<ChatOption> {
    val models = if (secrets == null) {
        providerConnections(store).flatMap { settings -> settings.effectiveProfile().models.filter { it.isNotBlank() && openCodeChatModel(settings.provider, it) }.map { AvailableModel(settings, it) } }
    } else availableModels(store, secrets, current)
    return models.distinctBy { it.settings.catalogKey() + ":" + it.id }.take(250).map {
        ChatOption(modelAction(it.settings, it.id, true), it.id.take(160), section = it.settings.providerLabel())
    } + listOf(ChatOption("chat-models", "管理模型"), ChatOption("chat-models-refresh", "刷新模型列表"))
}
internal fun reasoningControl(settings: AgentSettings): List<ChatControl> {
    val profile = settings.effectiveProfile(); val levels = profile.modelInfo[profile.model]?.reasoningEfforts.orEmpty()
    if (levels.isEmpty()) return emptyList()
    val options = listOf(ChatOption("effort/auto", "自动", "auto")) + levels.map { ChatOption("effort/$it", when (it) { "none" -> "关闭"; "minimal" -> "极低"; "low" -> "低"; "medium" -> "中"; "high" -> "高"; "xhigh" -> "极高"; "max" -> "最高"; else -> it }, it) }
    return listOf(ChatControl("思考强度", options, "effort/" + (profile.effort?.takeIf { it in levels } ?: "auto"), ChatControlKind.REASONING))
}
internal fun modelPickerPage(store: AgentStore, secrets: AgentSecrets? = null): PluginPage {
    val settings = store.modelPickerConversation?.let(store::get)?.let(store::connection) ?: store.settings.configuredConnection()
    val models = if (secrets != null) {
        availableModels(store, secrets, settings).filter {
            store.modelPickerConversation != null || it.settings.catalogKey() == settings.catalogKey()
        }.map { it.settings to it.id }
    } else {
        settings.effectiveProfile().models.map { settings to it }
    }.filter { it.second.contains(store.modelFilter, true) }.distinctBy { it.first.catalogKey() + ":" + it.second }
    val blocks = mutableListOf<PageBlock>(PageBlock.Input("搜索模型", store.modelFilter, "model-search", icon = PluginIcon.NONE,
        trailingAction = PageAction("models-refresh", "刷新模型列表", PluginIcon.NONE, symbol = "refresh")))
    store.modelStatus[settings.catalogKey()]?.let { blocks += PageBlock.Notice(it) }
    blocks += PageBlock.Actions(listOf(PageAction("models-manage", "管理模型", PluginIcon.NONE, symbol = "tune")) +
        (if (store.modelOffset > 0) listOf(PageAction("models-prev", "上一页")) else emptyList()) + (if (store.modelOffset + 40 < models.size) listOf(PageAction("models-next", "下一页")) else emptyList()))
    return PluginPage("models", "选择模型", blocks, management = ManagementList("models", models.drop(store.modelOffset).take(40).map { (candidate, model) ->
        ManagementItem(UUID.nameUUIDFromBytes((candidate.catalogKey() + ":" + model).toByteArray()).toString(), model.take(160), icon = PluginIcon.NONE,
            subtitle = candidate.providerLabel(), primaryAction = modelAction(candidate, model, store.modelPickerConversation != null))
    }, emptyText = "尚无可用模型，请先配置供应商并刷新列表"))
}
internal fun providersPage(store: AgentStore) = PluginPage("providers", "自定义供应商", listOf(PageBlock.Actions(listOf(PageAction("add-provider", "添加供应商", PluginIcon.ADD)))),
    management = ManagementList("providers", store.settings.customProviders.map { ManagementItem(it.id, it.name, subtitle = it.profile.endpoint, icon = PluginIcon.NONE,
        primaryAction = "edit-provider/" + it.id, actions = listOf(PageAction("remove-provider/" + it.id, "删除", PluginIcon.CLOSE))) }, emptyText = "可添加多个供应商或同一服务的不同账户"))
