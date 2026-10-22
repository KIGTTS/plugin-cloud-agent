package com.kigtts.tools.agent

internal data class AgentContextWindow(val messages: List<AgentMessage>, val omitted: Boolean)

/** Keep tool calls and their results together. Full history remains available through a read-only tool. */
internal fun contextWindow(messages: List<AgentMessage>, budget: Int = 96 * 1024,
    cost: (AgentMessage) -> Int = { it.content.length + it.reasoning.length + it.calls.sumOf { call -> call.arguments.length } + 128 }): AgentContextWindow {
    val complete = messages.filter { it.state == MessageState.COMPLETE }
    val groups = mutableListOf<MutableList<AgentMessage>>()
    complete.forEach { message ->
        if (message.role == "tool" && groups.lastOrNull()?.any { it.calls.any { call -> call.id == message.toolCallId } } == true) groups.last().add(message)
        else groups.add(mutableListOf(message))
    }
    var used = 0
    val recent = groups.asReversed().takeWhile { group ->
        val size = group.sumOf(cost)
        used += size
        used <= budget || used == size
    }.asReversed().flatten()
    val goals = listOfNotNull(complete.firstOrNull { it.role == "user" && !it.visualInput }, complete.lastOrNull { it.role == "user" && !it.visualInput })
    val selected = (goals + recent).map { it.id }.toSet()
    return AgentContextWindow(complete.filter { it.id in selected }, selected.size < complete.size)
}
