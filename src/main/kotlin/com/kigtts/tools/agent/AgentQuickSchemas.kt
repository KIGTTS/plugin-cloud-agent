package com.kigtts.tools.agent

import kotlinx.serialization.json.*

internal fun namedTool(name: String, description: String, required: List<String>, vararg fields: Pair<String, String>) = buildJsonObject {
    put("type", "function"); put("function", buildJsonObject {
        put("name", name); put("description", description); put("parameters", buildJsonObject {
            put("type", "object"); put("properties", buildJsonObject { fields.forEach { (key, type) -> put(key, buildJsonObject { put("type", type) }) } })
            put("required", buildJsonArray { required.forEach { add(it) } }); put("additionalProperties", false)
        })
    })
}
internal fun quickToolSchemas() = listOf(
    namedTool("list_quick_text", "查询/搜索所有分组的快捷文本，返回真实id、revision、附件与版本。query搜索正文和标题；同名时让用户选择，不猜ID。按nextOffset分页。", emptyList(), "query" to "string", "groupId" to "string", "offset" to "integer", "limit" to "integer"),
    namedTool("read_quick_text", "读取指定id的完整字段与revision、附件mediaId/owner/version/mainPage，修改前必须读取。", listOf("id"), "id" to "string"),
    namedTool("edit_quick_text", "局部修改已有条目。先read取revision。patch只含要改字段text/title/groupId/colorKind/color/richText/formats/autoPlay/audioPolicy/metadata/kind/mediaId；其它字段和附件保留。metadata是扁平字符串映射，例如{\"icon.symbol\":\"pets\",\"icon.mode\":\"icon\"}，不嵌套icon对象。改正文但没提供formats时清除过时格式偏移。不要把编辑已有卡片误作创建新卡片。", listOf("id", "revision", "patch"), "id" to "string", "revision" to "integer", "patch" to "object"),
    namedTool("read_page_file", "读取HTML快捷文本项目文件。先read_quick_text取得mediaId，quick_text files列目录/取version。文本支持offset/limit分页。", listOf("mediaId", "path"), "mediaId" to "string", "path" to "string", "offset" to "integer", "limit" to "integer"),
    namedTool("write_page_file", "修改已有HTML/JS/CSS文件，mediaId/path/version来自实际查询，text为完整新内容。允许用户自己的页面及本助手页面，其它插件资源会拒绝，不能冒用owner。写后重新查询version并截图验收。", listOf("mediaId", "path", "version", "text"), "mediaId" to "string", "path" to "string", "version" to "integer", "text" to "string")
)
