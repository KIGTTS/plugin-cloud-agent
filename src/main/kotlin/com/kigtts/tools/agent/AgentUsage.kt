package com.kigtts.tools.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Provider-reported counts only; absent usage is unknown rather than a fabricated zero. */
@Serializable data class AgentUsage(val inputTokens: Int? = null, val outputTokens: Int? = null,
    val cachedInputTokens: Int? = null, val uncachedInputTokens: Int? = null)

internal fun parseAgentUsage(value: JsonObject): AgentUsage {
    fun count(key: String) = value[key]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
    val input = count("prompt_tokens") ?: count("input_tokens")
    val details = (value["prompt_tokens_details"] as? JsonObject) ?: (value["input_tokens_details"] as? JsonObject)
    val cached = count("prompt_cache_hit_tokens") ?: details?.get("cached_tokens")?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
    return AgentUsage(input, count("completion_tokens") ?: count("output_tokens"), cached,
        count("prompt_cache_miss_tokens") ?: if (input != null && cached != null) (input - cached).coerceAtLeast(0) else null)
}
