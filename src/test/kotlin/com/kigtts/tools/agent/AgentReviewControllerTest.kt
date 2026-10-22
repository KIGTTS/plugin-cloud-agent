package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentReviewControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun environment(handler: suspend (StickerCommand) -> StickerReply) = object : PluginEnvironment {
        override val storage = PluginStorage(temporary.root); override val assets = temporary.root
        override val quickTextBroker = object : QuickTextBroker {
            override fun changes(afterRevision: Long) = emptyFlow<QuickTextChange>()
            override suspend fun request(command: QuickTextCommand): QuickTextReply = error("Sticker token sent to wrong broker")
        }
        override val stickerBroker = object : StickerBroker {
            override fun changes(afterRevision: Long) = emptyFlow<StickerChange>()
            override suspend fun request(command: StickerCommand) = handler(command)
        }
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("No network")
    }
    private fun controller(environment: PluginEnvironment, store: AgentStore): AgentReviewController {
        val secrets = object : AgentSecrets {
            override fun exists(provider: Provider) = false
            override fun read(provider: Provider): String = error("No secrets")
            override fun write(provider: Provider, value: String) = error("No secrets")
        }
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject,
                delta: suspend (StreamAssembler) -> Unit): StreamAssembler = error("No requests")
        }
        return AgentReviewController(environment, store, AgentRunner(store, secrets, transport, AgentTools(environment), {}))
    }
    private fun pending(environment: PluginEnvironment): AgentStore = AgentStore(environment.storage).apply {
        setPending(PendingReview(create().id, "", "sticker-token", "审核照片", kind = ReviewKind.STICKERS))
    }
    @Test fun restoredStickerReviewOpensOnlyItsNativeReviewPage() = runTest {
        var captured: StickerCommand? = null
        val env = environment { captured = it; StickerReply.UiOpened(StickerUiTarget.REVIEW) }
        pending(env); val restored = AgentStore(env.storage)
        assertEquals(ReviewKind.STICKERS, restored.pending!!.kind)
        assertEquals(PluginOutcome.RefreshUi, controller(env, restored).open())
        assertEquals(StickerCommand.OpenUi(StickerUiTarget.REVIEW, "sticker-token"), captured)
    }
    @Test fun continueCannotApproveButWorksAfterNativeApproval() = runTest {
        var approved = false; val calls = mutableListOf<StickerCommand>()
        val env = environment { command ->
            calls += command
            if (approved) StickerReply.Applied(8, "tx")
            else StickerReply.Failure(StickerError.CONFIRMATION_REQUIRED, "先审核", reviewToken = "sticker-token")
        }
        val store = pending(env); val reviews = controller(env, store)
        assertTrue(reviews.resume() is PluginOutcome.Message)
        assertEquals("sticker-token", AgentStore(env.storage).pending?.token)
        approved = true
        assertEquals(PluginOutcome.OpenPage("chat"), reviews.resume())
        assertNull(AgentStore(env.storage).pending)
        assertEquals(listOf(StickerCommand.Commit("sticker-token"), StickerCommand.Commit("sticker-token")), calls)
    }
    @Test fun unknownDiscardResponseRetainsReviewAndThenConfirmedDiscardClearsIt() = runTest {
        var acknowledged = false
        val env = environment { if (acknowledged) StickerReply.Discarded("sticker-token") else StickerReply.Snapshot(StickerSnapshot(7)) }
        val store = pending(env); val reviews = controller(env, store)
        assertTrue(reviews.discard() is PluginOutcome.Message); assertNotNull(store.pending)
        acknowledged = true
        assertEquals(PluginOutcome.RefreshUi, reviews.discard()); assertNull(AgentStore(env.storage).pending)
    }
    @Test fun completedTransactionRestoresAfterTokenExpiryWithoutRecommitting() = runTest {
        val calls = mutableListOf<StickerCommand>()
        val env = environment { command -> calls += command; StickerReply.Applied(8, "tx") }
        val initial = pending(env); initial.setPending(initial.pending!!.copy(transactionId = "tx"))
        val restored = AgentStore(env.storage)
        assertEquals(PluginOutcome.OpenPage("chat"), controller(env, restored).resume())
        assertEquals(listOf(StickerCommand.Transaction("tx")), calls)
        assertNull(AgentStore(env.storage).pending)
    }
    @Test fun nativeDiscardReceiptContinuesWithoutAnotherCommit() = runTest {
        val calls = mutableListOf<StickerCommand>()
        val env = environment { command -> calls += command; StickerReply.Discarded("sticker-token") }
        val store = pending(env); store.setPending(store.pending!!.copy(transactionId = "tx"))
        assertEquals(PluginOutcome.OpenPage("chat"), controller(env, store).resume())
        assertEquals(listOf(StickerCommand.Transaction("tx")), calls)
        assertNull(store.pending)
    }
    @Test fun expiredReviewCanBeCancelledWithoutClaimingRollback() = runTest {
        val env = environment { StickerReply.Failure(StickerError.NOT_FOUND, "预览已失效") }
        val store = pending(env)
        assertEquals(PluginOutcome.RefreshUi, controller(env, store).discard())
        assertNull(AgentStore(env.storage).pending)
        assertTrue(store.selected()!!.messages.isEmpty())
    }
}
