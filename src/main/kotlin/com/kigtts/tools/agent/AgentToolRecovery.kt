package com.kigtts.tools.agent

import kotlinx.serialization.json.*

/** A failed response is evidence to reconcile, never permission to replay a mutation. */
internal fun failedTool(call: ToolCall, error: Exception, readOnly: Boolean): ToolResult {
    if (error is AgentToolArgumentError) return invalidToolArguments(call, error)
    return ToolResult(buildJsonObject {
        put("ok", false)
        put("error", if (readOnly) "READ_FAILED" else "OUTCOME_UNKNOWN")
        put("message", error.message.orEmpty().take(700))
        if (!readOnly) {
            put("outcomeUnknown", true)
            put("recovery", "先查询当前状态或原事务回执，确认是否已执行；仅修正未执行的部分。不要盲目重放修改。")
        }
    }.toString(), pause = !readOnly, summary = "操作状态尚不明确，请检查后继续", reviewKind = ReviewKind.EXTERNAL)
}

internal fun ToolResult.forAccess(mode: AgentAccessMode): ToolResult {
    if (!pause || mode != AgentAccessMode.FULL) return this
    return copy(content = buildJsonObject {
        put("ok", false)
        put("error", if (reviewToken != null) "HOST_CONFIRMATION_REQUIRED" else "RECONCILE_REQUIRED")
        put("result", runCatching { Json.parseToJsonElement(content) }.getOrElse { JsonPrimitive(content) })
        reviewToken?.let { put("reviewToken", it); put("reviewKind", reviewKind.name) }
        transactionId?.let { put("transactionId", it) }
        put("recovery", "用户已开启完全访问，无需再次向用户申请批准。先查询实际状态/原事务；已完成则继续，未执行则修正参数。宿主仍返回权限拒绝时报告该限制，不能伪造批准或反复重放同一修改。")
    }.toString(), pause = false, reconcile = true)
}
