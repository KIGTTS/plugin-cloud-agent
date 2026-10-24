package com.kigtts.tools.agent

import com.kigtts.plugin.api.PageAction
import com.kigtts.plugin.api.PageBlock
import com.kigtts.plugin.api.PluginIcon
import com.kigtts.plugin.api.PluginPage

internal data class ProviderTemplate(
    val id: String,
    val label: String,
    val provider: Provider = Provider.CUSTOM,
    val endpoint: String,
    val model: String,
    val group: String,
    val apiFormat: ApiFormat = ApiFormat.CHAT_COMPLETIONS,
    val codexDeviceLogin: Boolean = false,
)

internal val providerTemplates = listOf(
    ProviderTemplate("deepseek", "DeepSeek", endpoint = "https://api.deepseek.com", model = "deepseek-chat", group = "预设供应商"),
    ProviderTemplate("glm-bigmodel", "GLM · BigModel", endpoint = "https://open.bigmodel.cn/api/paas/v4", model = "glm-4.5-air", group = "预设供应商"),
    ProviderTemplate("glm-coding", "GLM · BigModel Coding Plan", endpoint = "https://open.bigmodel.cn/api/coding/paas/v4", model = "glm-4.5-air", group = "预设供应商"),
    ProviderTemplate("glm-zai", "GLM · z.ai", endpoint = "https://api.z.ai/api/paas/v4", model = "glm-4.5-air", group = "预设供应商"),
    ProviderTemplate("glm-zai-coding", "GLM · z.ai Coding Plan", endpoint = "https://api.z.ai/api/coding/paas/v4", model = "glm-4.5-air", group = "预设供应商"),
    ProviderTemplate("kimi", "Kimi", endpoint = "https://api.moonshot.ai/v1", model = "kimi-k2.5", group = "预设供应商"),
    ProviderTemplate("openrouter", "OpenRouter", endpoint = "https://openrouter.ai/api/v1", model = "", group = "预设供应商"),
    ProviderTemplate("siliconflow", "硅基流动", endpoint = "https://api.siliconflow.cn/v1", model = "", group = "预设供应商"),
    ProviderTemplate("aliyun-cn", "阿里云百炼 · 国内", endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1", model = "qwen-plus", group = "预设供应商"),
    ProviderTemplate("aliyun-global", "阿里云百炼 · 国际", endpoint = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", model = "qwen-plus", group = "预设供应商"),
    ProviderTemplate("opencode-zen", "OpenCode Zen", endpoint = Provider.OPENCODE_ZEN.endpoint, model = Provider.OPENCODE_ZEN.model, group = "预设供应商"),
    ProviderTemplate("opencode-go", "OpenCode Go", endpoint = Provider.OPENCODE_GO.endpoint, model = Provider.OPENCODE_GO.model, group = "预设供应商"),
    ProviderTemplate("openai-api", "OpenAI · API 密钥", endpoint = "https://api.openai.com/v1", model = "gpt-4.1-mini", group = "预设供应商"),
    ProviderTemplate("custom", "自定义供应商", endpoint = "", model = "", group = "自定义供应商"),
    ProviderTemplate("codex-device", "Codex · 设备代码登录", endpoint = "https://api.openai.com/v1", model = "gpt-4.1-mini", group = "Codex 设备代码", codexDeviceLogin = true),
)

internal fun providerTemplatesPage(): PluginPage {
    val groups = listOf("自定义供应商", "预设供应商", "Codex 设备代码")
    val blocks = groups.mapNotNull { group -> providerTemplates.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { templates ->
        PageBlock.Group(group, templates.map { template ->
            PageBlock.Actions(listOf(PageAction("add-template/${template.id}", template.label, PluginIcon.ADD)))
        })
    } }
    return PluginPage("provider-templates", "添加供应商", blocks)
}
