@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class)

package dev.borovas.animadroid.navigation

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import dev.borovas.animadroid.BackupViewModel
import dev.borovas.animadroid.feature.player.PlayerRoute
import dev.borovas.animadroid.feature.player.playerEntry
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.window.core.layout.WindowSizeClass
import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.Episode
import dev.borovas.animadroid.feature.details.DetailsRoute
import dev.borovas.animadroid.feature.details.detailsEntry
import dev.borovas.animadroid.feature.downloads.DownloadsRoute
import dev.borovas.animadroid.feature.downloads.downloadsEntry
import dev.borovas.animadroid.feature.library.LibraryRoute
import dev.borovas.animadroid.feature.library.libraryEntry
import dev.borovas.animadroid.feature.recommend.RecommendCard
import dev.borovas.animadroid.feature.recommend.RecommendLifecycle
import dev.borovas.animadroid.feature.recommend.RecommendRoute
import dev.borovas.animadroid.feature.recommend.RecommendSettingsSection
import dev.borovas.animadroid.feature.recommend.recommendEntry
import dev.borovas.animadroid.feature.search.SearchRoute
import dev.borovas.animadroid.feature.search.searchEntry
import dev.borovas.animadroid.feature.settings.SettingsRoute
import dev.borovas.animadroid.feature.settings.settingsEntry

private data class TopLevel(val route: NavKey, val label: String, val icon: ImageVector)

private val topLevels = listOf(
    TopLevel(LibraryRoute, "Home", Icons.Rounded.Home),
    TopLevel(SearchRoute, "Search", Icons.Rounded.Search),
    TopLevel(DownloadsRoute, "Downloads", Icons.Rounded.Download),
    TopLevel(SettingsRoute, "Settings", Icons.Rounded.Settings),
)

@Composable
fun AnimadroidApp(aiRecommendationsEnabled: Boolean) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack(LibraryRoute)
    val top = backStack.lastOrNull()
    val showBar = topLevels.any { it.route == top }
    val selected = backStack.lastOrNull { k -> topLevels.any { it.route == k } } ?: LibraryRoute

    val wide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    fun selectTab(route: NavKey) {
        if (route == selected) return
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        if (route != LibraryRoute) backStack.add(route)
    }

    val onCloudflare: (String) -> Unit = { url ->
        // TODO: replace with the in-app WebView Cloudflare solver once available.
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    val onPlay: (Anime, List<Episode>, Int) -> Unit = { anime, episodes, index ->
        backStack.add(PlayerRoute(anime.providerId, anime, episodes, index))
    }

    val backup: BackupViewModel = hiltViewModel()
    LaunchedEffect(backup) { backup.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(backup::export) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(backup::import) }
    val aniCliLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(backup::importAniCli) }

    val listDetail = rememberListDetailSceneStrategy<NavKey>()
    val provider = remember(aiRecommendationsEnabled) {
        entryProvider<NavKey> {
            libraryEntry(
                onAnimeClick = { backStack.add(DetailsRoute(it)) },
                recommendations = if (aiRecommendationsEnabled) ({
                    RecommendCard(
                        onAnimeClick = { backStack.add(DetailsRoute(it)) },
                        onOpenSettings = { selectTab(SettingsRoute) },
                        onSeeAll = { backStack.add(RecommendRoute) },
                    )
                }) else null,
            )
            searchEntry(
                onAnimeClick = { backStack.add(DetailsRoute(it)) },
                onCloudflare = onCloudflare,
                metadata = ListDetailSceneStrategy.listPane(),
            )
            downloadsEntry()
            settingsEntry(
                onExport = { exportLauncher.launch("animadroid-backup.json") },
                onImport = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                onImportAniCli = { aniCliLauncher.launch(arrayOf("*/*")) },
                onAiToggled = { on -> if (!on) RecommendLifecycle.onDisabled(context) },
                recommendationsSection = { RecommendSettingsSection() },
            )
            recommendEntry(
                onAnimeClick = { backStack.add(DetailsRoute(it)) },
                onOpenSettings = { selectTab(SettingsRoute) },
                onBack = { backStack.removeLastOrNull() },
            )
            playerEntry(onBack = { backStack.removeLastOrNull() })
            detailsEntry(
                onBack = { backStack.removeLastOrNull() },
                onPlay = onPlay,
                onCloudflare = onCloudflare,
                metadata = ListDetailSceneStrategy.detailPane(),
            )
        }
    }

    Row(Modifier.fillMaxSize()) {
        if (wide && showBar) {
            NavigationRail {
                topLevels.forEach { t ->
                    NavigationRailItem(
                        selected = t.route == selected, onClick = { selectTab(t.route) },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                    )
                }
            }
        }
        Scaffold(
            bottomBar = {
                if (!wide && showBar) {
                    ShortNavigationBar {
                        topLevels.forEach { t ->
                            ShortNavigationBarItem(
                                selected = t.route == selected, onClick = { selectTab(t.route) },
                                icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                            )
                        }
                    }
                }
            },
        ) { pad ->
            val bottom = PaddingValues(bottom = pad.calculateBottomPadding())
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
                onBack = { backStack.removeLastOrNull() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                sceneStrategies = listOf(listDetail),
                entryProvider = provider,
            )
        }
    }
}
