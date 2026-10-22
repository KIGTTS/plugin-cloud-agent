package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginJson
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentCachePrefixTest {
    @Test fun largeContextHistoryStaysAnUnchangedPrefixWhenOneTurnIsAppended() {
        val settings = AgentSettings().let { s -> s.copy(profiles = s.profiles + (Provider.DEEPSEEK to s.profiles.getValue(Provider.DEEPSEEK)
            .copy(modelInfo = mapOf(Provider.DEEPSEEK.model to ModelInfo(contextTokens = 131072))))) }
        val before = Conversation(messages = listOf(AgentMessage(role = "user", content = "目标"),
            AgentMessage(role = "assistant", content = "stable document ".repeat(9000)), AgentMessage(role = "user", content = "继续")))
        val old = chatRequest(settings, before, JsonArray(emptyList())).getValue("messages").jsonArray
        val next = chatRequest(settings, before.copy(messages = before.messages + AgentMessage(role = "assistant", content = "已完成")),
            JsonArray(emptyList())).getValue("messages").jsonArray
        assertEquals("Preserve the cacheable prefix instead of sliding at 96KiB", old, JsonArray(next.take(old.size)))
        assertTrue(old.any { it.jsonObject["content"]?.jsonPrimitive?.content?.startsWith("stable document") == true })
    }
    @Test fun providerUsageOnlyChunkRetainsDeepSeekAndCompatibleCacheCounts() {
        val stream = StreamAssembler()
        stream.accept("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""")
        stream.accept("""{"choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":20,"prompt_cache_hit_tokens":768,"prompt_cache_miss_tokens":232}}""")
        stream.accept("[DONE]")
        assertEquals(AgentUsage(1000, 20, 768, 232), stream.usage)
        val other = parseAgentUsage(PluginJson.parseToJsonElement("""{"prompt_tokens":500,"prompt_tokens_details":{"cached_tokens":300}}""").jsonObject)
        assertEquals(300, other.cachedInputTokens); assertEquals(200, other.uncachedInputTokens)
        assertNull(parseAgentUsage(buildJsonObject { put("completion_tokens", 4) }).cachedInputTokens)
    }
}
