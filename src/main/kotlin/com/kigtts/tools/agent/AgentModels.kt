package com.kigtts.tools.agent

import com.kigtts.plugin.api.ChatOption
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.UUID

@Serializable enum class Provider(val label: String, val endpoint: String, val model: String) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
    GLM("GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3"),
    KIMI("Kimi", "https://api.moonshot.ai/v1", "kimi-k3"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", ""),
    SILICONFLOW("硅基流动", "https://api.siliconflow.cn/v1", ""),
    DASHSCOPE("阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    ARK("火山方舟", "https://ark.cn-beijing.volces.com/api/v3", ""),
    MINIMAX("MiniMax", "https://api.minimaxi.com/v1", ""),
    OPENCODE_ZEN("OpenCode Zen", "https://opencode.ai/inference/openai/v1", "glm-5.3-flash-free"),
    OPENCODE_GO("OpenCode Go", "https://opencode.ai/inference/go/openai/v1", "glm-5.3-flash"),
    CUSTOM("自定义（兼容配置）", "", ""),
}
@Serializable data class ProviderProfile(val endpoint: String, val model: String, val models: List<String> = emptyList(), val modelsUpdatedAt: Long = 0, val modelInfo: Map<String, ModelInfo> = emptyMap(), val effort: String? = null,
    val rememberedEfforts: Map<String, String> = emptyMap())
@Serializable data class ModelInfo(val reasoningEfforts: List<String> = emptyList(), val vision: Boolean? = null, val contextTokens: Int? = null)
@Serializable data class CustomProvider(val id: String = UUID.randomUUID().toString(), val name: String = "自定义供应商", val profile: ProviderProfile = ProviderProfile("", ""))
@Serializable data class AgentSettings(val provider: Provider = Provider.DEEPSEEK,
    val profiles: Map<Provider, ProviderProfile> = Provider.entries.associateWith { ProviderProfile(it.endpoint, it.model) },
    val showReasoning: Boolean = false, val access: AgentAccessMode = AgentAccessMode.REQUEST_APPROVAL, val toolsEnabled: Boolean = true,
    val selectedConversation: String? = null, val pinHomeIcon: Boolean = false, val customProviders: List<CustomProvider> = emptyList(), val customProviderId: String? = null,
    val fullAccessConfirmed: Boolean = false, val taskNotifications: Boolean = true)
@Serializable enum class AgentAccessMode { REQUEST_APPROVAL, ASSISTED, FULL }
@Serializable data class ToolCall(val id: String, val name: String, val arguments: String)
@Serializable enum class MessageState { COMPLETE, STREAMING, FAILED, CANCELLED }
@Serializable data class AgentMessage(val id: String = UUID.randomUUID().toString(), val role: String,
    val content: String = "", val reasoning: String = "", val calls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null, val state: MessageState = MessageState.COMPLETE,
    val summary: String = "", val images: List<AgentImage> = emptyList(), val visualInput: Boolean = false, val reasoningDone: Boolean = false,
    val usage: AgentUsage? = null, val options: List<ChatOption> = emptyList())
@Serializable data class AgentImage(val path: String, val mime: String = "image/jpeg", val description: String = "图片")
@Serializable data class AgentQueuedMessage(val id: String = UUID.randomUUID().toString(), val text: String,
    val images: List<AgentImage> = emptyList(), val createdAt: Long = System.currentTimeMillis(), val priority: Boolean = false)
@Serializable data class Conversation(val id: String = UUID.randomUUID().toString(), val title: String = "新会话",
    val messages: List<AgentMessage> = emptyList(), val provider: Provider? = null, val model: String? = null, val customProviderId: String? = null, val reasoningEffort: String? = null,
    val updatedAt: Long = System.currentTimeMillis(), val draftImages: List<AgentImage> = emptyList(), val queued: List<AgentQueuedMessage> = emptyList(),
    val contextSummary: String = "", val compactedThroughId: String? = null, val visionUnavailableModel: String? = null)
@Serializable enum class ReviewKind { QUICK_TEXT, STICKERS, EXTERNAL, TOOL }
@Serializable data class PendingReview(val conversationId: String, val toolCallId: String, val token: String,
    val summary: String, val createdAt: Long = System.currentTimeMillis(), val kind: ReviewKind = ReviewKind.QUICK_TEXT,
    val transactionId: String? = null, val call: ToolCall? = null)

internal fun completionEndpoint(base: String): String {
    val uri = URI(base.trim())
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null) {
        "服务地址需要使用 HTTPS，且不能包含帐号或查询参数"
    }
    return uri.toASCIIString().trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
}

internal fun AgentSettings.profile(): ProviderProfile =
    if (provider == Provider.CUSTOM && customProviderId != null) customProviders.firstOrNull { it.id == customProviderId }?.profile ?: ProviderProfile("", "")
    else requireNotNull(profiles[provider])
internal fun AgentSettings.providerLabel(): String = if (provider == Provider.CUSTOM && customProviderId != null) customProviders.firstOrNull { it.id == customProviderId }?.name ?: "自定义供应商" else provider.label
internal fun AgentSettings.catalogKey() = customProviderId?.takeIf { provider == Provider.CUSTOM } ?: provider.name
internal fun AgentStore.connection(conversation: Conversation): AgentSettings {
    val provider = conversation.provider ?: settings.provider
    val customId = if (provider == Provider.CUSTOM) conversation.customProviderId ?: settings.customProviderId else null
    val chosen = settings.copy(provider = provider, customProviderId = customId)
    val profile = chosen.profile()
    val model = conversation.model ?: profile.model
    return chosen.copy(profiles = chosen.profiles + (provider to profile.copy(model = model,
        effort = (conversation.reasoningEffort ?: profile.rememberedEfforts[model] ?: profile.effort)?.takeUnless { it == "auto" })))
}
internal fun AgentSettings.effectiveProfile(): ProviderProfile = requireNotNull(profiles[provider])
internal fun AgentStore.target(context: com.kigtts.plugin.api.PluginContext): Conversation? {
    val id = context.inputs["_chatConversation"]?.takeIf(String::isNotBlank) ?: return selected()
    return requireNotNull(get(id)) { "此会话已删除，请重新打开会话" }
}
internal fun AgentStore.changeProfile(change: (ProviderProfile) -> ProviderProfile) = updateSettings { settings ->
    if (settings.provider == Provider.CUSTOM && settings.customProviderId != null) settings.copy(customProviders = settings.customProviders.map { if (it.id == settings.customProviderId) it.copy(profile = change(it.profile)) else it })
    else settings.copy(profiles = settings.profiles + (settings.provider to change(requireNotNull(settings.profiles[settings.provider]))))
}

internal fun AgentSettings.configuredConnection() = copy(profiles = profiles + (provider to profile()))
