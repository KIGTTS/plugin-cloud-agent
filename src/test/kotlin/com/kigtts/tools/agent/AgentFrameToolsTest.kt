package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Exercise Agent JSON -> portable command -> broker -> serialized reply, rather than bypassing the tool. */
class AgentFrameToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun tools(handler: suspend (StickerCommand) -> StickerReply) = AgentTools(object : PluginEnvironment {
        override val storage = PluginStorage(temporary.root); override val assets = temporary.root
        override val stickerBroker = object : StickerBroker {
            override fun changes(afterRevision: Long) = emptyFlow<StickerChange>()
            override suspend fun request(command: StickerCommand) = handler(command)
        }
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("No network")
    })
    private suspend fun AgentTools.run(command: StickerCommand) = execute(ToolCall("call", "stickers",
        buildJsonObject { put("command", PluginJson.encodeToJsonElement(command)) }.toString()))
    @Test fun discoverFrameThenAssignNativeIdWithoutLosingPoseAndMirrors() = runTest {
        val style = PolaroidFrameStyle("brand.example.paper", "品牌纸框", version = 3, overlayImageId = "overlay", owner = "brand.example")
        val query = StickerCommand.QueryFrames(offset = 2, limit = 10, revision = 7)
        var prepared: StickerCommand.Prepare? = null
        val tools = tools { command -> when (command) {
            is StickerCommand.QueryFrames -> {
                assertEquals(query, command)
                StickerReply.Frames(7, listOf(style), total = 12, nextOffset = null)
            }
            is StickerCommand.Prepare -> {
                prepared = command; StickerReply.Prepared(StickerPreview("token", 7, listOf("名片"), 0, false))
            }
            else -> error("Unexpected command")
        } }
        val found = PluginJson.decodeFromString<StickerReply>(tools.run(query).content) as StickerReply.Frames
        assertEquals(style, found.items.single())
        val card = PolaroidCard(title = "名片", frameStyleId = found.items.single().id,
            photoX = .2, photoY = .8, photoZoom = 2.5, photoBackdrop = PhotoBackdropMode.BLUR,
            layers = listOf(CardLayer(kind = CardLayerKind.TEXT, text = "贴纸文字", flipX = true, flipY = true)))
        val result = tools.run(StickerCommand.Prepare("tx", found.revision, listOf(StickerEdit.PutCard(card))))
        assertEquals(card, (prepared!!.edits.single() as StickerEdit.PutCard).card)
        assertFalse(result.pause)
        assertTrue(STICKER_TOOL_DESCRIPTION.contains("query-frames"))
        assertTrue(STICKER_TOOL_DESCRIPTION.contains("frameStyleId"))
    }
    @Test fun customFrameDtoCarriesOpaqueOverlayAndHostAssignedMetadata() = runTest {
        val style = PolaroidFrameStyle("tools.cloud-agent.paper", "自定义纸框", color = 0xFFEEEEDD,
            borderColor = 0xFF007070, borderWidth = .005, photoCornerRadius = .02, overlayImageId = "image")
        var captured: StickerCommand.Prepare? = null
        val assigned = style.copy(owner = "tools.cloud-agent", version = 4)
        val tools = tools { command -> when (command) {
            is StickerCommand.Prepare -> {
                captured = command; StickerReply.Prepared(StickerPreview("token", 7, listOf(style.name), 0, false))
            }
            is StickerCommand.QueryFrames -> StickerReply.Frames(8, listOf(assigned), 1)
            else -> error("Unexpected command")
        } }
        tools.run(StickerCommand.Prepare("frame-tx", 7, listOf(StickerEdit.PutFrameStyle(style))))
        assertEquals(style, (captured!!.edits.single() as StickerEdit.PutFrameStyle).style)
        assertNull(style.owner)
        val actual = PluginJson.decodeFromString<StickerReply>(tools.run(StickerCommand.QueryFrames()).content) as StickerReply.Frames
        assertEquals("tools.cloud-agent", actual.items.single().owner)
        assertEquals(4L, actual.items.single().version)
        assertFalse(actual.toString().contains("path="))
    }
    @Test fun removingOwnedFrameUsesSameNativeReviewAndTransactionRecovery() = runTest {
        var captured: StickerCommand.Prepare? = null
        val tools = tools { command ->
            captured = command as StickerCommand.Prepare
            StickerReply.Prepared(StickerPreview("frame-token", 8, listOf("移除边框"), 1, true))
        }
        val result = tools.run(StickerCommand.Prepare("remove-frame", 8,
            listOf(StickerEdit.RemoveFrameStyles(listOf("tools.cloud-agent.paper")))))
        assertEquals(listOf("tools.cloud-agent.paper"), (captured!!.edits.single() as StickerEdit.RemoveFrameStyles).ids)
        assertTrue(result.pause); assertEquals("frame-token", result.reviewToken)
        assertEquals(ReviewKind.STICKERS, result.reviewKind); assertEquals("remove-frame", result.transactionId)
    }
}
