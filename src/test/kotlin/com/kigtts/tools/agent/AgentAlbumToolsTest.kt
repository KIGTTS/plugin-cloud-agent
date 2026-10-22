package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Model-authored JSON travels through the public tool decoder and the typed broker. */
class AgentAlbumToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun tools(handler: suspend (StickerCommand) -> StickerReply) = AgentTools(object : PluginEnvironment {
        override val storage = PluginStorage(temporary.root)
        override val assets = temporary.root
        override val stickerBroker = object : StickerBroker {
            override fun changes(afterRevision: Long) = emptyFlow<StickerChange>()
            override suspend fun request(command: StickerCommand) = handler(command)
        }
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("No network")
    })
    private suspend fun AgentTools.call(command: String) = execute(ToolCall("album-call", "stickers", "{\"command\":$command}"))

    @Test fun pagedDiscoveryAndAlbumCardsKeepOrderAndCompleteCardFields() = runTest {
        val album = PolaroidAlbum("travel", "旅途", listOf("second", "first", "last"))
        val card = PolaroidCard(id = "first", title = "拍立得", imageId = "filtered", originalImageId = "original",
            frameStyleId = "instax.mini.classic", photoX = .2, photoY = .8, photoZoom = 2.5,
            photoBackdrop = PhotoBackdropMode.BLUR,
            layers = listOf(CardLayer(kind = CardLayerKind.TEXT, text = "旅途", flipX = true, flipY = true)))
        val received = mutableListOf<StickerCommand>()
        val tools = tools { command ->
            received += command
            when (command) {
                is StickerCommand.QueryAlbums -> StickerReply.Albums(12, listOf(album), total = 4, nextOffset = 3)
                is StickerCommand.QueryAlbumCards -> StickerReply.Snapshot(StickerSnapshot(revision = 12,
                    cards = listOf(card), offset = 1, total = 3, nextOffset = 2, albums = listOf(album)))
                else -> error("Unexpected mutation")
            }
        }
        val albums = PluginJson.decodeFromString<StickerReply>(tools.call(
            """{"type":"query-albums","offset":2,"limit":1,"revision":12}""").content) as StickerReply.Albums
        assertEquals(listOf(album), albums.items)
        assertEquals(3, albums.nextOffset)
        val result = tools.call("""{"type":"query-album-cards","albumId":"travel","offset":1,"limit":1,"revision":12}""")
        val snapshot = (PluginJson.decodeFromString<StickerReply>(result.content) as StickerReply.Snapshot).value
        assertEquals(listOf(StickerCommand.QueryAlbums(2, 1, 12), StickerCommand.QueryAlbumCards("travel", 1, 1, 12)), received)
        assertEquals(card, snapshot.cards.single())
        assertEquals(album.cardIds, snapshot.albums.single().cardIds)
        assertEquals(2, snapshot.nextOffset)
        assertFalse(result.pause)
    }

    @Test fun prepareUsesMetadataMoveAndCompleteOrderFieldsWithoutInventingMembership() = runTest {
        var received: StickerCommand? = null
        val tools = tools { command ->
            received = command
            StickerReply.Prepared(StickerPreview("album-edit", 12, listOf("相册"), deleted = 0, requiresConfirmation = false))
        }
        val result = tools.call("""{"type":"prepare","transactionId":"album-tx","revision":12,"edits":[
            {"type":"album.put","id":"travel","name":"新旅途"},
            {"type":"album.move-cards","albumId":"travel","cardIds":["new"]},
            {"type":"album.order","ids":["travel","user.polaroids"]},
            {"type":"album.cards-order","albumId":"travel","cardIds":["new","first","second"]}
        ]}""")
        assertEquals(StickerCommand.Prepare("album-tx", 12, listOf(
            StickerEdit.PutAlbum("travel", "新旅途"),
            StickerEdit.MoveAlbumCards("travel", listOf("new")),
            StickerEdit.OrderAlbums(listOf("travel", DEFAULT_POLAROID_ALBUM_ID)),
            StickerEdit.OrderAlbumCards("travel", listOf("new", "first", "second")))), received)
        assertEquals("album-tx", result.transactionId)
        assertFalse(result.pause)
        val encoded = PluginJson.parseToJsonElement(result.content).jsonObject
        assertEquals("prepared", encoded.getValue("type").jsonPrimitive.content)
    }

    @Test fun removingAlbumPausesAtNativeReviewAndNeverCommitsOrRemovesCardsImplicitly() = runTest {
        val received = mutableListOf<StickerCommand>()
        val tools = tools { command ->
            received += command
            StickerReply.Prepared(StickerPreview("album-review", 15, listOf("删除相册，照片保留"), 1, true))
        }
        val result = tools.call("""{"type":"prepare","transactionId":"remove-album","revision":15,
            "edits":[{"type":"album.remove","ids":["travel"]}]}""")
        assertEquals(listOf(StickerCommand.Prepare("remove-album", 15,
            listOf(StickerEdit.RemoveAlbums(listOf("travel"))))), received)
        assertTrue(result.pause)
        assertEquals("album-review", result.reviewToken)
        assertEquals(ReviewKind.STICKERS, result.reviewKind)
        assertEquals("remove-album", result.transactionId)
    }
}
