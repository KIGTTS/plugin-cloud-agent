package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginJson
import kotlinx.serialization.json.*

/** Thrown only before a command is sent to a broker, never after a possibly applied mutation. */
internal class AgentToolArgumentError(message: String) : IllegalArgumentException(message)
internal inline fun <T> toolInput(block: () -> T): T = try { block() }
catch (error: AgentToolArgumentError) { throw error }
catch (error: IllegalArgumentException) { throw AgentToolArgumentError("参数未通过校验，命令尚未执行。" + error.message.orEmpty().take(700)) }
catch (error: NoSuchElementException) { throw AgentToolArgumentError("缺少必填参数，命令尚未执行。" + error.message.orEmpty().take(300)) }

internal inline fun <reified T> decodeToolCommand(value: JsonElement): T = try {
    PluginJson.decodeFromJsonElement<T>(value)
} catch (error: IllegalArgumentException) {
    throw AgentToolArgumentError("参数未通过校验，命令尚未执行。" + error.message.orEmpty().take(700))
}

internal fun invalidToolArguments(call: ToolCall, error: AgentToolArgumentError): ToolResult =
    ToolResult(buildJsonObject {
        put("ok", false); put("error", "INVALID_ARGUMENTS"); put("executed", false)
        put("message", error.message)
        if (call.name == "quick_text") put("help",
            "新网页优先create_page并提供完整html。底层publish-page的root是对象，例如" +
                """{"type":"publish-page","root":{"path":"workspace/project"},"mainPage":"index.html"}""" +
                "，必须先写好该目录中的文件。describe查询能力，不要用缺失字段的修改命令探测接口。")
        if (call.name == "write_file") put("help", "path必须以workspace/开头，例如workspace/moto-racer/index.html；text是完整UTF-8内容。")
        if (call.name == "edit_quick_text") put("help", "metadata是扁平的字符串映射，例如patch={metadata:{\"icon.symbol\":\"pets\",\"icon.mode\":\"icon\"}}，不要嵌套icon对象。先read_quick_text取得最新revision。")
    }.toString())
