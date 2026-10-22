package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Real image bytes stay in plugin storage; tool responses contain paths and bounded visual attachments. */
internal class AgentImageTools(private val environment: PluginEnvironment) {
    private val images = AgentImages(environment.storage)
    suspend fun execute(call: ToolCall, args: JsonObject): ToolResult = when (call.name) {
        "image_search" -> { environment.requirePermission("network"); AgentWebTools.searchImages(args.string("query")) }
        "download_image" -> {
            val path = args["path"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: "workspace/images/${UUID.randomUUID()}"
            val result = AgentDownload(environment).download(args.string("url"), path)
            val image = images.prepare(path, args["description"]?.jsonPrimitive?.content ?: "下载的图片")
            ToolResult(result.content, images = if (args["view"]?.jsonPrimitive?.booleanOrNull == true) listOf(image) else emptyList())
        }
        "read_quick_image" -> {
            val target = "workspace/quick-reads/${UUID.randomUUID()}.image"
            val reply = requireNotNull(environment.quickTextBroker).request(QuickTextCommand.GetFile(args.string("mediaId"), args["path"]?.jsonPrimitive?.content.orEmpty(), target))
            when (reply) {
                is QuickTextReply.Resource -> ToolResult(buildJsonObject { put("ok", true); put("path", target); put("bytes", reply.bytes) }.toString(),
                    images = listOf(images.prepare(target, "快捷文本中的图片")))
                else -> ToolResult(PluginJson.encodeToJsonElement<QuickTextReply>(reply).toString())
            }
        }
        else -> error("未知图片工具")
    }
}

internal fun imageToolSchemas() = listOf(
    namedTool("image_search", "搜索公开图片，返回原图/缩略图URL、来源、作者与许可。只有插件network权限，无额外操作审批。默认Wikimedia Commons，找不到可web_search查其它来源再web_fetch读取图片地址。结果不是已下载文件，需download_image。", listOf("query"), "query" to "string"),
    namedTool("download_image", "下载公开HTTPS真实图片到workspace，保留原文件，最多32MiB。path可省略，view=true会附视觉图片；不支持视觉的模型仍可下载保存，不能声称看见。返回实际路径可read_image、write_quick_image或打包进网页。文件已存在时用新路径，不覆盖资源。", listOf("url"), "url" to "string", "path" to "string", "description" to "string", "view" to "boolean"),
    namedTool("read_quick_image", "复制并查看用户指定快捷文本的真实图片。先read_quick_text取得真实mediaId，媒体图片path为空；网页内图片先quick_text files查相对path。返回助手workspace副本，可read_image再次查看或用于网页/贴纸，不读取其它私有目录。", listOf("mediaId"), "mediaId" to "string", "path" to "string"),
    namedTool("write_quick_image", "将本助手workspace/imports中的真实图片保存到已有快捷文本，改为MEDIA词条，保留字幕/标题/分组/颜色等字段。先read_quick_text得到id和revision，冲突时重新读取，按权限模式审批。新图片另存，不改原图。", listOf("id", "revision", "path"), "id" to "string", "revision" to "integer", "path" to "string", "description" to "string")
)
