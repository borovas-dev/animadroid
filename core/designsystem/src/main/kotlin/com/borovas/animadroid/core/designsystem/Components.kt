@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.borovas.animadroid.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.carousel.CarouselState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.borovas.animadroid.core.model.Anime

/** Cover image + title + optional episode badge / progress. Expressive large-rounded poster. */
@Composable
fun AnimeCard(
    anime: Anime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    progress: Float? = null,
) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Cover(anime.coverUrl, anime.title, Modifier.fillMaxSize())
            val b = badge ?: episodeBadge(anime)
            if (b != null) {
                Surface(
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) { Text(b, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium) }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        Text(
            anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private fun episodeBadge(a: Anime): String? {
    val sub = a.subEpisodes
    val dub = a.dubEpisodes
    return when {
        sub != null && dub != null && dub > 0 -> "SUB $sub | DUB $dub"
        sub != null -> "$sub eps"
        dub != null -> "DUB $dub"
        else -> null
    }
}

@Composable
fun Cover(url: String?, contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    if (url == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        AsyncImage(model = url, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
    }
}

/** Horizontal multi-browse carousel of arbitrary posters. */
@Composable
fun <T> PosterCarousel(
    items: List<T>,
    modifier: Modifier = Modifier,
    preferredItemWidth: Dp = 180.dp,
    state: CarouselState = rememberCarouselState { items.size },
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    item: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = preferredItemWidth,
        modifier = modifier.fillMaxWidth(),
        itemSpacing = 8.dp,
        contentPadding = contentPadding,
    ) { index ->
        item(items[index], Modifier.maskClip(RoundedCornerShape(28.dp)))
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ShapedIcon(icon)
        Text(title, Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            message, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        if (action != null) Box(Modifier.padding(top = 24.dp)) { action() }
    }
}

/** Icon on a MaterialShapes cookie illustration. */
@Composable
fun ShapedIcon(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 112.dp) {
    Box(
        modifier.size(size).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size / 2), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionLabel: String = "Retry",
    icon: ImageVector = Icons.Rounded.ErrorOutline,
    title: String = "Something went wrong",
) {
    EmptyState(icon, title, message, modifier) {
        if (onRetry != null) Button(onClick = onRetry) { Text(actionLabel) }
    }
}

@Composable
fun SkeletonLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(64.dp)) }
}

@Composable
fun Modifier.shimmer(): Modifier {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    return this.background(
        Brush.linearGradient(
            colors = listOf(base, hi, base),
            start = Offset(x * 600f, 0f),
            end = Offset(x * 600f + 400f, 200f),
        ),
    )
}

@Composable
fun AnimeCardPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(24.dp)).shimmer())
        Box(Modifier.size(width = 100.dp, height = 14.dp).clip(RoundedCornerShape(7.dp)).shimmer())
    }
}

@Composable
fun PlaceholderGrid(modifier: Modifier = Modifier, count: Int = 12) {
    LazyVerticalGrid(
        GridCells.Adaptive(120.dp), modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false,
    ) { items(count) { AnimeCardPlaceholder() } }
}

/** Connected button group of single-select toggle buttons (Material 3 Expressive). */
@Composable
fun <T> ConnectedToggleGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, option ->
            val shapes = when (i) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                modifier = Modifier.weight(1f),
                shapes = shapes,
            ) { Text(label(option)) }
        }
    }
}
