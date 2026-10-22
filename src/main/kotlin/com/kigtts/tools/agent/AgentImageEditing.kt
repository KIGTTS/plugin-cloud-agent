package com.kigtts.tools.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Creates new scoped resources only. Imported originals and existing destinations are never overwritten. */
internal class AgentImageEditing(private val environment: PluginEnvironment) {
    private val images = AgentImages(environment.storage)
    suspend fun execute(call: ToolCall, args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val operation = args.string("operation")
        val source = toolInput {
            val path = args.string("path")
            require(path.startsWith("workspace/") || path.startsWith("imports/")) { "请先把用户指定图片导入自己的workspace或imports" }
            environment.storage.file(path).also { require(it.isFile && it.length() in 1..32L * 1024 * 1024) { "图片不存在或超过32MiB" } }
        }
        val svg = operation == "vectorize"
        val destination = args["destination"]?.jsonPrimitive?.content ?: "workspace/image-edits/${UUID.randomUUID()}.${if (svg) "svg" else "png"}"
        val output = toolInput {
            require(destination.startsWith("workspace/") && destination.endsWith(if (svg) ".svg" else ".png", true)) { "新文件必须在workspace下，位图用.png，矢量用.svg" }
            environment.storage.file(destination).also { require(!it.exists()) { "目标已存在，请使用新路径" }; require(it.canonicalFile != source.canonicalFile) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 8_000_000) { "图片无法解码或超过800万像素，请先使用resize_image生成小尺寸副本" }
        val decoded = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })) { "无法读取图片" }
        val bitmap = try { orientAgentBitmap(decoded, source) } catch (error: Throwable) { decoded.recycle(); throw error }
        if (bitmap !== decoded) decoded.recycle()
        var edited: Bitmap? = null
        val stage = File(output.parentFile, ".${output.name}.${UUID.randomUUID()}.tmp")
        try {
            currentCoroutineContext().ensureActive()
            var svgText: String? = null
            var transparentPixels: Int? = null
            when (operation) {
                "crop" -> {
                    val x = args.int("x"); val y = args.int("y"); val w = args.int("width"); val h = args.int("height")
                    require(x >= 0 && y >= 0 && w > 0 && h > 0 && x.toLong() + w <= bitmap.width && y.toLong() + h <= bitmap.height) { "裁剪坐标必须在原图像素范围内" }
                    edited = Bitmap.createBitmap(bitmap, x, y, w, h)
                }
                "rotate", "flip" -> {
                    val matrix = Matrix()
                    if (operation == "rotate") { val degrees = args.int("degrees"); require(degrees in setOf(90, 180, 270)); matrix.postRotate(degrees.toFloat()) }
                    else { val axis = args["axis"]?.jsonPrimitive?.content ?: "horizontal"; require(axis in setOf("horizontal", "vertical")); matrix.setScale(if (axis == "horizontal") -1f else 1f, if (axis == "vertical") -1f else 1f) }
                    edited = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                }
                "remove_background", "fill", "adjust", "vectorize" -> {
                    val w = bitmap.width; val h = bitmap.height
                    val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    when (operation) {
                        "remove_background", "fill" -> {
                            val x = args["x"]?.jsonPrimitive?.intOrNull; val y = args["y"]?.jsonPrimitive?.intOrNull
                            require((x == null) == (y == null) && (x == null || x in 0 until w && y!! in 0 until h)) { "x/y需同时指定并位于原图内" }
                            val seeds = if (x != null) intArrayOf(y!! * w + x) else {
                                require(operation == "remove_background") { "填色需要x/y种子点" }
                                (listOf(0, w - 1, w * (h - 1), w * h - 1)).distinct().toIntArray()
                            }
                            AgentPixelEdits.flood(pixels, w, h, seeds, args["tolerance"]?.jsonPrimitive?.intOrNull ?: 24,
                                if (operation == "fill") Color.parseColor(args.string("color")) else 0, operation == "remove_background")
                        }
                        "adjust" -> AgentPixelEdits.adjust(pixels, args.number("brightness", 0.0), args.number("contrast", 1.0), args.number("saturation", 1.0))
                        "vectorize" -> svgText = AgentPixelEdits.silhouette(pixels, w, h, args["threshold"]?.jsonPrimitive?.intOrNull ?: 128, args["color"]?.jsonPrimitive?.content ?: "#202020")
                    }
                    transparentPixels = pixels.count { it ushr 24 < 255 }
                    if (!svg) edited = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                }
                else -> error("operation仅支持crop/rotate/flip/remove_background/fill/adjust/vectorize")
            }
            currentCoroutineContext().ensureActive()
            check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
            if (svg) stage.writeText(requireNotNull(svgText), Charsets.UTF_8)
            else stage.outputStream().use { check(requireNotNull(edited).compress(Bitmap.CompressFormat.PNG, 100, it)) }
            currentCoroutineContext().ensureActive()
            check(!output.exists() && stage.renameTo(output)) { "输出文件已存在或无法保存，请核对目标" }
            ToolResult(buildJsonObject {
                put("ok", true); put("path", destination); put("operation", operation); put("originalPreserved", true)
                transparentPixels?.let { put("nonOpaquePixels", it) }
                put("bytes", output.length()); put("mime", if (svg) "image/svg+xml" else "image/png")
                put("width", edited?.width ?: bitmap.width); put("height", edited?.height ?: bitmap.height)
                put("sha256", MessageDigest.getInstance("SHA-256").digest(output.readBytes()).joinToString("") { "%02x".format(it) })
                if (svg) put("preview", "使用render_svg检查真实矢量结果")
            }.toString(), images = if (svg) emptyList() else listOf(images.prepare(destination, "图片处理实际结果：$operation")))
        } finally { stage.delete(); if (edited !== bitmap) edited?.recycle(); bitmap.recycle() }
    }
}

private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
private fun JsonObject.number(key: String, default: Double) = get(key)?.jsonPrimitive?.double ?: default
internal fun imageEditingSchemas() = listOf(namedTool("image_edit",
    "对已下载/导入的真实位图做本地图像处理，输出新的PNG，原图不改。operation=crop(x,y,width,height像素)/rotate(degrees90/180/270)/flip(axis horizontal/vertical)/remove_background(边缘连通纯色抠图，默认四角或x/y指定种子，tolerance0..255)/fill(x,y,color连通填色)/adjust(brightness-1..1,contrast0..4,saturation0..4)/vectorize(透明轮廓转SVG，threshold1..255,color为#RRGGBB)。普通抠图不识别人/复杂场景，不能冒充AI语义分割；先读取图片，处理后检查实际预览。path限workspace/imports，destination限workspace且必须不存在；最多800万像素/32MiB。更大原图先resize_image。矢量输出用render_svg验收。",
    listOf("path", "operation"), "path" to "string", "operation" to "string", "destination" to "string", "x" to "integer", "y" to "integer", "width" to "integer", "height" to "integer", "degrees" to "integer", "axis" to "string", "tolerance" to "integer", "color" to "string", "brightness" to "number", "contrast" to "number", "saturation" to "number", "threshold" to "integer"))
