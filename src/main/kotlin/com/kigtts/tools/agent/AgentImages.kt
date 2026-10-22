package com.kigtts.tools.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kigtts.plugin.api.PluginStorage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/** Images are copied and downsampled once, kept on disk, and never embedded in Binder snapshots. */
internal class AgentImages(private val storage: PluginStorage) {
    fun prepare(path: String, description: String = "图片"): AgentImage {
        require(listOf("imports/", "workspace/", "exports/", "resources/quick-imports/").any(path::startsWith)) { "图片不在助手授权目录" }
        val file = storage.file(path); require(file.isFile && file.length() in 1..32L * 1024 * 1024) { "图片不存在或过大" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取此图片，请选择PNG、JPEG或WebP图片" }
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1536) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        val rotated = orientAgentBitmap(bitmap, file)
        val flattened = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(flattened).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(rotated, 0f, 0f, null) }
        val bytes = ByteArrayOutputStream()
        try { flattened.compress(Bitmap.CompressFormat.JPEG, 85, bytes) } finally { flattened.recycle(); if (rotated !== bitmap) rotated.recycle(); bitmap.recycle() }
        require(bytes.size() <= 1250 * 1024) { "图片压缩后仍过大" }
        val target = "imports/chat/" + UUID.randomUUID() + ".jpg"
        storage.file(target).apply { parentFile!!.mkdirs(); writeBytes(bytes.toByteArray()) }
        return AgentImage(target, description = description.take(300))
    }
    fun data(image: AgentImage): String {
        require(image.path.startsWith("imports/chat/") && image.mime == "image/jpeg")
        val file = storage.file(image.path); require(file.isFile && file.length() in 1..1250L * 1024) { "会话图片已丢失，请重新添加" }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(file.readBytes())
    }
}
