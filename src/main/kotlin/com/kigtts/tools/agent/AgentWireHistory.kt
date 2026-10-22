package com.kigtts.tools.agent

internal class AgentVisionUnsupported : java.io.IOException("当前模型不支持图片")
internal fun visionModelKey(settings: AgentSettings) = settings.catalogKey() + "|" + settings.effectiveProfile().endpoint + "|" + settings.effectiveProfile().model

/** Repair old interleaved visual messages for the wire only; original user history stays intact. */
internal fun wireHistory(source: List<AgentMessage>): List<AgentMessage> {
    val output = mutableListOf<AgentMessage>()
    val pending = linkedSetOf<String>()
    val deferred = mutableListOf<AgentMessage>()
    fun finish() {
        pending.forEach { output += AgentMessage(role = "tool", toolCallId = it,
            content = "{\"status\":\"interrupted_outcome_unknown\",\"message\":\"历史调用缺少回执，先查询状态，不要重放修改\"}") }
        pending.clear(); output += deferred; deferred.clear()
    }
    for (message in source) {
        if (message.role == "tool") {
            if (pending.remove(message.toolCallId)) {
                output += message
                if (pending.isEmpty()) { output += deferred; deferred.clear() }
            }
            continue
        }
        if (pending.isNotEmpty() && message.visualInput) { deferred += message; continue }
        finish(); output += message
        if (message.role == "assistant") pending += message.calls.map { it.id }
    }
    finish()
    return output
}
