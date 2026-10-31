@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.borovas.animadroid.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.borovas.animadroid.core.designsystem.AnimadroidTheme
import com.borovas.animadroid.core.designsystem.AnimeCard
import com.borovas.animadroid.core.designsystem.ConnectedToggleGroup
import com.borovas.animadroid.core.designsystem.EmptyState
import com.borovas.animadroid.core.designsystem.ErrorState
import com.borovas.animadroid.core.designsystem.PlaceholderGrid
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.TranslationMode
import kotlinx.coroutines.launch

/**
 * Navigation 3 entry for the search tab.
 * [metadata] lets the app attach scene metadata (e.g. ListDetailSceneStrategy.listPane()).
 */
fun EntryProviderScope<NavKey>.searchEntry(
    onAnimeClick: (Anime) -> Unit,
    onCloudflare: (url: String) -> Unit,
    metadata: Map<String, Any> = emptyMap(),
) {
    entry<SearchRoute>(metadata = metadata) {
        // Activity-scoped so query/results survive bottom-tab switches (which pop this entry).
        var ctx = androidx.compose.ui.platform.LocalContext.current
        while (ctx is android.content.ContextWrapper && ctx !is androidx.lifecycle.ViewModelStoreOwner) ctx = ctx.baseContext
        val owner = ctx as androidx.lifecycle.ViewModelStoreOwner
        val vm: SearchViewModel = hiltViewModel(viewModelStoreOwner = owner)
        val state by vm.state.collectAsStateWithLifecycle()
        SearchScreen(
            state = state,
            onSearch = vm::search,
            onModeChange = vm::setMode,
            onRemoveRecent = vm::removeRecent,
            onClearRecents = vm::clearRecents,
            onRetry = vm::retry,
            onAnimeClick = onAnimeClick,
            onCloudflare = onCloudflare,
        )
    }
}

@Composable
fun SearchScreen(
    state: SearchUiState,
    onSearch: (String) -> Unit,
    onModeChange: (TranslationMode) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    onRetry: () -> Unit,
    onAnimeClick: (Anime) -> Unit,
    onCloudflare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchBarState = rememberSearchBarState()
    val textState = rememberTextFieldState(state.query)
    val scope = rememberCoroutineScope()

    fun submit(q: String) {
        textState.edit { replace(0, length, q) }
        onSearch(q)
        scope.launch { searchBarState.animateToCollapsed() }
    }

    val inputField = @Composable {
        SearchBarDefaults.InputField(
            textFieldState = textState,
            searchBarState = searchBarState,
            onSearch = { submit(it) },
            placeholder = { Text("Search anime") },
            leadingIcon = {
                if (searchBarState.currentValue == SearchBarValue.Expanded) {
                    IconButton(onClick = { scope.launch { searchBarState.animateToCollapsed() } }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                } else Icon(Icons.Rounded.Search, null)
            },
            trailingIcon = {
                if (textState.text.isNotEmpty()) {
                    IconButton(onClick = { textState.edit { replace(0, length, "") } }) { Icon(Icons.Rounded.Close, "Clear") }
                }
            },
        )
    }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        SearchBar(
            state = searchBarState,
            inputField = inputField,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
        ExpandedFullScreenSearchBar(state = searchBarState, inputField = inputField) {
            RecentSearches(state.recents, onPick = ::submit, onRemove = onRemoveRecent, onClear = onClearRecents)
        }
        ConnectedToggleGroup(
            options = TranslationMode.entries,
            selected = state.mode,
            onSelect = onModeChange,
            label = { it.name },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
        when (val r = state.results) {
            SearchResults.Idle -> EmptyState(
                Icons.Rounded.Search, "Find your next show",
                "Search by title. Your recent searches show up when you tap the bar.",
            )
            SearchResults.Loading -> PlaceholderGrid()
            is SearchResults.Error ->
                if (r.cloudflareUrl != null) ErrorState(
                    r.message, onRetry = { onCloudflare(r.cloudflareUrl) }, actionLabel = "Verify in browser",
                    icon = Icons.Rounded.Language, title = "Verification needed",
                ) else ErrorState(r.message, onRetry)
            is SearchResults.Success ->
                if (r.items.isEmpty()) EmptyState(Icons.Rounded.SearchOff, "No results", "Nothing found for \"${state.query}\" in ${state.mode.name}. Try the other mode or a different title.")
                else LazyVerticalGrid(
                    GridCells.Adaptive(130.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(r.items, key = { it.providerId + it.id }) { a -> AnimeCard(a, onClick = { onAnimeClick(a) }) }
                }
        }
    }
}

@Composable
private fun RecentSearches(recents: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    if (recents.isEmpty()) {
        EmptyState(Icons.Rounded.History, "No recent searches", "Searches you submit will appear here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Icon(Icons.Rounded.DeleteSweep, null); Text("  Clear all") }
            }
        }
        items(recents, key = { it }) { q ->
            val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                if (v != SwipeToDismissBoxValue.Settled) { onRemove(q); true } else false
            })
            SwipeToDismissBox(dismiss, backgroundContent = {}) {
                ListItem(
                    headlineContent = { Text(q) },
                    leadingContent = { Icon(Icons.Rounded.History, null) },
                    trailingContent = { IconButton(onClick = { onRemove(q) }) { Icon(Icons.Rounded.Close, "Remove") } },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.clickableItem { onPick(q) },
                )
            }
        }
    }
}

private fun Modifier.clickableItem(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

private val sample = listOf(
    Anime("1", "p", "Frieren: Beyond Journey's End", subEpisodes = 28, dubEpisodes = 28),
    Anime("2", "p", "Cowboy Bebop", subEpisodes = 26),
    Anime("3", "p", "Mushishi", subEpisodes = 26),
    Anime("4", "p", "Monster", subEpisodes = 74),
)

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun SearchPreview() = AnimadroidTheme(dynamicColor = false) {
    SearchScreen(
        SearchUiState("fri", TranslationMode.SUB, listOf("frieren", "bebop"), SearchResults.Success(sample)),
        {}, {}, {}, {}, {}, {}, {},
    )
}

@Preview(showBackground = true)
@Composable
private fun SearchErrorPreview() = AnimadroidTheme(dynamicColor = false) {
    SearchScreen(
        SearchUiState("x", results = SearchResults.Error("Cloudflare is asking for a browser check.", "https://x")),
        {}, {}, {}, {}, {}, {}, {},
    )
}
