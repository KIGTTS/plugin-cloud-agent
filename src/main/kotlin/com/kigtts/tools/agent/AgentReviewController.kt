package com.kigtts.tools.agent

import com.kigtts.plugin.api.*

/** Keep approval tokens bound to their issuing broker, including after process restoration. */
internal class AgentReviewController(private val environment: PluginEnvironment, private val store: AgentStore,
    private val runner: AgentRunner) {
    private val fallbackCopy: AgentCopy = { _, fallback -> fallback }
    suspend fun open(conversationId: String? = null, copy: AgentCopy = fallbackCopy): PluginOutcome {
        val pending = (if (conversationId != null) store.pendingFor(conversationId) else store.pending) ?: return PluginOutcome.Message(copy("review.none", "没有待审核操作"))
        if (pending.token.isEmpty() || pending.kind == ReviewKind.EXTERNAL)
            return PluginOutcome.Message(copy("review.external", "请先在对应页面检查并完成审核"))
        val message = when (pending.kind) {
            ReviewKind.QUICK_TEXT -> when (val reply = requireNotNull(environment.quickTextBroker).request(
                QuickTextCommand.OpenUi(QuickUiTarget.REVIEW, pending.token))) {
                is QuickTextReply.UiOpened -> null
                else -> copy("review.open_failed", "审核页面尚未打开")
            }
            ReviewKind.STICKERS -> when (val reply = requireNotNull(environment.stickerBroker).request(
                StickerCommand.OpenUi(StickerUiTarget.REVIEW, pending.token))) {
                is StickerReply.UiOpened -> null
                else -> copy("review.open_failed", "审核页面尚未打开")
            }
            ReviewKind.EXTERNAL, ReviewKind.TOOL -> null
        }
        return message?.let { PluginOutcome.Message(it) } ?: PluginOutcome.RefreshUi
    }
    suspend fun discard(conversationId: String? = null, copy: AgentCopy = fallbackCopy): PluginOutcome {
        val pending = (if (conversationId != null) store.pendingFor(conversationId) else store.pending) ?: return PluginOutcome.RefreshUi
        val message = if (pending.token.isEmpty()) null else when (pending.kind) {
            ReviewKind.QUICK_TEXT -> when (val reply = requireNotNull(environment.quickTextBroker).request(QuickTextCommand.Discard(pending.token))) {
                is QuickTextReply.Discarded -> null
                else -> copy("review.discard_failed", "取消状态尚未确认")
            }
            ReviewKind.STICKERS -> when (val reply = requireNotNull(environment.stickerBroker).request(StickerCommand.Discard(pending.token))) {
                is StickerReply.Discarded -> null
                is StickerReply.Failure -> if (reply.code == StickerError.NOT_FOUND) null else copy("review.discard_failed", "取消状态尚未确认")
                else -> copy("review.discard_failed", "取消状态尚未确认")
            }
            ReviewKind.EXTERNAL -> copy("review.external_status", "请先在对应页面检查操作状态")
            ReviewKind.TOOL -> null
        }
        if (message != null) return PluginOutcome.Message(message)
        if (pending.kind == ReviewKind.TOOL && pending.toolCallId.isNotEmpty()) store.message(pending.conversationId,
            AgentMessage(role = "tool", toolCallId = pending.toolCallId, content = "{\"status\":\"rejected_by_user\"}"))
        store.setPending(null, pending.conversationId)
        return PluginOutcome.RefreshUi
    }
    suspend fun resume(conversationId: String? = null, copy: AgentCopy = fallbackCopy): PluginOutcome {
        val pending = (if (conversationId != null) store.pendingFor(conversationId) else store.pending) ?: return PluginOutcome.Message(copy("review.no_resume", "没有待继续操作"))
        check(!runner.isRunning(pending.conversationId))
        if (pending.kind == ReviewKind.TOOL) { runner.approve(pending); return PluginOutcome.RefreshUi }
        var applied = true
        if (pending.token.isNotEmpty()) {
            val failure = when (pending.kind) {
                ReviewKind.QUICK_TEXT -> when (val reply = requireNotNull(environment.quickTextBroker).request(QuickTextCommand.Commit(pending.token))) {
                    is QuickTextReply.Applied -> null
                    else -> copy("review.apply_failed", "变更尚未完成")
                }
                ReviewKind.STICKERS -> stickerCompletion(pending, copy).let { result -> applied = result.applied; result.failure }
                ReviewKind.EXTERNAL -> copy("review.external", "请先在对应页面检查并完成审核")
                ReviewKind.TOOL -> null
            }
            if (failure != null) return PluginOutcome.Message(failure)
            if (pending.toolCallId.isNotEmpty()) store.message(pending.conversationId, AgentMessage(role = "tool",
                content = if (applied) "{\"status\":\"applied_after_user_review\"}" else "{\"status\":\"discarded_after_user_review\"}",
                toolCallId = pending.toolCallId))
        }
        store.setPending(null, pending.conversationId)
        if (pending.toolCallId.isNotEmpty()) runner.continueAfterReview(pending.conversationId)
        return PluginOutcome.OpenPage("chat")
    }
    private suspend fun stickerCompletion(pending: PendingReview, copy: AgentCopy): Completion {
        val broker = requireNotNull(environment.stickerBroker)
        pending.transactionId?.let { id ->
            when (val reply = broker.request(StickerCommand.Transaction(id))) {
                is StickerReply.Applied -> return Completion()
                is StickerReply.Discarded -> return Completion(applied = false)
                is StickerReply.Prepared -> Unit
                is StickerReply.Failure -> if (reply.code != StickerError.NOT_FOUND) return Completion(failure = copy("review.apply_failed", "变更尚未完成"))
                else -> return Completion(failure = copy("review.apply_failed", "变更尚未完成"))
            }
        }
        return when (val reply = broker.request(StickerCommand.Commit(pending.token))) {
            is StickerReply.Applied -> Completion()
            else -> Completion(failure = copy("review.apply_failed", "变更尚未完成"))
        }
    }
    private data class Completion(val applied: Boolean = true, val failure: String? = null)
}
