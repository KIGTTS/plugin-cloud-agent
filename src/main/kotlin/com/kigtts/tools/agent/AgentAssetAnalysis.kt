package com.kigtts.tools.agent

import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import javax.xml.parsers.DocumentBuilderFactory

internal fun analyzeWebAsset(file: File): JsonObject = when (file.extension.lowercase(java.util.Locale.ROOT)) {
    "svg" -> analyzeSvg(file)
    "gltf", "glb" -> analyzeGltf(file)
    else -> error("支持 SVG、glTF 和 GLB 文件")
}

internal fun analyzeSvg(file: File): JsonObject {
    require(file.length() in 1..2L * 1024 * 1024) { "SVG 分析最多支持 2MiB" }
    val text = file.readText(); require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "SVG 不能包含文档实体" }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true; isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val root = factory.newDocumentBuilder().parse(text.byteInputStream()).documentElement
    require(root.localName == "svg" || root.tagName == "svg") { "文件不是 SVG" }
    val nodes = root.getElementsByTagName("*"); require(nodes.length <= 20000) { "SVG 元素过多" }
    val counts = sortedMapOf<String, Int>(); val paints = linkedSetOf<String>(); val links = linkedSetOf<String>()
    for (i in 0 until nodes.length) {
        val element = nodes.item(i) as? org.w3c.dom.Element ?: continue
        val name = element.localName ?: element.tagName
        counts[name] = (counts[name] ?: 0) + 1
        listOf("fill", "stroke").forEach { key -> element.getAttribute(key).takeIf { it.isNotBlank() }?.let { if (paints.size < 64) paints += it.take(120) } }
        listOf("href", "xlink:href").forEach { key -> element.getAttribute(key).takeIf { it.isNotBlank() && !it.startsWith('#') && !it.startsWith("data:") }?.let { if (links.size < 64) links += it.take(500) } }
    }
    return buildJsonObject {
        put("format", "svg"); put("width", root.getAttribute("width")); put("height", root.getAttribute("height")); put("viewBox", root.getAttribute("viewBox"))
        put("elements", buildJsonObject { counts.forEach { (name, count) -> put(name, count) } })
        put("paints", JsonArray(paints.map(::JsonPrimitive))); put("externalReferences", JsonArray(links.map(::JsonPrimitive)))
        put("hasScripts", counts.containsKey("script")); put("method", "XML structure only; scripts and external references are never executed")
    }
}

internal fun analyzeGltf(file: File): JsonObject {
    val json = if (file.extension.equals("glb", true)) RandomAccessFile(file, "r").use { input ->
        fun uint() = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        require(uint() == 0x46546c67L && uint() == 2L) { "仅支持 glTF 2 GLB" }
        require(uint() == input.length()) { "GLB 文件长度不完整" }
        val length = uint(); require(uint() == 0x4e4f534aL && length in 1..4L * 1024 * 1024 && length <= input.length() - input.filePointer) { "GLB JSON 块无效或超过 4MiB" }
        val bytes = ByteArray(length.toInt()); input.readFully(bytes); String(bytes, Charsets.UTF_8).trimEnd('\u0000', ' ')
    } else { require(file.length() in 1..4L * 1024 * 1024) { "glTF JSON 最多支持 4MiB" }; file.readText() }
    val value = PluginJsonForAssets.parseToJsonElement(json).jsonObject
    require(value["asset"]?.jsonObject?.get("version")?.jsonPrimitive?.content?.startsWith("2.") == true) { "仅支持 glTF 2" }
    fun items(key: String) = value[key]?.jsonArray.orEmpty()
    val accessors = items("accessors")
    var primitives = 0; var vertices = 0L; var triangles = 0L
    items("meshes").forEach { mesh -> mesh.jsonObject["primitives"]?.jsonArray.orEmpty().forEach { element ->
        primitives++; val p = element.jsonObject
        fun count(index: JsonElement?) = index?.jsonPrimitive?.intOrNull?.let { accessors.getOrNull(it)?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull } ?: 0
        val vertexCount = count(p["attributes"]?.jsonObject?.get("POSITION")); vertices += vertexCount
        val count = p["indices"]?.let(::count) ?: vertexCount
        triangles += when (p["mode"]?.jsonPrimitive?.intOrNull ?: 4) { 4 -> count / 3; 5, 6 -> (count - 2).coerceAtLeast(0); else -> 0 }
    } }
    val links = (items("buffers") + items("images")).mapNotNull { it.jsonObject["uri"]?.jsonPrimitive?.content?.takeUnless { uri -> uri.startsWith("data:") } }.distinct().take(64)
    return buildJsonObject {
        put("format", file.extension.lowercase()); put("version", value.getValue("asset").jsonObject.getValue("version"))
        listOf("scenes", "nodes", "meshes", "materials", "textures", "images", "animations", "skins", "accessors", "buffers").forEach { put(it, items(it).size) }
        put("primitives", primitives); put("verticesAcrossPrimitives", vertices); put("estimatedTriangles", triangles)
        put("externalReferences", JsonArray(links.map { JsonPrimitive(it.take(500)) }))
        put("extensionsRequired", value["extensionsRequired"] ?: JsonArray(emptyList()))
        put("positionBounds", buildJsonArray { accessors.take(128).forEachIndexed { index, a -> val v = a.jsonObject
            if (v["type"]?.jsonPrimitive?.content == "VEC3" && v.containsKey("min") && v.containsKey("max")) add(buildJsonObject { put("accessor", index); put("min", v.getValue("min")); put("max", v.getValue("max")) }) } })
        put("method", "glTF/GLB JSON structure and declared bounds; no external fetch, compressed-mesh decoding or 3D rendering")
    }
}
private val PluginJsonForAssets = Json { ignoreUnknownKeys = true }
