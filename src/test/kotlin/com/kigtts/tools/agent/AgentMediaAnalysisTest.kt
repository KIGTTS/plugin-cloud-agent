package com.kigtts.tools.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class AgentMediaAnalysisTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun silenceConstantToneAndKnownPulseTrainHaveHonestBoundedMetrics() {
        val silence = AgentAudioMetrics(); val tone = AgentAudioMetrics(); val beat = AgentAudioMetrics()
        repeat(12000) { i -> val t = i / 1000.0
            silence.add(t, 0.0); tone.add(t, .5 * sin(t * 2 * PI * 50))
            val phase = t % .5; beat.add(t, if (phase < .04) .8 * (1 - phase / .04) else 0.0)
        }
        val quiet = silence.report(); assertEquals(JsonNull, quiet["estimatedBpm"]); assertEquals(1.0, quiet.getValue("silenceFraction").jsonPrimitive.double, 0.0)
        val normal = tone.report(); assertEquals(.5 / sqrt(2.0), normal.getValue("rms").jsonPrimitive.double, .001)
        assertEquals(0.0, normal.getValue("clippedFraction").jsonPrimitive.double, 0.0)
        val pulse = beat.report(); assertEquals(120.0, pulse.getValue("estimatedBpm").jsonPrimitive.double, 1.0)
        assertTrue(pulse.getValue("beatConfidence").jsonPrimitive.double > .7)
        assertTrue(beat.waveformSvg().startsWith("<svg")); assertTrue(beat.waveformSvg().length < 50000)
    }
    @Test fun numericalFitHandlesLargeTimeOffsetsAndRejectsDegenerateData() {
        val points = buildJsonArray { repeat(40) { i -> val x = 1_000_000.0 + i; val z = i - 20.0; add(buildJsonArray { add(x); add(2 + 3 * z + .5 * z * z) }) } }
        val fit = fitAgentData(points, 2)
        assertEquals(1.0, fit.getValue("rSquared").jsonPrimitive.double, 1e-12); assertTrue(fit.getValue("rmse").jsonPrimitive.double < 1e-8)
        val center = fit.getValue("xCenter").jsonPrimitive.double; val scale = fit.getValue("xScale").jsonPrimitive.double
        val z = (1_000_010.0 - center) / scale; val coefficients = fit.getValue("coefficients").jsonArray.map { it.jsonPrimitive.double }
        assertEquals(22.0, coefficients.indices.sumOf { coefficients[it] * z.pow(it) }, 1e-8)
        assertTrue(runCatching { fitAgentData(Json.parseToJsonElement("[[1,2],[1,3],[1,4]]").jsonArray, 1) }.isFailure)
    }
    @Test fun svgAndGlbInspectReferencesWithoutExecutingOrFetchingThem() {
        val svg = temp.newFile("test.svg").apply { writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 16"><path fill="#123456" d="M0 0L2 2"/><image href="https://example.com/photo.png"/><script>throw Error('never')</script></svg>""") }
        val s = analyzeWebAsset(svg); assertTrue(s.getValue("hasScripts").jsonPrimitive.boolean); assertEquals("0 0 32 16", s.getValue("viewBox").jsonPrimitive.content)
        assertEquals("https://example.com/photo.png", s.getValue("externalReferences").jsonArray.single().jsonPrimitive.content)
        val bad = temp.newFile("bad.svg").apply { writeText("""<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///private">]><svg>&x;</svg>""") }
        assertTrue(runCatching { analyzeWebAsset(bad) }.isFailure)
        val text = """{"asset":{"version":"2.0"},"accessors":[{"count":6,"type":"VEC3","min":[0,0,0],"max":[1,1,1]}],"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],"buffers":[{"uri":"mesh.bin","byteLength":72}],"animations":[{}]}"""
        val json = text.padEnd((text.length + 3) / 4 * 4).toByteArray()
        val glb = temp.newFile("test.glb").apply { writeBytes(ByteBuffer.allocate(20 + json.size).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546c67).putInt(2).putInt(20 + json.size).putInt(json.size).putInt(0x4e4f534a).put(json).array()) }
        val g = analyzeWebAsset(glb); assertEquals(2L, g.getValue("estimatedTriangles").jsonPrimitive.long); assertEquals(1, g.getValue("animations").jsonPrimitive.int)
        assertEquals("mesh.bin", g.getValue("externalReferences").jsonArray.single().jsonPrimitive.content)
        glb.appendBytes(byteArrayOf(0)); assertTrue(runCatching { analyzeWebAsset(glb) }.isFailure)
    }
}
