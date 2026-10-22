package com.kigtts.tools.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Concurrent sessions share a bounded UI notification rate, never their conversation data. */
internal class AgentUiSignals(notify: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    init { scope.launch { for (signal in signals) { notify(); delay(100) } } }
    fun changed() { signals.trySend(Unit) }
    fun close() { signals.close(); scope.cancel() }
}
