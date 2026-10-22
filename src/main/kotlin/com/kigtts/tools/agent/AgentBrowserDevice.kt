package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.serialization.json.*

/** Resolve named logical devices in the host; no physical density or orientation is guessed by the model. */
internal suspend fun browserDeviceSize(args: JsonObject, broker: AppBroker): Pair<Int, Int> {
    val explicitWidth = args["width"]?.jsonPrimitive?.intOrNull
    val explicitHeight = args["height"]?.jsonPrimitive?.intOrNull
    require((explicitWidth == null) == (explicitHeight == null)) { "width和height需同时提供" }
    val dimensions = if (explicitWidth != null && explicitHeight != null) explicitWidth to explicitHeight else {
        val device = args["device"]?.jsonPrimitive?.content ?: "current"
        val reply = broker.request(AppCommand.BrowserSession("devices"))
        require(reply is AppReply.Data) { "当前宿主未提供模拟设备信息，请更新主程序或指定width/height" }
        val profile = reply.value["devices"]?.jsonArray?.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == device }?.jsonObject
        requireNotNull(profile) { "设备预设不存在，请先browser devices查询" }
        profile.getValue("width").jsonPrimitive.int to profile.getValue("height").jsonPrimitive.int
    }
    return when (args["orientation"]?.jsonPrimitive?.content ?: "current") {
        "portrait" -> minOf(dimensions.first, dimensions.second) to maxOf(dimensions.first, dimensions.second)
        "landscape" -> maxOf(dimensions.first, dimensions.second) to minOf(dimensions.first, dimensions.second)
        "current" -> dimensions
        else -> throw IllegalArgumentException("orientation须为current/portrait/landscape")
    }
}
