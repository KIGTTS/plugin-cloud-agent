package com.kigtts.tools.agent

/** A model is selectable only after its provider has a key and a refreshed catalog. */
internal data class AvailableModel(val settings: AgentSettings, val id: String)

internal fun providerConnections(store: AgentStore): List<AgentSettings> =
    Provider.entries.filter { it != Provider.CUSTOM }.map { provider ->
        store.settings.copy(provider = provider, customProviderId = null).configuredConnection()
    } + store.settings.customProviders.map { custom ->
        store.settings.copy(provider = Provider.CUSTOM, customProviderId = custom.id).configuredConnection()
    }

internal fun availableModels(store: AgentStore, secrets: AgentSecrets, current: AgentSettings? = null): List<AvailableModel> =
    (providerConnections(store) + listOfNotNull(current)).distinctBy { it.catalogKey() }.flatMap { settings ->
        if (!secrets.exists(settings)) return@flatMap emptyList()
        val profile = settings.effectiveProfile()
        profile.models.filter { it.isNotBlank() && openCodeChatModel(settings.provider, it) }
            .distinct().map { AvailableModel(settings, it) }
    }

internal fun selectedModelIsAvailable(store: AgentStore, secrets: AgentSecrets, settings: AgentSettings): Boolean =
    availableModels(store, secrets, settings).any { it.settings.catalogKey() == settings.catalogKey() && it.id == settings.effectiveProfile().model }
