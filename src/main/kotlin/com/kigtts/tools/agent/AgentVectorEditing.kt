package com.kigtts.tools.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

internal object AgentSvgEdits {
    fun edit(text: String, color: String?, viewBox: String?): String {
        require(text.toByteArray(Charsets.UTF_8).size <= 512 * 1024)
        require(!text.contains(Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE))) { "SVG不允许实体和DOCTYPE" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; isValidating = false; isExpandEntityReferences = false
        }
        // Android's parser does not support the desktop Xerces feature flags. Reject declarations
        // before parsing and deny every external resolution instead of failing on valid SVGs.
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw org.xml.sax.SAXException("SVG不允许外部资源") }
        }
        val document = builder.parse(InputSource(StringReader(text)))
        for (i in 0 until document.childNodes.length) require(document.childNodes.item(i).nodeType != org.w3c.dom.Node.PROCESSING_INSTRUCTION_NODE) { "SVG不允许外部样式处理指令" }
        val root = document.documentElement
        require(root.localName == "svg" && root.getAttribute("viewBox").isNotBlank()) { "SVG需要viewBox" }
        color?.let { require(it.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) }
        val allowed = setOf("svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "defs", "linearGradient", "radialGradient", "stop", "clipPath", "title", "desc")
        val nodes = document.getElementsByTagName("*")
        require(nodes.length <= 2000) { "SVG元素数量超过渲染上限" }
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as Element
            var parent = node.parentNode; var depth = 0
            while (parent is Element) { require(++depth <= 32) { "SVG嵌套过深" }; parent = parent.parentNode }
            require(node.localName in allowed) { "SVG包含不支持的元素：${node.localName}" }
            for (a in 0 until node.attributes.length) {
                val attr = node.attributes.item(a)
                require(!attr.nodeName.startsWith("on", true) && attr.localName !in setOf("href", "style")) { "SVG不允许脚本/外链/style" }
                require(!attr.nodeValue.contains(Regex("url\\s*\\((?!\\s*#[A-Za-z_])", RegexOption.IGNORE_CASE))) { "SVG不允许外部资源" }
            }
            if (color != null) {
                for (attribute in listOf("fill", "stroke", "stop-color")) {
                    val old = node.getAttribute(attribute)
                    if (old.isNotBlank() && old != "none" && !old.startsWith("url(")) node.setAttribute(attribute, color)
                }
                if (node.localName in setOf("path", "rect", "circle", "ellipse", "polygon") && !node.hasAttribute("fill")) node.setAttribute("fill", color)
            }
        }
        viewBox?.let {
            val dimensions = it.trim().split(Regex("[\\s,]+")).map(String::toDouble)
            require(dimensions.size == 4 && dimensions.all(Double::isFinite) && dimensions[2] > 0 && dimensions[3] > 0) { "viewBox必须为x y width height" }
            root.setAttribute("viewBox", dimensions.joinToString(" ")); root.setAttribute("overflow", "hidden")
            root.removeAttribute("width"); root.removeAttribute("height")
        }
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }
}

internal class AgentVectorEditing(private val environment: PluginEnvironment) {
    suspend fun execute(call: ToolCall, args: JsonObject): ToolResult {
        val path = args.string("path")
        val vector = call.name == "svg_edit"
        val destination = args["destination"]?.jsonPrimitive?.content ?: "workspace/image-edits/${UUID.randomUUID()}.${if (vector) "svg" else "png"}"
        val (source, output) = toolInput {
            require(path.startsWith("workspace/") || path.startsWith("imports/"))
            require(destination.startsWith("workspace/") && destination.endsWith(if (vector) ".svg" else ".png", true))
            val source = environment.storage.file(path); val output = environment.storage.file(destination)
            require(source.isFile && source.length() in 1..32L * 1024 * 1024 && !output.exists() && source.canonicalFile != output.canonicalFile)
            source to output
        }
        withContext(Dispatchers.IO) {
            val stage = File(output.parentFile, ".${output.name}.${UUID.randomUUID()}.tmp")
            try {
                check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
                if (vector) stage.writeText(AgentSvgEdits.edit(source.readText(Charsets.UTF_8), args["color"]?.jsonPrimitive?.content, args["viewBox"]?.jsonPrimitive?.content), Charsets.UTF_8)
                else {
                    val width = args.getValue("width").jsonPrimitive.int; val height = args.getValue("height").jsonPrimitive.int
                    require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 8_000_000)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(source.path, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0)
                    var sample = 1
                    while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 8_000_000 && sample < 128) sample *= 2
                    val decoded = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
                    val input = try { orientAgentBitmap(decoded, source) } catch (error: Throwable) { decoded.recycle(); throw error }
                    if (input !== decoded) decoded.recycle()
                    var scaled: Bitmap? = null
                    try { scaled = Bitmap.createScaledBitmap(input, width, height, true); stage.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    finally { if (scaled !== input) scaled?.recycle(); input.recycle() }
                }
                currentCoroutineContext().ensureActive()
                check(!output.exists() && stage.renameTo(output))
            } finally { stage.delete() }
        }
        if (vector) {
            val preview = AgentVisualTools(environment).execute(ToolCall(call.id + "-preview", "render_svg", "{}"), buildJsonObject { put("path", destination); put("size", 512) })
            val result = PluginJson.parseToJsonElement(preview.content).jsonObject
            return ToolResult(buildJsonObject { put("ok", result["ok"]?.jsonPrimitive?.booleanOrNull == true); put("saved", true); put("path", destination); put("originalPreserved", true); put("preview", result) }.toString(), images = preview.images)
        }
        return ToolResult(buildJsonObject { put("ok", true); put("path", destination); put("originalPreserved", true); put("bytes", output.length()) }.toString(), images = listOf(AgentImages(environment.storage).prepare(destination, "缩放图片实际结果")))
    }
}

internal fun vectorEditingSchemas() = listOf(
    namedTool("resize_image", "把真实图片缩放为新的PNG副本，保留原图。path限workspace/imports；width/height为明确输出像素，最多800万像素。原图较大时采样解码，不用于宣称提升原图细节。destination限workspace且不能覆盖。", listOf("path", "width", "height"), "path" to "string", "width" to "integer", "height" to "integer", "destination" to "string"),
    namedTool("svg_edit", "本地矢量裁剪/调色：path为自身SVG；color=#RRGGBB批量替换非none填色/描边，viewBox='x y width height'进行非破坏原稿的矢量裁剪，可只传其中一项。保留原文件，destination为新的workspace/*.svg；拒绝脚本/外链/实体。返回真实渲染预览，先验收再应用。", listOf("path"), "path" to "string", "color" to "string", "viewBox" to "string", "destination" to "string")
)
