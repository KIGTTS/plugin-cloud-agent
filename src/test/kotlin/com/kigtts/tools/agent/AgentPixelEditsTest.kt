package com.kigtts.tools.agent

import org.junit.Assert.*
import org.junit.Test

class AgentPixelEditsTest {
    @Test fun edgeCutoutKeepsEnclosedWhiteDetailsAndAlpha() {
        val white = -1; val black = 0xff000000.toInt()
        val pixels = IntArray(25) { white }
        for (y in 1..3) for (x in 1..3) pixels[y * 5 + x] = black
        pixels[12] = white
        assertEquals(16, AgentPixelEdits.flood(pixels, 5, 5, intArrayOf(0, 4, 20, 24), 0, 0, true))
        assertEquals(0, pixels[0] ushr 24)
        assertEquals(white, pixels[12]); assertEquals(black, pixels[6])
    }
    @Test fun fillOnlyChangesConnectedRegionAndAdjustPreservesAlpha() {
        val pixels = intArrayOf(-1, 0xff000000.toInt(), -1)
        AgentPixelEdits.flood(pixels, 3, 1, intArrayOf(0), 0, 0x80123456.toInt(), false)
        assertEquals(-1, pixels[2]); assertEquals(0x80123456.toInt(), pixels[0])
        AgentPixelEdits.adjust(pixels, 0.0, 1.0, 0.0)
        assertEquals(128, pixels[0] ushr 24)
        assertEquals(pixels[0] shr 16 and 255, pixels[0] and 255)
    }
    @Test fun silhouetteHasRealTransparentHolesAndRejectsInjectedColor() {
        val svg = AgentPixelEdits.silhouette(intArrayOf(-1, 0, -1, -1), 2, 2, 128, "#112233")
        assertTrue(svg.contains("M0 0 h1v1h-1z")); assertTrue(svg.contains("M0 1 h2v1h-2z"))
        assertThrows(IllegalArgumentException::class.java) { AgentPixelEdits.silhouette(intArrayOf(-1), 1, 1, 128, "red\" onload=\"x") }
    }
    @Test fun vectorRecolorCropIsNewDocumentAndRejectsExternalContent() {
        val original = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"><path fill=\"#000000\" d=\"M0 0h50v50z\"/></svg>"
        val result = AgentSvgEdits.edit(original, "#ffffff", "10 20 40 50")
        assertTrue(result.contains("#ffffff")); assertTrue(result.contains("10.0 20.0 40.0 50.0")); assertTrue(original.contains("#000000"))
        assertThrows(IllegalArgumentException::class.java) { AgentSvgEdits.edit("<!DOCTYPE svg SYSTEM 'file:///secret'><svg viewBox='0 0 1 1'/>", null, null) }
        assertThrows(IllegalArgumentException::class.java) { AgentSvgEdits.edit("<svg viewBox='0 0 1 1'><image href='https://example.com'/></svg>", null, null) }
        assertTrue(AgentSvgEdits.edit(original.replace("<path", "<!-- local artwork --><path"), null, null).contains("local artwork"))
        assertThrows(IllegalArgumentException::class.java) { AgentSvgEdits.edit("<!DOCTYPE svg [<!ENTITY external SYSTEM 'https://example.com'>]><svg viewBox='0 0 1 1'>&external;</svg>", null, null) }
        assertThrows(IllegalArgumentException::class.java) { AgentSvgEdits.edit("<svg viewBox='0 0 1 1'>" + "<g>".repeat(34) + "</g>".repeat(34) + "</svg>", null, null) }
    }
}
