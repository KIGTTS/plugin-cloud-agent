package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginJson
import kotlinx.serialization.json.*

/** Explain high risk using the real tool and command, never model-provided claims of safety. */
internal fun assistedRisk(call: ToolCall): String? = runCatching {
    val args = PluginJson.parseToJsonElement(call.arguments).jsonObject
    val command = args["command"] as? JsonObject
    val type = command?.get("type")?.jsonPrimitive?.content.orEmpty()
    when (call.name) {
        "create_page", "write_page_file" -> null
        "edit_quick_text" -> if ((args["patch"] as? JsonObject)?.keys?.all { it in setOf("title", "text", "subtitle", "metadata", "color") } == true) null else "此修改涉及媒体或其它行为，请核对目标和变更"
        "deliver_lua" -> "将安装并启用可执行插件，请核对来源与权限用途"
        "lua" -> "将启用或执行 Lua 代码，可能修改软件内容"
        "browser" -> "网页交互可能提交数据或触发外部操作，请核对目标"
        "app" -> "将执行软件操作或改变设置，请核对具体动作"
        "quick_text" -> when (type) {
            "publish-page", "publish-media", "publish-audio", "put-file", "set-main", "presentation" -> null
            else -> "涉及事务提交、导入或批量变更，请核对删除、覆盖及目标范围"
        }
        "stickers" -> "将修改贴纸或照片，可能覆盖或删除已有内容"
        else -> "此操作的影响范围尚不能自动确认"
    }
}.getOrDefault("请求格式无法确认，请先检查参数")
