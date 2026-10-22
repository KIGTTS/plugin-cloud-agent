package com.kigtts.tools.agent

/** Official endpoint tables checked 2026-10-21. Other protocols stay visible in management only. */
internal fun openCodeChatModel(provider: Provider, model: String): Boolean {
    if (provider !in setOf(Provider.OPENCODE_ZEN, Provider.OPENCODE_GO)) return true
    val shared = setOf("glm-5.3-flash", "glm-5.3", "glm-5.2", "kimi-k3", "kimi-k2.7-code", "deepseek-v4.1-flash", "deepseek-v4-pro",
        "minimax-m3.1-flash-free", "longcat-2.5-preview-free", "beam-free")
    return model in shared || model in if (provider == Provider.OPENCODE_ZEN) setOf("qwen3.8-max", "minimax-m3", "minimax-m2.7", "minimax-m2.5", "glm-5.1", "glm-5", "kimi-k2.5", "kimi-k2.6", "mistral-large-4",
        "big-pickle", "glm-5.3-flash-free", "deepseek-v4.1-flash-free", "ll-alpha-free", "exo-free", "mimo-v2.6-flash-free", "mimo-v2.5-free", "nemotron-3-ultra-free", "nemotron-3.5-lightning-free", "nemotron-3.5-super-vl-free")
        else setOf("longcat-2.0", "mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.5", "mimo-v2.5-pro", "hy4-preview", "hy3", "step-5-preview")
}
internal fun openCodeCatalogEndpoint(base: String): String? = when (base.trimEnd('/')) {
    Provider.OPENCODE_ZEN.endpoint -> "https://opencode.ai/inference/v1/models"
    Provider.OPENCODE_GO.endpoint -> "https://opencode.ai/inference/go/v1/models"
    else -> null
}
