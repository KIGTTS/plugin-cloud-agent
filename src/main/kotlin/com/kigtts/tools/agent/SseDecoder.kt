package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginJson
import kotlinx.serialization.json.*
import java.io.Reader

/** UTF-8 decoding is done before SSE framing, so split Chinese characters are retained. */
class SseDecoder(private val eventLimit: Int = 512 * 1024) {
    suspend fun read(reader: Reader, event: suspend (String) -> Boolean) {
        val line = StringBuilder(); val data = StringBuilder()
        var skipLf = false
        suspend fun finishLine(): Boolean {
            val value = line.toString(); line.setLength(0)
            if (value.isEmpty()) {
                if (data.isEmpty()) return true
                val payload = data.toString().removeSuffix("\n"); data.setLength(0)
                return event(payload)
            }
            if (value.startsWith("data:")) {
                data.append(value.removePrefix("data:").removePrefix(" ")).append('\n')
                require(data.length <= eventLimit) { "服务返回内容过长" }
            }
            return true
        }
        val buffer = CharArray(4096)
        while (true) {
            val count = reader.read(buffer); if (count < 0) break
            for (index in 0 until count) {
                val character = buffer[index]
                if (character == '\n' && skipLf) { skipLf = false; continue }
                skipLf = character == '\r'
                if (character == '\n' || character == '\r') { if (!finishLine()) return }
                else { line.append(character); require(line.length <= eventLimit) { "服务返回内容过长" } }
            }
        }
        if (line.isNotEmpty() && !finishLine()) return
        if (data.isNotEmpty()) event(data.toString().removeSuffix("\n"))
    }
}

class StreamAssembler(private val textLimit: Int = 1024 * 1024) {
    private data class Call(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())
    private val text = StringBuilder(); private val reasoning = StringBuilder(); private val calls = sortedMapOf<Int, Call>()
    var finishReason: String? = null; private set
    var ended = false; private set
    var usage: AgentUsage? = null; private set
    val content get() = text.toString()
    val reasoningContent get() = reasoning.toString()
    val hasToolCalls get() = calls.isNotEmpty()
    fun accept(payload: String): Boolean {
        if (payload == "[DONE]") { ended = true; return false }
        val root = PluginJson.parseToJsonElement(payload).jsonObject
        check(root["error"] == null) { "服务中断了回答，请稍后重试" }
        (root["usage"] as? JsonObject)?.let { usage = parseAgentUsage(it) }
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "content_block_delta" -> {
                val delta = root["delta"]?.jsonObject
                text.append(delta?.get("text").stringOrEmpty())
                require(text.length + reasoning.length <= textLimit)
                return true
            }
            "message_delta" -> {
                root["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
                return true
            }
            "message_stop", "response.completed" -> { ended = true; finishReason = finishReason ?: "stop"; return false }
        }
        if (root["type"]?.jsonPrimitive?.contentOrNull == "response.output_text.delta") {
            text.append(root["delta"].stringOrEmpty()); require(text.length + reasoning.length <= textLimit); return true
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull { it.jsonObject["index"]?.jsonPrimitive?.intOrNull in listOf(null, 0) }?.jsonObject ?: return true
        choice["finish_reason"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let { finishReason = it }
        val delta = choice["delta"]?.jsonObject ?: return true
        text.append(delta["content"].stringOrEmpty()); reasoning.append(delta["reasoning_content"].stringOrEmpty())
        require(text.length + reasoning.length <= textLimit) { "回答过长，请新建会话或缩小请求" }
        delta["tool_calls"]?.jsonArray?.forEach { element ->
            val part = element.jsonObject; val index = part["index"]?.jsonPrimitive?.intOrNull ?: 0
            require(index in 0..31)
            val call = calls.getOrPut(index) { Call() }
            call.id += part["id"].stringOrEmpty()
            part["function"]?.jsonObject?.let { function ->
                call.name += function["name"].stringOrEmpty(); call.arguments.append(function["arguments"].stringOrEmpty())
            }
            require(call.arguments.length <= 256 * 1024 && call.name.length <= 128 && call.id.length <= 256)
        }
        return true
    }
    fun result(): List<ToolCall> {
        check(ended || finishReason != null) { "连接中断，已保留收到的文字" }
        check(finishReason != "length") { "回答达到服务长度限制，已保留收到的文字" }
        if (calls.isNotEmpty()) check(finishReason == "tool_calls" || (ended && finishReason == null)) { "工具请求未完整返回" }
        return calls.values.map { call ->
            require(call.id.isNotBlank() && call.name.isNotBlank())
            PluginJson.parseToJsonElement(call.arguments.toString()).jsonObject
            ToolCall(call.id, call.name, call.arguments.toString())
        }
    }
}
private fun JsonElement?.stringOrEmpty() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: ""
