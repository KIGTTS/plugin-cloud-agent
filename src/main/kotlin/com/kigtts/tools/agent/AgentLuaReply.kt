package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.serialization.json.*

/** Expose runtime diagnostics as tool data, rather than hiding JSON inside a dialog string. */
internal fun luaToolReply(reply: AppReply): ToolResult {
    if (reply is AppReply.Failure) return ToolResult(buildJsonObject {
        put("ok", false); put("code", reply.code); put("error", reply.message)
    }.toString())
    val data = (reply as? AppReply.Data)?.value ?: return ToolResult("{\"ok\":false,\"error\":\"运行时未返回结果\"}")
    val outcome = data["outcome"]?.let { PluginJson.decodeFromJsonElement(PluginOutcome.serializer(), it) }
    val parsed = (outcome as? PluginOutcome.Dialog)?.text?.let { runCatching { PluginJson.parseToJsonElement(it) }.getOrNull() }
    return ToolResult(buildJsonObject {
        put("completed", data["completed"] ?: JsonPrimitive(true))
        when {
            parsed is JsonObject -> { parsed.forEach { (key, value) -> put(key, value) }; if ("ok" !in parsed) put("ok", true) }
            parsed != null -> { put("ok", true); put("result", parsed) }
            outcome is PluginOutcome.Message -> { put("ok", false); put("error", outcome.text) }
            else -> { put("ok", false); put("error", "运行时未返回可核对的调试结果"); put("reply", data) }
        }
    }.toString())
}
