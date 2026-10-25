package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.serialization.json.*

internal suspend fun registerAgentOverlay(environment: PluginEnvironment, requestAdd: Boolean): Boolean {
    val broker = environment.appBroker ?: return false
    if (!requestAdd) {
        val status = broker.request(AppCommand.Overlay()) as? AppReply.Data ?: return false
        if (status.value["authorized"]?.jsonPrimitive?.booleanOrNull != true) return false
    }
    return broker.request(AppCommand.Overlay(if (requestAdd) "request-add" else "register",
        OverlayEntrance("assistant", "AI 助手", "smart_toy", actionId = "open"))) is AppReply.Data
}
