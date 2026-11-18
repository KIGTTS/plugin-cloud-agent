package com.kigtts.tools.agent

internal typealias AgentCopy = (key: String, fallback: String) -> String

internal val sourceAgentCopy: AgentCopy = { _, fallback -> fallback }

internal data class AgentNotice(val key: String, val fallback: String, val arguments: Map<String, String> = emptyMap()) {
    fun render(copy: AgentCopy): String = Regex("\\{([a-z]+)\\}").replace(copy(key, fallback)) {
        arguments[it.groupValues[1]] ?: it.value
    }
}

internal class ModelCatalogFailure(val notice: AgentNotice) : IllegalStateException(notice.render(sourceAgentCopy))

internal class AgentUiFailure(val notice: AgentNotice) : IllegalArgumentException(notice.render(sourceAgentCopy))
