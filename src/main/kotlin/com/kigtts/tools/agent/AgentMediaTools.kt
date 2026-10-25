package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID

internal class AgentMediaTools(private val environment: PluginEnvironment) {
    suspend fun execute(call: ToolCall, args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.string("path")
        require(listOf("workspace/", "imports/", "exports/", "resources/quick-imports/").any(path::startsWith)) { "请选择助手授权目录内的文件" }
        val file = environment.storage.file(path)
        require(file.isFile && file.length() in 1..256L * 1024 * 1024) { "文件不存在或超过 256MiB" }
        val id = UUID.randomUUID().toString(); val root = "workspace/analysis/$id"
        val start = args["startSeconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val seconds = args["seconds"]?.jsonPrimitive?.intOrNull ?: 60
        require(start.isFinite() && start in 0.0..86400.0 && seconds in 1..120)
        when (call.name) {
            "analyze_audio" -> {
                val (metrics, format) = analyzeAudioFile(file, start, seconds)
                val svg = "$root-waveform.svg"; val stats = "$root.json"
                val report = buildJsonObject { metrics.report().forEach { (k, v) -> put(k, v) }; put("source", path); put("startSeconds", start); put("sourceFormat", format.toString()) }
                environment.storage.file(svg).apply { parentFile!!.mkdirs(); writeText(metrics.waveformSvg()) }
                environment.storage.file(stats).writeText(report.toString())
                ToolResult(buildJsonObject { report.filterKeys { it != "envelope" }.forEach { (k, v) -> put(k, v) }; put("statisticsPath", stats); put("waveformPath", svg) }.toString())
            }
            "analyze_video" -> {
                val count = args["samples"]?.jsonPrimitive?.intOrNull ?: 6; require(count in 2..12)
                val sheet = "$root-contact.jpg"; val report = analyzeVideoFile(file, environment.storage.file(sheet), start, seconds, count)
                val result = buildJsonObject { report.forEach { (k, v) -> put(k, v) }; put("source", path); put("contactSheetPath", sheet) }
                environment.storage.file("$root.json").writeText(result.toString())
                ToolResult(result.toString(), images = listOf(AgentImages(environment.storage).prepare(sheet, "视频关键帧，时间为抽样请求位置")))
            }
            "analyze_asset" -> ToolResult(analyzeWebAsset(file).toString())
            else -> error("没有此分析工具")
        }
    }
}

internal fun mediaToolSchemas() = listOf(
    namedTool("analyze_audio", "本地音频或视频音轨的轻量分析：时长、峰值/RMS/dBFS、静音/削波比例、过零率、节拍/BPM估计、包络线性拟合/R²。返回完整包络JSON与波形SVG路径。startSeconds指定片段，seconds为1..120（默认60）。使用Android解码器，格式取决于设备；节拍是估计，不是语音识别或LUFS测量。保留原文件，不上传。", listOf("path"), "path" to "string", "startSeconds" to "number", "seconds" to "integer"),
    namedTool("analyze_video", "本地视频轻量分析：元数据、2..12帧关键帧联系表图片、采样亮度与变化量、亮度线性趋势。startSeconds/seconds选择最多120秒，samples默认6。适合查看片段与定位大致画面变化；不是逐帧运动追踪、精确切镜或语义识别。原文件不改，不上传；视觉模型可查看联系表。", listOf("path"), "path" to "string", "startSeconds" to "number", "seconds" to "integer", "samples" to "integer"),
    namedTool("analyze_asset", "安全读取SVG/glTF/GLB网页素材：SVG画布/元素/颜色/外链；glTF2场景节点、网格、三角形估计、材质、动画、声明边界与扩展。只分析结构，不执行脚本、不请求外链，不解码压缩网格。GLB/glTF JSON限4MiB，SVG限2MiB。", listOf("path"), "path" to "string")
)
