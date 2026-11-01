@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.borovas.animadroid.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.borovas.animadroid.core.designsystem.AnimadroidTheme
import com.borovas.animadroid.core.designsystem.ConnectedToggleGroup
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.QualityPreference
import com.borovas.animadroid.core.model.SkipBehavior
import com.borovas.animadroid.core.model.ThemeMode
import com.borovas.animadroid.core.model.TranslationMode
import kotlin.math.roundToInt

/** Provider ids as registered by the provider modules. Default stays "hianime" (see [AppSettings]). */
private val PROVIDER_CHOICES = listOf("hianime" to R.string.provider_hianime, "shinden" to R.string.provider_shinden)

private const val SOURCE_URL = "https://github.com/borovas-dev/animadroid"

fun EntryProviderScope<NavKey>.settingsEntry(
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onImportAniCli: () -> Unit = {},
    onAiToggled: (Boolean) -> Unit = {},
    recommendationsSection: (@Composable () -> Unit)? = null,
) {
    entry<SettingsRoute> {
        val vm: SettingsViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        SettingsScreen(state.settings, vm::update, onExport, onImport, onImportAniCli, onAiToggled = onAiToggled, recommendationsSection = recommendationsSection)
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onImportAniCli: () -> Unit,
    modifier: Modifier = Modifier,
    onAiToggled: (Boolean) -> Unit = {},
    recommendationsSection: (@Composable () -> Unit)? = null,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    val uri = LocalUriHandler.current
    Scaffold(
        modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text("Settings") }, scrollBehavior = scroll) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Group("Playback") {
                    Labeled(stringResource(R.string.settings_source)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PROVIDER_CHOICES.forEach { (id, label) ->
                                FilterChip(
                                    selected = settings.providerId == id, onClick = { onUpdate { it.copy(providerId = id) } },
                                    label = { Text(stringResource(label)) },
                                )
                            }
                        }
                        if (settings.providerId == "shinden") {
                            Text(
                                stringResource(R.string.settings_source_shinden_hint),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Labeled("Audio") {
                        ConnectedToggleGroup(TranslationMode.entries, settings.mode, { m -> onUpdate { it.copy(mode = m) } }, { it.name }, Modifier.fillMaxWidth())
                    }
                    Labeled("Preferred quality") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QualityPreference.entries.forEach { q ->
                                FilterChip(
                                    selected = settings.quality == q, onClick = { onUpdate { it.copy(quality = q) } },
                                    label = { Text(q.label()) },
                                )
                            }
                        }
                    }
                    SwitchRow("Autoplay next episode", "Start the next episode when one finishes", settings.autoPlayNext) { v -> onUpdate { it.copy(autoPlayNext = v) } }
                    SwitchRow("Seek bar previews", "Show a thumbnail while dragging the seek bar", settings.scrubPreviewEnabled) { v -> onUpdate { it.copy(scrubPreviewEnabled = v) } }
                    if (settings.scrubPreviewEnabled) {
                        SwitchRow("Previews on mobile data", "Decode previews from the stream even on metered networks (uses data)", settings.scrubPreviewMobileData) { v -> onUpdate { it.copy(scrubPreviewMobileData = v) } }
                    }
                }
            }
            item {
                Group("Ani-skip") {
                    SwitchRow("Skip openings & endings", "Uses ani-skip timestamps when available", settings.aniSkipEnabled) { v -> onUpdate { it.copy(aniSkipEnabled = v) } }
                    if (settings.aniSkipEnabled) {
                        Labeled("Behavior") {
                            ConnectedToggleGroup(
                                SkipBehavior.entries, settings.skipBehavior, { b -> onUpdate { it.copy(skipBehavior = b) } },
                                { if (it == SkipBehavior.AUTO) "Auto skip" else "Show button" }, Modifier.fillMaxWidth(),
                            )
                        }
                        Labeled("Offset: ${settings.skipOffsetSec} s") {
                            Slider(
                                value = settings.skipOffsetSec.toFloat(), onValueChange = { v -> onUpdate { it.copy(skipOffsetSec = v.roundToInt()) } },
                                valueRange = 0f..5f, steps = 4,
                            )
                        }
                    }
                }
            }
            item {
                Group("Appearance") {
                    Labeled("Theme") {
                        ConnectedToggleGroup(
                            ThemeMode.entries, settings.themeMode, { t -> onUpdate { it.copy(themeMode = t) } },
                            { it.name.lowercase().replaceFirstChar(Char::titlecase) }, Modifier.fillMaxWidth(),
                        )
                    }
                    SwitchRow("Dynamic color", "Use colors from your wallpaper", settings.dynamicColor) { v -> onUpdate { it.copy(dynamicColor = v) } }
                }
            }
            item {
                Group("Data") {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onExport, Modifier.weight(1f)) { Text("Export") }
                        FilledTonalButton(onClick = onImport, Modifier.weight(1f)) { Text("Import") }
                    }
                    OutlinedButton(onClick = onImportAniCli, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        Text("Import ani-cli history")
                    }
                }
            }
            item {
                Group("Downloads") {
                    SwitchRow("Download on Wi-Fi only", "Pause downloads on mobile data", settings.downloadWifiOnly) { v -> onUpdate { it.copy(downloadWifiOnly = v) } }
                }
            }
            item {
                Group("Recommendations") {
                    SwitchRow(
                        "On-device AI recommendations",
                        "Uses the Gemma model, running entirely on your device. Requires a one-time download of about 2 GB. Nothing leaves your phone.",
                        settings.aiRecommendationsEnabled,
                    ) { v -> onUpdate { it.copy(aiRecommendationsEnabled = v) }; onAiToggled(v) }
                    if (settings.aiRecommendationsEnabled) recommendationsSection?.invoke()
                }
            }
            item {
                Group("About") {
                    ListItem(headlineContent = { Text("Animadroid") }, supportingContent = { Text("Version $version"); }, colors = transparent())
                    ListItem(headlineContent = { Text("License") }, supportingContent = { Text("GNU GPL-3.0") }, colors = transparent())
                    ListItem(
                        headlineContent = { Text("Credits") },
                        supportingContent = { Text("A native port of ani-cli by pystardust and contributors. Skip timestamps from ani-skip (aniskip.com).") },
                        colors = transparent(),
                    )
                    TextButton(onClick = { uri.openUri(SOURCE_URL) }, Modifier.padding(horizontal = 8.dp)) { Text("Source code on GitHub") }
                }
            }
        }
    }
}

private fun QualityPreference.label() = when (this) {
    QualityPreference.BEST -> "Best"
    QualityPreference.WORST -> "Worst"
    QualityPreference.P1080 -> "1080p"
    QualityPreference.P720 -> "720p"
    QualityPreference.P480 -> "480p"
    QualityPreference.P360 -> "360p"
}

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "dev"

@Composable
private fun transparent() = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title, Modifier.padding(start = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        )
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked, onChange) },
        colors = transparent(),
    )
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SettingsPreview() = AnimadroidTheme(dynamicColor = false) {
    SettingsScreen(AppSettings(), {}, {}, {}, {})
}
