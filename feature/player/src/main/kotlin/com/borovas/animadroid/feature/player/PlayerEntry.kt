package com.borovas.animadroid.feature.player

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/** Registers the player destination: `entryProvider { playerEntry(onBack = { backStack.removeLastOrNull() }) }`. */
fun EntryProviderScope<NavKey>.playerEntry(onBack: () -> Unit) {
    entry<PlayerRoute> { route -> PlayerScreen(route = route, onBack = onBack) }
}
