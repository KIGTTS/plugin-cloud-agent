package com.kigtts.tools.agent

import com.kigtts.plugin.api.PluginStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentEffortMemoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = AgentStore(PluginStorage(temporary.root))
    @Test fun rememberedModelLevelSurvivesRestartAndNewConversation() {
        val store = store(); val first = store.create()
        val connection = store.connection(first)
        store.rememberEffort(connection, first.model!!, "high")
        store.modify(first.id) { it.copy(reasoningEffort = "low") }
        val restored = store()
        assertEquals("low", restored.connection(restored.get(first.id)!!).effectiveProfile().effort)
        assertEquals("high", restored.connection(restored.create()).effectiveProfile().effort)
    }
    @Test fun explicitAutoDoesNotFallBackToLegacyGlobalLevel() {
        val store = store()
        store.changeProfile { it.copy(effort = "max") }
        val conversation = store.create()
        store.rememberEffort(store.connection(conversation), conversation.model!!, "auto")
        assertNull(store().let { it.connection(it.create()).effectiveProfile().effort })
        store.modify(conversation.id) { it.copy(reasoningEffort = "auto") }
        assertNull(store.connection(store.get(conversation.id)!!).effectiveProfile().effort)
    }
    @Test fun modelAndCustomAccountPreferencesStaySeparate() {
        val store = store()
        val one = CustomProvider(id = "one", name = "one", profile = ProviderProfile("https://one.example", "shared-model"))
        val two = CustomProvider(id = "two", name = "two", profile = one.profile)
        store.updateSettings { it.copy(customProviders = listOf(one, two)) }
        val settings = store.settings.copy(provider = Provider.CUSTOM, customProviderId = "one").configuredConnection()
        store.rememberEffort(settings, "shared-model", "high")
        store.rememberEffort(settings, "other-model", "low")
        assertEquals("high", store.connection(Conversation(provider = Provider.CUSTOM, customProviderId = "one", model = "shared-model")).effectiveProfile().effort)
        assertEquals("low", store.connection(Conversation(provider = Provider.CUSTOM, customProviderId = "one", model = "other-model")).effectiveProfile().effort)
        assertNull(store.connection(Conversation(provider = Provider.CUSTOM, customProviderId = "two", model = "shared-model")).effectiveProfile().effort)
    }
}
