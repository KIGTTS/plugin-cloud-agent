package com.kigtts.tools.agent

import android.graphics.*
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.File

internal suspend fun analyzeVideoFile(file: File, output: File, start: Double, seconds: Int, samples: Int): JsonObject {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.path)
        fun metadata(key: Int) = retriever.extractMetadata(key)
        val duration = (metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000
        require(start < duration && duration > 0) { "视频时长无效或起始时间超过片长" }
        val width = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        require(width > 0 && height > 0) { "文件没有可读取的视频画面" }
        val end = minOf(duration, start + seconds)
        val columns = minOf(samples, 3); val rows = (samples + columns - 1) / columns
        val sheet = Bitmap.createBitmap(columns * 320, rows * 204, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet); canvas.drawColor(Color.rgb(20, 28, 30))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16f }
        val brightness = DoubleArray(samples); var previous: IntArray? = null; val frameResults = mutableListOf<JsonObject>()
        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
        try {
            repeat(samples) { index ->
                currentCoroutineContext().ensureActive()
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "视频取帧超时，请减少取帧数量" }
                val time = start + (end - start) * (index + .5) / samples
                val ratio = minOf(640.0 / width, 640.0 / height, 1.0)
                val frame = requireNotNull(retriever.getScaledFrameAtTime((time * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))) { "无法读取 ${time.toInt()} 秒附近画面" }
                try {
                    val scale = minOf(320f / frame.width, 180f / frame.height)
                    val left = index % columns * 320f + (320 - frame.width * scale) / 2
                    val top = index / columns * 204f + (180 - frame.height * scale) / 2
                    canvas.drawBitmap(frame, null, RectF(left, top, left + frame.width * scale, top + frame.height * scale), paint)
                    canvas.drawText(java.lang.String.format(java.util.Locale.ROOT, "%.2f s", time), index % columns * 320f + 8, index / columns * 204f + 198, paint)
                    val tiny = Bitmap.createScaledBitmap(frame, 32, 32, true)
                    val pixels = IntArray(1024); tiny.getPixels(pixels, 0, 32, 0, 0, 32, 32); if (tiny !== frame) tiny.recycle()
                    val values = pixels.map { .2126 * Color.red(it) + .7152 * Color.green(it) + .0722 * Color.blue(it) }
                    brightness[index] = values.average() / 255
                    val difference = previous?.let { old -> pixels.indices.sumOf { i ->
                        (kotlin.math.abs(Color.red(pixels[i]) - Color.red(old[i])) + kotlin.math.abs(Color.green(pixels[i]) - Color.green(old[i])) + kotlin.math.abs(Color.blue(pixels[i]) - Color.blue(old[i]))) / 765.0
                    } / pixels.size }
                    previous = pixels
                    frameResults += buildJsonObject { put("requestedTimeSeconds", time); put("brightness", brightness[index]); put("differenceFromPrevious", difference?.let(::JsonPrimitive) ?: JsonNull) }
                } finally { frame.recycle() }
            }
            output.parentFile!!.mkdirs(); output.outputStream().use { check(sheet.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
        } finally { sheet.recycle() }
        val fit = linearFit(brightness, (end - start) / samples)
        return buildJsonObject {
            put("durationSeconds", duration); put("width", width); put("height", height)
            put("rotationDegrees", metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
            put("frameRate", metadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("bitrate", metadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("hasAudio", metadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
            put("frames", JsonArray(frameResults)); put("brightnessSlopePerSecond", fit.first)
            put("method", "Evenly sampled nearby keyframes, letterboxed contact sheet; frame differences are hints, not exact scene-cut detection or semantic recognition")
        }
    } finally { retriever.release() }
}
