package com.kigtts.tools.agent

import kotlin.math.*

/** Bounded pixel operations; flood selections are connected, so enclosed details survive cutout. */
internal object AgentPixelEdits {
    fun flood(pixels: IntArray, width: Int, height: Int, seeds: IntArray, tolerance: Int,
        replacement: Int, transparent: Boolean): Int {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        require(pixels.size <= 8_000_000 && tolerance in 0..255)
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var changed = 0
        for (seed in seeds) {
            require(seed in pixels.indices)
            if (visited[seed]) continue
            val target = pixels[seed]
            fun matches(index: Int): Boolean {
                val value = pixels[index]
                return maxOf(abs((value ushr 24) - (target ushr 24)),
                    abs((value shr 16 and 255) - (target shr 16 and 255)),
                    abs((value shr 8 and 255) - (target shr 8 and 255)),
                    abs((value and 255) - (target and 255))) <= tolerance
            }
            var head = 0; var tail = 0
            visited[seed] = true; queue[tail++] = seed
            while (head < tail) {
                val index = queue[head++]
                pixels[index] = if (transparent) pixels[index] and 0x00ffffff else replacement
                changed++
                fun add(next: Int) {
                    if (!visited[next] && matches(next)) { visited[next] = true; queue[tail++] = next }
                }
                if (index % width > 0) add(index - 1)
                if (index % width < width - 1) add(index + 1)
                if (index >= width) add(index - width)
                if (index < pixels.size - width) add(index + width)
            }
        }
        return changed
    }

    fun adjust(pixels: IntArray, brightness: Double, contrast: Double, saturation: Double) {
        require(brightness.isFinite() && brightness in -1.0..1.0 && contrast.isFinite() && contrast in 0.0..4.0 && saturation.isFinite() && saturation in 0.0..4.0)
        for (i in pixels.indices) {
            val value = pixels[i]; val r = value shr 16 and 255; val g = value shr 8 and 255; val b = value and 255
            val gray = .2126 * r + .7152 * g + .0722 * b
            fun channel(c: Int) = (((gray + (c - gray) * saturation - 127.5) * contrast + 127.5 + brightness * 255).roundToInt()).coerceIn(0, 255)
            pixels[i] = (value and -0x1000000) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
    }

    /** Lossless alpha silhouette made of horizontal runs. Bounded to avoid pathological SVG files. */
    fun silhouette(pixels: IntArray, width: Int, height: Int, threshold: Int, color: String): String {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong() && threshold in 1..255)
        require(color.matches(Regex("#[0-9a-fA-F]{6}")))
        val path = StringBuilder()
        var runs = 0
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                if ((pixels[y * width + x] ushr 24) < threshold) { x++; continue }
                val start = x
                while (x < width && (pixels[y * width + x] ushr 24) >= threshold) x++
                require(++runs <= 16000) { "轮廓过于复杂，请先缩小图片或提高透明度阈值" }
                path.append("M$start $y h${x - start}v1h-${x - start}z")
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\"><path fill=\"$color\" d=\"$path\"/></svg>"
    }
}
