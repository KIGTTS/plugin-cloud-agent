package com.kigtts.tools.agent

import kotlin.math.*
import kotlinx.serialization.json.*

/** Fixed-size envelope analysis: no model, FFT package or full-file PCM buffer. */
internal class AgentAudioMetrics(private val bucketSeconds: Double = .02, private val maxSeconds: Int = 120) {
    private val squares = DoubleArray(ceil(maxSeconds / bucketSeconds).toInt())
    private val peaks = DoubleArray(squares.size)
    private val counts = IntArray(squares.size)
    private var total = 0L; private var clipped = 0L; private var crossings = 0L
    private var previous = 0.0
    var duration = 0.0; private set
    fun add(time: Double, value: Double) {
        if (!time.isFinite() || time < 0 || time >= maxSeconds || !value.isFinite()) return
        val index = (time / bucketSeconds).toInt(); val sample = value.coerceIn(-1.0, 1.0)
        squares[index] += sample * sample; peaks[index] = max(peaks[index], abs(sample)); counts[index]++
        if (abs(sample) >= .999) clipped++
        if (total > 0 && (sample >= 0) != (previous >= 0)) crossings++
        previous = sample; total++; duration = max(duration, time)
    }
    fun envelope(): DoubleArray = DoubleArray(min(squares.size, (duration / bucketSeconds).toInt() + 1)) { i -> if (counts[i] == 0) 0.0 else sqrt(squares[i] / counts[i]) }
    fun report(): JsonObject {
        require(total > 0) { "所选时间段没有可分析的音频" }
        val rms = envelope(); val power = sqrt(squares.sum() / total)
        val fit = linearFit(rms, bucketSeconds)
        val beat = estimateBeat(rms, bucketSeconds)
        return buildJsonObject {
            put("analyzedSeconds", duration); put("rms", power); put("rmsDbfs", 20 * log10(power.coerceAtLeast(1e-9)))
            put("peak", peaks.maxOrNull() ?: 0.0); put("clippedFraction", clipped.toDouble() / total)
            put("zeroCrossingsPerSecond", crossings / duration.coerceAtLeast(.001))
            put("silenceFraction", rms.count { it < .01 }.toDouble() / rms.size)
            put("envelopeStepSeconds", bucketSeconds); put("envelope", buildJsonArray { rms.forEach { add(it) } })
            put("estimatedBpm", beat?.first?.let(::JsonPrimitive) ?: JsonNull)
            put("beatConfidence", beat?.second ?: 0.0)
            put("envelopeLinearFit", buildJsonObject { put("slopePerSecond", fit.first); put("intercept", fit.second); put("rSquared", fit.third) })
            put("method", "PCM amplitude envelope; autocorrelation tempo estimate, not speech/music recognition or loudness LUFS")
        }
    }
    fun waveformSvg(): String {
        val n = 512; val points = DoubleArray(n)
        val used = min(peaks.size, (duration / bucketSeconds).toInt() + 1).coerceAtLeast(1)
        for (i in 0 until used) points[(i.toLong() * n / used).toInt().coerceAtMost(n - 1)] = max(points[(i.toLong() * n / used).toInt().coerceAtMost(n - 1)], peaks[i])
        val path = buildString { points.forEachIndexed { i, v -> append("M$i ${100 - v * 94}V${100 + v * 94} ") } }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 200"><rect width="512" height="200" fill="#142529"/><path d="$path" stroke="#8dd9dc" stroke-width="1"/></svg>"""
    }
}

internal fun linearFit(values: DoubleArray, step: Double): Triple<Double, Double, Double> {
    if (values.size < 2) return Triple(0.0, values.firstOrNull() ?: 0.0, 0.0)
    val meanX = (values.size - 1) * step / 2; val meanY = values.average()
    var covariance = 0.0; var varianceX = 0.0; var varianceY = 0.0
    values.forEachIndexed { i, y -> val dx = i * step - meanX; val dy = y - meanY; covariance += dx * dy; varianceX += dx * dx; varianceY += dy * dy }
    val slope = covariance / varianceX
    return Triple(slope, meanY - slope * meanX, if (varianceY < 1e-15) 0.0 else (covariance * covariance / (varianceX * varianceY)).coerceIn(0.0, 1.0))
}

internal fun estimateBeat(rms: DoubleArray, step: Double): Pair<Double, Double>? {
    if (rms.size * step < 4) return null
    val onset = DoubleArray(rms.size) { i -> if (i == 0) 0.0 else max(0.0, rms[i] - rms[i - 1]) }
    val energy = onset.sumOf { it * it }; if (energy < 1e-8) return null
    val low = ceil(60 / 200.0 / step).toInt(); val high = min((60 / 40.0 / step).toInt(), rms.size / 2)
    var bestLag = 0; var best = 0.0
    for (lag in low..high) {
        var correlation = 0.0
        for (i in lag until onset.size) correlation += onset[i] * onset[i - lag]
        val score = correlation / energy
        if (score > best + .015) { best = score; bestLag = lag }
    }
    return if (bestLag == 0 || best < .08) null else 60 / (bestLag * step) to best.coerceIn(0.0, 1.0)
}
