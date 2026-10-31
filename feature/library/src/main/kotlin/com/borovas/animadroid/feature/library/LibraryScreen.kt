@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.borovas.animadroid.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.borovas.animadroid.core.designsystem.AnimadroidTheme
import com.borovas.animadroid.core.designsystem.AnimeCard
import com.borovas.animadroid.core.designsystem.Cover
import com.borovas.animadroid.core.designsystem.EmptyState
import com.borovas.animadroid.core.designsystem.PosterCarousel
import com.borovas.animadroid.core.designsystem.SectionHeader
import com.borovas.animadroid.core.designsystem.SkeletonLoading
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.EpisodeProgress
import com.borovas.animadroid.core.model.WatchEntry

fun EntryProviderScope<NavKey>.libraryEntry(
    onAnimeClick: (Anime) -> Unit,
    recommendations: (@Composable () -> Unit)? = null,
) {
    entry<LibraryRoute> {
        val vm: LibraryViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        LibraryScreen(state, onAnimeClick, vm::removeFromHistory, recommendations)
    }
}

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onAnimeClick: (Anime) -> Unit,
    onRemoveFromHistory: (Anime) -> Unit,
    recommendations: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text("Home") }, scrollBehavior = scroll) },
    ) { pad ->
        when {
            state.loading -> SkeletonLoading(Modifier.padding(pad))
            state.continueWatching.isEmpty() && state.favorites.isEmpty() -> Column(Modifier.padding(pad)) {
                if (recommendations != null) Box(Modifier.padding(horizontal = 16.dp)) { recommendations() }
                EmptyState(
                    Icons.Rounded.VideoLibrary, "Your library is empty",
                    "Search for a show and start watching. Progress and favorites will show up here.",
                )
            }
            else -> LazyVerticalGrid(
                GridCells.Adaptive(130.dp),
                Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = pad.calculateBottomPadding() + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (recommendations != null) item(span = { GridItemSpan(maxLineSpan) }) { recommendations() }
                if (state.continueWatching.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionHeader("Continue watching", horizontalPadding = 0.dp)
                            PosterCarousel(
                                state.continueWatching, Modifier.height(260.dp),
                                contentPadding = PaddingValues(0.dp),
                            ) { entry, mod ->
                                ContinueCard(entry, { onAnimeClick(entry.anime) }, { onRemoveFromHistory(entry.anime) }, mod)
                            }
                        }
                    }
                }
                if (state.favorites.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Favorites", horizontalPadding = 0.dp) }
                    items(state.favorites, key = { "f" + it.providerId + it.id }) { a ->
                        AnimeCard(a, onClick = { onAnimeClick(a) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(entry: WatchEntry, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick)) {
        Cover(entry.anime.coverUrl, entry.anime.title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)), startY = 250f)))
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
        ) { Icon(Icons.Rounded.Close, "Remove from history") }
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.anime.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Episode ${entry.last.episodeNumber}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            LinearWavyProgressIndicator(progress = { entry.last.fraction }, Modifier.fillMaxWidth())
        }
    }
}

private fun sampleEntry(i: Int, title: String, frac: Float) = WatchEntry(
    Anime("$i", "hianime", title), EpisodeProgress("hianime", "$i", "${i + 2}", (frac * 1400).toLong(), 1400, 0),
)

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun LibraryPreview() = AnimadroidTheme(dynamicColor = false) {
    LibraryScreen(
        LibraryUiState(
            continueWatching = listOf(sampleEntry(1, "Frieren", .4f), sampleEntry(2, "Cowboy Bebop", .7f), sampleEntry(3, "Mushishi", .2f)),
            favorites = listOf(Anime("9", "h", "One Piece", subEpisodes = 1100), Anime("8", "h", "Monster", subEpisodes = 74)),
            loading = false,
        ),
        {}, {},
    )
}

@Preview(showBackground = true)
@Composable
private fun LibraryEmptyPreview() = AnimadroidTheme(dynamicColor = false) {
    LibraryScreen(LibraryUiState(loading = false), {}, {})
}
