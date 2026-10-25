package com.kigtts.tools.agent

import com.kigtts.plugin.api.*

/** Provider/model configuration stays in the plugin; host renders a native dropdown and editor pages. */
internal class AgentProviderController(private val store: AgentStore, private val secrets: AgentSecrets,
    private val runner: AgentRunner, private val catalog: AgentModelCatalog) {
    private fun pickerConnection() = store.modelPickerConversation?.let(store::get)?.let(store::connection) ?: store.settings.configuredConnection()
    fun handle(action: String, context: PluginContext): PluginOutcome? {
        val value = context.inputs["value"].orEmpty()
        when {
            action.startsWith("configure-provider/") -> {
                val parts = action.substringAfter('/').split('/'); val provider = Provider.valueOf(parts[0]); val custom = parts.getOrNull(1)
                require(custom == null || provider == Provider.CUSTOM && store.settings.customProviders.any { it.id == custom })
                store.updateSettings { it.copy(provider = provider, customProviderId = custom) }
                store.modelPickerConversation = null
                return PluginOutcome.OpenPage("connection-settings")
            }
            action.startsWith("provider/") || action.startsWith("chat-provider/") -> {
                val chat = action.startsWith("chat-"); val parts = action.substringAfter('/').split('/'); val provider = Provider.valueOf(parts[0]); val custom = parts.getOrNull(1)
                require(custom == null || provider == Provider.CUSTOM && store.settings.customProviders.any { it.id == custom })
                if (chat) { val conversation = store.target(context) ?: store.create(); store.modify(conversation.id) { it.copy(provider = provider, customProviderId = custom, model = null, reasoningEffort = null) }
                    catalog.refresh(store.connection(requireNotNull(store.get(conversation.id))))
                } else { store.updateSettings { it.copy(provider = provider, customProviderId = custom) }; catalog.refresh(store.settings.configuredConnection()) }
                return PluginOutcome.RefreshUi
            }
            action == "providers" -> return PluginOutcome.OpenPage("providers")
            action == "add-provider" -> { require(store.settings.customProviders.size < 20); return PluginOutcome.OpenPage("provider-templates") }
            action.startsWith("add-template/") -> {
                require(store.settings.customProviders.size < 20)
                val template = providerTemplates.firstOrNull { it.id == action.substringAfter('/') } ?: error("供应商模板不存在")
                if (template.id == "custom") return PluginOutcome.TextInput("供应商名称", "add-provider-save")
                val custom = CustomProvider(name = uniqueProviderName(template.label, store.settings.customProviders), profile = ProviderProfile(template.endpoint, template.model, apiFormat = template.apiFormat, codexDeviceLogin = template.codexDeviceLogin))
                store.updateSettings { it.copy(provider = Provider.CUSTOM, customProviderId = custom.id, customProviders = it.customProviders + custom) }
                return if (template.codexDeviceLogin) PluginOutcome.TextInput("Codex 设备代码登录令牌", "codex-device-save/${custom.id}", secret = true)
                else PluginOutcome.OpenPage("connection-settings")
            }
            action == "add-provider-save" -> {
                require(value.trim().length in 1..80 && store.settings.customProviders.size < 20)
                val custom = CustomProvider(name = uniqueProviderName(value, store.settings.customProviders))
                store.updateSettings { it.copy(provider = Provider.CUSTOM, customProviderId = custom.id, customProviders = it.customProviders + custom) }
                return PluginOutcome.OpenPage("connection-settings")
            }
            action.startsWith("codex-device-save/") -> {
                val id = action.substringAfter('/'); require(store.settings.customProviders.any { it.id == id })
                require(value.trim().isNotBlank())
                secrets.writeCustom(id, value.trim())
                return PluginOutcome.OpenPage("connection-settings")
            }
            action.startsWith("edit-provider/") -> {
                val id = action.substringAfter('/'); require(store.settings.customProviders.any { it.id == id })
                store.updateSettings { it.copy(provider = Provider.CUSTOM, customProviderId = id) }; return PluginOutcome.OpenPage("connection-settings")
            }
            action.startsWith("remove-provider/") -> return PluginOutcome.Menu("删除此供应商配置？", listOf(PageAction("remove-provider-confirm/" + action.substringAfter('/'), "删除配置和密钥", PluginIcon.CLOSE)))
            action.startsWith("remove-provider-confirm/") -> {
                val id = action.substringAfter('/'); require(store.settings.customProviders.any { it.id == id })
                check(store.list().none { runner.isRunning(it.id) && store.connection(it).customProviderId == id }) { "请先停止正在使用此供应商的回答" }
                secrets.writeCustom(id, "")
                store.updateSettings { it.copy(customProviders = it.customProviders.filterNot { custom -> custom.id == id }, provider = if (it.customProviderId == id) Provider.DEEPSEEK else it.provider, customProviderId = it.customProviderId?.takeUnless { selected -> selected == id }) }
                return PluginOutcome.RefreshUi
            }
            action == "provider-name" -> return PluginOutcome.TextInput("供应商名称", "save-provider-name", store.settings.providerLabel())
            action == "save-provider-name" -> {
                require(value.trim().length in 1..80); val id = requireNotNull(store.settings.customProviderId)
                require(store.settings.customProviders.none { it.id != id && it.name.trim().equals(value.trim(), true) }) { "已有同名供应商，请换个名称" }
                store.updateSettings { it.copy(customProviders = it.customProviders.map { custom -> if (custom.id == id) custom.copy(name = value.trim()) else custom }) }; return PluginOutcome.RefreshUi
            }
            action in setOf("endpoint", "key") -> {
                val profile = store.settings.profile()
                return PluginOutcome.TextInput(if (action == "endpoint") "服务地址" else "密钥", "save-$action", if (action == "endpoint") profile.endpoint else "", secret = action == "key")
            }
            action in setOf("save-endpoint", "save-key") -> {
                check(store.list().none { runner.isRunning(it.id) && store.connection(it).catalogKey() == store.settings.catalogKey() }) { "请先停止正在使用此连接的回答" }
                when (action) {
                    "save-key" -> secrets.write(store.settings, value)
                    "save-endpoint" -> { completionEndpoint(value.trim()); store.changeProfile { it.copy(endpoint = value.trim(), modelsUpdatedAt = 0) } }
                }
                catalog.refresh(store.settings.configuredConnection(), true)
                return PluginOutcome.RefreshUi
            }
            action.startsWith("api-format/") -> {
                val format = runCatching { ApiFormat.valueOf(action.substringAfter('/')) }.getOrElse { error("API 格式不受支持") }
                store.changeProfile { it.copy(apiFormat = format, modelsUpdatedAt = 0) }
                catalog.refresh(store.settings.configuredConnection(), true)
                return PluginOutcome.RefreshUi
            }
            action == "chat-models" || action == "models" -> {
                store.modelPickerConversation = if (action == "chat-models") (store.target(context) ?: store.create()).id else null
                store.modelFilter = ""; store.modelOffset = 0; catalog.refresh(pickerConnection()); return PluginOutcome.OpenPage("models")
            }
            action == "chat-models-refresh" -> { val conversation = store.selected() ?: store.create(); catalog.refresh(store.connection(conversation), true); return PluginOutcome.RefreshUi }
            action == "models-refresh" -> { catalog.refresh(pickerConnection(), true); return PluginOutcome.RefreshUi }
            action == "models-manage" -> return PluginOutcome.OpenPage("connections")
            action == "models-next" -> { store.modelOffset += 40; return PluginOutcome.RefreshUi }
            action == "models-prev" -> { store.modelOffset = (store.modelOffset - 40).coerceAtLeast(0); return PluginOutcome.RefreshUi }
            action == "model-search" -> return PluginOutcome.TextInput("搜索模型", "model-search-save", store.modelFilter)
            action == "model-search-save" -> { store.modelFilter = value.take(160); store.modelOffset = 0; return PluginOutcome.RefreshUi }
            action.startsWith("effort/") -> {
                val conversation = store.target(context) ?: store.create(); val connection = store.connection(conversation)
                val effort = action.substringAfter('/'); val profile = connection.effectiveProfile()
                require(effort == "auto" || effort in profile.modelInfo[profile.model]?.reasoningEfforts.orEmpty())
                store.modify(conversation.id) { it.copy(reasoningEffort = effort) }
                store.rememberEffort(connection, profile.model, effort); return PluginOutcome.RefreshUi
            }
            action.startsWith("model-select/") || action.startsWith("chat-model-select/") -> {
                val chat = action.startsWith("chat-")
                val target = store.modelPickerConversation?.let(store::get) ?: if (chat) store.target(context) else null
                var settings = target?.let(store::connection) ?: store.settings.configuredConnection()
                val candidates = availableModels(store, secrets, settings)
                val match = candidates.firstOrNull { modelAction(it.settings, it.id, chat) == action }?.let { it.settings to it.id } ?: error("模型列表已变化，请重新选择")
                settings = match.first; val chosen = match.second
                if (target != null) {
                    val same = store.connection(target).catalogKey() == settings.catalogKey() && target.model == chosen
                    store.modify(target.id) { it.copy(provider = settings.provider, customProviderId = settings.customProviderId, model = chosen,
                        reasoningEffort = if (same) it.reasoningEffort else null) }
                    return PluginOutcome.OpenPage("chat")
                }
                store.updateSettings { current ->
                    if (settings.provider == Provider.CUSTOM && settings.customProviderId != null) current.copy(customProviders = current.customProviders.map { if (it.id == settings.customProviderId) it.copy(profile = it.profile.copy(model = chosen)) else it })
                    else current.copy(profiles = current.profiles + (settings.provider to requireNotNull(current.profiles[settings.provider]).copy(model = chosen)))
                }
                return PluginOutcome.RefreshUi
            }
        }
        return null
    }
}
internal fun updateModelList(store: AgentStore, settings: AgentSettings, models: List<String>, info: Map<String, ModelInfo> = emptyMap()) {
    store.updateSettings { current ->
        if (settings.provider == Provider.CUSTOM && settings.customProviderId != null) current.copy(customProviders = current.customProviders.map { if (it.id == settings.customProviderId) it.copy(profile = it.profile.copy(models = models, modelsUpdatedAt = System.currentTimeMillis(), modelInfo = it.profile.modelInfo.filterKeys { it in models } + info)) else it })
        else current.copy(profiles = current.profiles + (settings.provider to requireNotNull(current.profiles[settings.provider]).let { it.copy(models = models, modelsUpdatedAt = System.currentTimeMillis(), modelInfo = it.modelInfo.filterKeys { it in models } + info) }))
    }
}
