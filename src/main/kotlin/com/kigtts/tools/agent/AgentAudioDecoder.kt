package com.kigtts.tools.agent

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteOrder

/** Uses the device's codec; decodes a bounded segment directly into envelope buckets. */
internal suspend fun analyzeAudioFile(file: File, start: Double, seconds: Int): Pair<AgentAudioMetrics, MediaFormat> {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("文件没有可读取的音轨")
        val format = extractor.getTrackFormat(track); extractor.selectTrack(track)
        val startUs = (start * 1_000_000).toLong(); val endUs = startUs + seconds * 1_000_000L
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME))); codec = decoder
        decoder.configure(format, null, null, 0); decoder.start()
        val result = AgentAudioMetrics(maxSeconds = seconds); val info = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var idleSince = android.os.SystemClock.elapsedRealtime()
        val deadline = idleSince + 60_000
        while (!outputDone) {
            currentCoroutineContext().ensureActive()
            val now = android.os.SystemClock.elapsedRealtime()
            check(now < deadline && now - idleSince < 10_000) { "音频解码超时，请缩短分析片段" }
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = requireNotNull(decoder.getInputBuffer(index)); val time = extractor.sampleTime
                    val length = if (time < 0 || time >= endUs) -1 else extractor.readSampleData(buffer, 0)
                    if (length < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(index, 0, length, time, 0); extractor.advance() }
                }
            }
            when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val pcm = decoder.outputFormat
                    rate = pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (pcm.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcm.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(rate in 1000..384000 && channels in 1..32 && encoding in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) { "暂不支持此 PCM 格式" }
                }
                else -> if (index >= 0) {
                    idleSince = now
                    try {
                        val buffer = decoder.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2; val frames = info.size / (bytes * channels)
                            for (frame in 0 until frames) {
                                var sample = 0.0
                                repeat(channels) { sample += if (bytes == 4) buffer.float.toDouble() else buffer.short / 32768.0 }
                                val time = info.presentationTimeUs / 1_000_000.0 + frame.toDouble() / rate - start
                                result.add(time, sample / channels)
                            }
                        }
                    } finally { decoder.releaseOutputBuffer(index, false) }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs >= endUs
                }
            }
        }
        return result to format
    } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
}
