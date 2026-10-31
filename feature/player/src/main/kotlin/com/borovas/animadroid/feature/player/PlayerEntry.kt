package com.borovas.animadroid.feature.player

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/** Registers the player destination: `entryProvider { playerEntry(onBack = { backStack.removeLastOrNull() }) }`. */
fun EntryProviderScope<NavKey>.playerEntry(onBack: () -> Unit, onCloudflare: (url: String) -> Unit = {}) {
    entry<PlayerRoute> { route -> PlayerScreen(route = route, onBack = onBack, onCloudflare = onCloudflare) }
}
