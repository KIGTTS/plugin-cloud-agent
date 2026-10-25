package com.kigtts.tools.agent

import java.util.Locale

internal fun uniqueProviderName(name: String, providers: List<CustomProvider>): String {
    val base = name.trim().take(70)
    val used = providers.map { it.name.trim().lowercase(Locale.ROOT) }.toSet()
    if (base.lowercase(Locale.ROOT) !in used) return base
    var index = 2
    while ("$base ($index)".lowercase(Locale.ROOT) in used) index++
    return "$base ($index)"
}
