package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentTaskBadgesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun taskBadgesStayIsolatedPersistAndPrioritizeErrorsThenReview() {
        val storage = PluginStorage(temporary.root)
        val store = AgentStore(storage)
        val first = store.create().id; val second = store.create().id
        val badges = AgentTaskBadges(store, storage)
        badges.changed(first, true); assertNull(badges.badge())
        store.message(first, AgentMessage(role = "assistant", content = "完成"))
        badges.changed(first, false)
        assertEquals(BadgeTone.THEME, badges.badge()!!.tone)
        assertEquals(BadgeTone.THEME, AgentTaskBadges(store, storage).badge()!!.tone)
        store.setPending(PendingReview(second, "call", "token", "等待确认"))
        assertEquals(BadgeTone.CAUTION, badges.badge()!!.tone)
        assertEquals(second, badges.attentionConversation())
        badges.viewed(second); assertEquals(BadgeTone.CAUTION, badges.badge()!!.tone)
        store.message(first, AgentMessage(role = "assistant", state = MessageState.FAILED))
        badges.changed(first, false)
        assertEquals(BadgeTone.WARNING, badges.badge()!!.tone)
        assertEquals(first, badges.attentionConversation())
        badges.viewed(first); assertEquals(BadgeTone.CAUTION, badges.badge()!!.tone)
        store.setPending(null, second); assertNull(badges.badge())
        store.message(second, AgentMessage(role = "assistant", state = MessageState.CANCELLED))
        badges.changed(second, false); assertNull(badges.badge())
    }
}
