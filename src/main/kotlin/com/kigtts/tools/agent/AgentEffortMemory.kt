package com.kigtts.tools.agent

/** Model preferences are scoped to a provider/account; explicit auto is different from no preference. */
internal fun AgentStore.rememberEffort(connection: AgentSettings, model: String, effort: String) {
    updateSettings { current ->
        fun updated(profile: ProviderProfile) = profile.copy(rememberedEfforts = profile.rememberedEfforts + (model to effort))
        if (connection.provider == Provider.CUSTOM && connection.customProviderId != null)
            current.copy(customProviders = current.customProviders.map {
                if (it.id == connection.customProviderId) it.copy(profile = updated(it.profile)) else it
            })
        else current.copy(profiles = current.profiles + (connection.provider to updated(current.profiles.getValue(connection.provider))))
    }
}
