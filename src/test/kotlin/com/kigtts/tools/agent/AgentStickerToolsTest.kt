package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentStickerToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun broker(handler: suspend (StickerCommand) -> StickerReply) = object : StickerBroker {
        override fun changes(afterRevision: Long) = emptyFlow<StickerChange>()
        override suspend fun request(command: StickerCommand) = handler(command)
    }
    private fun environment(stickers: StickerBroker? = null, quick: QuickTextBroker? = null) = object : PluginEnvironment {
        override val storage = PluginStorage(temporary.root); override val assets = temporary.root
        override val stickerBroker = stickers; override val quickTextBroker = quick
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("No network")
    }
    private suspend fun AgentTools.run(command: StickerCommand) = execute(ToolCall("call", "stickers",
        buildJsonObject { put("command", PluginJson.encodeToJsonElement(command)) }.toString()))
    @Test fun typedQueryAndCardLayersKeepNativeIdsWithoutPaths() = runTest {
        var captured: StickerCommand? = null
        val card = PolaroidCard("card", "名片", layers = listOf(CardLayer("layer", CardLayerKind.IMAGE, imageId = "image", x = .2, y = .3)))
        val tools = AgentTools(environment(broker { captured = it; StickerReply.Snapshot(StickerSnapshot(7, cards = listOf(card))) }))
        val query = StickerCommand.Query(StickerScope.CARDS, limit = 10, revision = 7)
        val result = tools.run(query)
        assertEquals(query, captured)
        val restored = PluginJson.decodeFromString<StickerReply>(result.content) as StickerReply.Snapshot
        assertEquals(card, restored.value.cards.single())
        assertFalse(result.content.contains("path"))
        assertTrue(tools.schemas.any { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "stickers" })
    }
    @Test fun missingStickerCapabilityDoesNotFallBackToAppCommands() = runTest {
        val tools = AgentTools(environment())
        try { tools.run(StickerCommand.Describe); fail("No granted broker") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("未授予")) }
    }
    @Test fun imagePublishAndCopiesCannotReachSecretsOrOtherOwners() = runTest {
        var calls = 0
        val tools = AgentTools(environment(broker { calls++; StickerReply.Discarded("x") }))
        val invalid = listOf(
            StickerCommand.PublishImage(PluginResource("config/key-DEEPSEEK.bin")),
            StickerCommand.PublishImage(PluginResource("workspace/image.png", "other.plugin")),
            StickerCommand.ReadImage("image", "config/key-DEEPSEEK.bin"),
            StickerCommand.ExportCard("card", "config/key-DEEPSEEK.bin"),
            StickerCommand.ReadImage("image", "drafts/tools.example/main.lua"),
            StickerCommand.ReadImage("image", "workspace/../config/key-DEEPSEEK.bin"))
        invalid.forEach { command ->
            try { tools.run(command); fail(command.toString()) } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, calls)
    }
    @Test fun copiedPhotoCanBeRepublishedWithoutBorrowingOriginalOwner() = runTest {
        val environment = environment(broker { command ->
            val read = command as StickerCommand.ReadImage
            File(temporary.root, read.destination).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            StickerReply.Resource(PluginResource(read.destination, "tools.cloud-agent"), 3)
        }, object : QuickTextBroker {
            override fun changes(afterRevision: Long) = emptyFlow<QuickTextChange>()
            override suspend fun request(command: QuickTextCommand): QuickTextReply {
                val image = (command as QuickTextCommand.PublishMedia).content as SubtitleContent.Image
                assertEquals(PluginResource("workspace/exports/photo.png", "tools.cloud-agent"), image.source)
                assertArrayEquals(byteArrayOf(1, 2, 3), File(temporary.root, image.source.path).readBytes())
                return QuickTextReply.Media(QuickMedia("new-media", "image", version = 1))
            }
        })
        val tools = AgentTools(environment)
        val copied = PluginJson.decodeFromString<StickerReply>(tools.run(StickerCommand.ReadImage("image", "workspace/exports/photo.png")).content) as StickerReply.Resource
        val command: QuickTextCommand = QuickTextCommand.PublishMedia(SubtitleContent.Image(copied.source))
        val result = tools.execute(ToolCall("publish", "quick_text", "{\"command\":${PluginJson.encodeToString(command)}}"))
        assertEquals("new-media", (PluginJson.decodeFromString<QuickTextReply>(result.content) as QuickTextReply.Media).value.id)
    }
    @Test fun quickPhotoAndGalleryBridgeBothWaysThroughOpaqueIds() = runTest {
        val calls = mutableListOf<StickerCommand>()
        val tools = AgentTools(environment(broker { command ->
            calls += command
            when (command) {
                is StickerCommand.ImportMedia -> StickerReply.Image(StickerImage("gallery-photo", "image/jpeg", 12, 100, 80, 1))
                is StickerCommand.PublishQuickMedia -> StickerReply.Media(QuickMedia("quick-photo", "image", version = 1))
                else -> error("Unexpected")
            }
        }))
        val imported = PluginJson.decodeFromString<StickerReply>(tools.run(StickerCommand.ImportMedia("original-media")).content) as StickerReply.Image
        val published = PluginJson.decodeFromString<StickerReply>(tools.run(StickerCommand.PublishQuickMedia(imported.value.id)).content) as StickerReply.Media
        assertEquals(listOf(StickerCommand.ImportMedia("original-media"), StickerCommand.PublishQuickMedia("gallery-photo")), calls)
        assertEquals("quick-photo", published.value.id)
    }
    @Test fun deletionPreviewImmediatelyPausesBeforeAnyCommit() = runTest {
        val tools = AgentTools(environment(broker { StickerReply.Prepared(StickerPreview("sticker-token", 7, listOf("删除照片"), 1, true)) }))
        val result = tools.run(StickerCommand.Prepare("tx", 7, listOf(StickerEdit.RemoveItems(listOf("photo")))))
        assertTrue(result.pause); assertEquals("sticker-token", result.reviewToken); assertEquals(ReviewKind.STICKERS, result.reviewKind)
    }
    @Test fun missingConfirmationTokenStillPausesAndDoesNotClaimSuccess() = runTest {
        val tools = AgentTools(environment(broker { StickerReply.Failure(StickerError.CONFIRMATION_REQUIRED, "审核") }))
        val result = tools.run(StickerCommand.Commit("bound-token"))
        assertTrue(result.pause); assertEquals("bound-token", result.reviewToken)
        assertEquals(ReviewKind.STICKERS, result.reviewKind)
    }
    @Test fun renderedCardExportReturnsOwnedResourceForWholeCardSticker() = runTest {
        var captured: StickerCommand? = null
        val tools = AgentTools(environment(broker { captured = it; StickerReply.Resource(PluginResource("exports/card.png", "tools.cloud-agent"), 12) }))
        val result = tools.run(StickerCommand.ExportCard("card", "exports/card.png"))
        assertEquals(StickerCommand.ExportCard("card", "exports/card.png"), captured)
        assertEquals(PluginResource("exports/card.png", "tools.cloud-agent"), (PluginJson.decodeFromString<StickerReply>(result.content) as StickerReply.Resource).source)
    }
}
