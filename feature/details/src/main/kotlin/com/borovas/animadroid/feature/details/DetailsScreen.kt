@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.borovas.animadroid.feature.details

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.borovas.animadroid.core.designsystem.AnimadroidTheme
import dev.borovas.animadroid.core.designsystem.ConnectedToggleGroup
import dev.borovas.animadroid.core.designsystem.Cover
import dev.borovas.animadroid.core.designsystem.EmptyState
import dev.borovas.animadroid.core.designsystem.ErrorState
import dev.borovas.animadroid.core.designsystem.SectionHeader
import dev.borovas.animadroid.core.designsystem.SkeletonLoading
import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.Episode
import dev.borovas.animadroid.core.model.EpisodeProgress
import dev.borovas.animadroid.core.model.TranslationMode

fun EntryProviderScope<NavKey>.detailsEntry(
    onBack: () -> Unit,
    onPlay: (anime: Anime, episodes: List<Episode>, index: Int) -> Unit,
    onCloudflare: (url: String) -> Unit,
    metadata: Map<String, Any> = emptyMap(),
) {
    entry<DetailsRoute>(metadata = metadata) { key ->
        val vm = hiltViewModel<DetailsViewModel, DetailsViewModel.Factory>(
            key = key.anime.providerId + key.anime.id,
            creationCallback = { it.create(key.anime) },
        )
        val state by vm.state.collectAsStateWithLifecycle()
        DetailsScreen(
            state = state,
            onBack = onBack,
            onToggleFavorite = vm::toggleFavorite,
            onModeChange = vm::setMode,
            onRetry = vm::reload,
            onPlay = { index -> onPlay(state.anime, state.episodes, index) },
            onCloudflare = onCloudflare,
        )
    }
}

@Composable
fun DetailsScreen(
    state: DetailsUiState,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onModeChange: (TranslationMode) -> Unit,
    onRetry: () -> Unit,
    onPlay: (index: Int) -> Unit,
    onCloudflare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val target = state.playTarget
    Scaffold(
        modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(state.anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                subtitle = state.anime.year?.let { { Text(it.toString()) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        if (state.isFavorite) Icon(Icons.Rounded.Favorite, "Remove from favorites", tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.Rounded.FavoriteBorder, "Add to favorites")
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (target != null) {
                ExtendedFloatingActionButton(
                    onClick = { onPlay(target.index) },
                    icon = { Icon(Icons.Rounded.PlayArrow, null) },
                    text = { Text(target.label) },
                )
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + 96.dp),
        ) {
            item { Header(state) }
            if (state.availableModes.size > 1) item {
                ConnectedToggleGroup(
                    state.availableModes, state.mode, onModeChange, { it.name },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.anime.synopsis?.takeIf { it.isNotBlank() }?.let { s -> item { Synopsis(s) } }
            item { SectionHeader("Episodes") }
            when {
                state.loading -> item { SkeletonLoading(Modifier.fillMaxWidth().height(160.dp)) }
                state.error != null -> item {
                    ErrorState(
                        state.error,
                        onRetry = { if (state.cloudflareUrl != null) onCloudflare(state.cloudflareUrl) else onRetry() },
                        actionLabel = if (state.cloudflareUrl != null) "Verify in browser" else "Retry",
                        icon = if (state.cloudflareUrl != null) Icons.Rounded.Language else Icons.Rounded.Close,
                        modifier = Modifier.height(280.dp),
                    )
                }
                state.episodes.isEmpty() -> item {
                    EmptyState(Icons.Rounded.PlayArrow, "No episodes", "No ${state.mode.name} episodes were found.", Modifier.height(240.dp))
                }
                else -> items(state.episodes, key = { it.id }) { ep ->
                    val index = state.episodes.indexOf(ep)
                    EpisodeRow(ep, state.progress[ep.number], onClick = { onPlay(index) })
                }
            }
        }
    }
}

@Composable
private fun Header(state: DetailsUiState) {
    Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
        Cover(state.anime.coverUrl, null, Modifier.fillMaxSize().blur(36.dp).alpha(0.45f), ContentScale.Crop)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Cover(
                state.anime.coverUrl, state.anime.title,
                Modifier.width(140.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(28.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val a = state.anime
                a.subEpisodes?.let { Text("SUB: $it episodes", style = MaterialTheme.typography.labelLarge) }
                a.dubEpisodes?.let { Text("DUB: $it episodes", style = MaterialTheme.typography.labelLarge) }
                Text(a.providerId, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Synopsis(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize()) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Read more") }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, progress: EpisodeProgress?, onClick: () -> Unit) {
    val finished = progress?.isFinished == true
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (finished) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Episode ${ep.number}", style = MaterialTheme.typography.titleMedium)
                ep.title?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (progress != null && !finished && progress.fraction > 0f) {
                    LinearWavyProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (finished) Icon(Icons.Rounded.CheckCircle, "Watched", tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val sampleAnime = Anime(
    "1", "hianime", "Frieren: Beyond Journey's End",
    synopsis = "The demon king has been defeated, and the victorious hero party returns home. The elf mage Frieren then sets out on a new journey to understand humans better and to fulfil a final promise.",
    subEpisodes = 28, dubEpisodes = 28, year = 2023,
)

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun DetailsPreview() = AnimadroidTheme(dynamicColor = false) {
    val eps = (1..12).map { Episode("e$it", it.toString(), if (it % 3 == 0) "A title that is fairly long" else null) }
    DetailsScreen(
        DetailsUiState(
            sampleAnime, episodes = eps, loading = false, isFavorite = true,
            progress = mapOf(
                "1" to EpisodeProgress("hianime", "1", "1", 1300, 1400, 1),
                "2" to EpisodeProgress("hianime", "1", "2", 500, 1400, 2),
            ),
        ),
        {}, {}, {}, {}, {}, {},
    )
}

@Preview(showBackground = true)
@Composable
private fun DetailsLoadingPreview() = AnimadroidTheme(dynamicColor = false) {
    DetailsScreen(DetailsUiState(sampleAnime), {}, {}, {}, {}, {}, {})
}
