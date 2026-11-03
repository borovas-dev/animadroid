@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.borovas.animadroid.feature.settings

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.animation.AnimatedVisibility
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import com.borovas.animadroid.core.model.SourceStrategy
import com.borovas.animadroid.core.model.ThemeMode
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.feature.subtitles.MlKitLanguages
import com.borovas.animadroid.feature.subtitles.SubtitleLanguages
import kotlin.math.roundToInt

private const val SOURCE_URL = "https://github.com/borovas-dev/animadroid"

fun EntryProviderScope<NavKey>.settingsEntry(
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onImportAniCli: () -> Unit = {},
    onAiToggled: (Boolean) -> Unit = {},
    recommendationsSection: (@Composable () -> Unit)? = null,
    onOpenSourceHealth: () -> Unit = {},
) {
    entry<SettingsRoute> {
        val vm: SettingsViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        SettingsScreen(
            state.settings, vm::update, onExport, onImport, onImportAniCli, onAiToggled = onAiToggled, recommendationsSection = recommendationsSection,
            trackingSection = { TrackingContent() },
            languages = state.languages, sources = state.sources, onMoveSource = vm::moveSource, onSourceEnabled = vm::setSourceEnabled,
            onOpenSourceHealth = onOpenSourceHealth,
        )
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
    trackingSection: (@Composable () -> Unit)? = null,
    languages: List<String> = BASE_LANGUAGES,
    sources: List<SourceItem> = emptyList(),
    onMoveSource: (String, Int) -> Unit = { _, _ -> },
    onSourceEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onOpenSourceHealth: () -> Unit = {},
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    val uri = LocalUriHandler.current
    val strategyReliable = stringResource(R.string.strategy_reliable)
    val strategyBest = stringResource(R.string.strategy_best_quality)
    val audioSub = stringResource(R.string.audio_sub)
    val audioDub = stringResource(R.string.audio_dub)
    val skipAuto = stringResource(R.string.skip_auto)
    val skipButton = stringResource(R.string.skip_button)
    val themeSystem = stringResource(R.string.theme_system)
    val themeLight = stringResource(R.string.theme_light)
    val themeDark = stringResource(R.string.theme_dark)
    Scaffold(
        modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text(stringResource(R.string.settings_title)) }, scrollBehavior = scroll) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Group(stringResource(R.string.settings_playback)) {
                    Labeled(stringResource(R.string.settings_content_language)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            languages.forEach { code ->
                                FilterChip(
                                    selected = settings.contentLanguage == code, onClick = { onUpdate { it.copy(contentLanguage = code) } },
                                    label = { Text(languageName(code)) },
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.settings_content_language_desc),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Labeled(stringResource(R.string.settings_audio)) {
                        ConnectedToggleGroup(
                            TranslationMode.entries, settings.mode, { m -> onUpdate { it.copy(mode = m) } },
                            { if (it == TranslationMode.SUB) audioSub else audioDub }, Modifier.fillMaxWidth())
                    }
                    Labeled(stringResource(R.string.settings_quality)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QualityPreference.entries.forEach { q ->
                                FilterChip(
                                    selected = settings.quality == q, onClick = { onUpdate { it.copy(quality = q) } },
                                    label = { Text(q.label()) },
                                )
                            }
                        }
                    }
                    SwitchRow(stringResource(R.string.settings_autoplay), stringResource(R.string.settings_autoplay_desc), settings.autoPlayNext) { v -> onUpdate { it.copy(autoPlayNext = v) } }
                    SwitchRow(stringResource(R.string.settings_prefetch), stringResource(R.string.settings_prefetch_desc), settings.prefetchNextEpisode) { v -> onUpdate { it.copy(prefetchNextEpisode = v) } }
                    if (settings.prefetchNextEpisode) {
                        SwitchRow(stringResource(R.string.settings_prefetch_mobile), stringResource(R.string.settings_prefetch_mobile_desc), settings.prefetchOnMetered) { v -> onUpdate { it.copy(prefetchOnMetered = v) } }
                    }
                    SwitchRow(stringResource(R.string.settings_dub), stringResource(R.string.settings_dub_desc), settings.dubEnabled) { v -> onUpdate { it.copy(dubEnabled = v) } }
                    SwitchRow(stringResource(R.string.settings_scrub), stringResource(R.string.settings_scrub_desc), settings.scrubPreviewEnabled) { v -> onUpdate { it.copy(scrubPreviewEnabled = v) } }
                    if (settings.scrubPreviewEnabled) {
                        SwitchRow(stringResource(R.string.settings_scrub_mobile), stringResource(R.string.settings_scrub_mobile_desc), settings.scrubPreviewMobileData) { v -> onUpdate { it.copy(scrubPreviewMobileData = v) } }
                    }
                }
            }
            item {
                Group(stringResource(R.string.settings_subtitles_group)) {
                    SubtitleLanguageRow(settings.subtitleLanguage) { code -> onUpdate { it.copy(subtitleLanguage = code) } }
                    if (settings.subtitleLanguage.isNotEmpty()) {
                        SwitchRow(
                            stringResource(R.string.settings_translate_subtitles),
                            stringResource(R.string.settings_translate_subtitles_desc),
                            settings.translateSubtitles,
                        ) { v -> onUpdate { it.copy(translateSubtitles = v) } }
                    }
                }
            }
            item {
                Group(stringResource(R.string.settings_aniskip)) {
                    SwitchRow(stringResource(R.string.settings_skip), stringResource(R.string.settings_skip_desc), settings.aniSkipEnabled) { v -> onUpdate { it.copy(aniSkipEnabled = v) } }
                    if (settings.aniSkipEnabled) {
                        Labeled(stringResource(R.string.settings_skip_behavior)) {
                            ConnectedToggleGroup(
                                SkipBehavior.entries, settings.skipBehavior, { b -> onUpdate { it.copy(skipBehavior = b) } },
                                { if (it == SkipBehavior.AUTO) skipAuto else skipButton }, Modifier.fillMaxWidth(),
                            )
                        }
                        if (settings.skipBehavior == SkipBehavior.BUTTON) {
                            SwitchRow(stringResource(R.string.settings_auto_skip_intro), stringResource(R.string.settings_auto_skip_desc), settings.autoSkipIntro) { v -> onUpdate { it.copy(autoSkipIntro = v) } }
                            SwitchRow(stringResource(R.string.settings_auto_skip_outro), stringResource(R.string.settings_auto_skip_desc), settings.autoSkipOutro) { v -> onUpdate { it.copy(autoSkipOutro = v) } }
                        }
                        Labeled(stringResource(R.string.settings_skip_offset, settings.skipOffsetSec)) {
                            Slider(
                                value = settings.skipOffsetSec.toFloat(), onValueChange = { v -> onUpdate { it.copy(skipOffsetSec = v.roundToInt()) } },
                                valueRange = 0f..5f, steps = 4,
                            )
                        }
                    }
                }
            }
            item {
                Group(stringResource(R.string.settings_appearance)) {
                    Labeled(stringResource(R.string.settings_theme)) {
                        ConnectedToggleGroup(
                            ThemeMode.entries, settings.themeMode, { t -> onUpdate { it.copy(themeMode = t) } },
                            { when (it) { ThemeMode.SYSTEM -> themeSystem; ThemeMode.LIGHT -> themeLight; ThemeMode.DARK -> themeDark } }, Modifier.fillMaxWidth(),
                        )
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_app_language)) },
                            supportingContent = { Text(stringResource(R.string.settings_app_language_desc)) },
                            colors = transparent(),
                            modifier = Modifier.clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                    }
                    SwitchRow(stringResource(R.string.settings_dynamic), stringResource(R.string.settings_dynamic_desc), settings.dynamicColor) { v -> onUpdate { it.copy(dynamicColor = v) } }
                }
            }
            item {
                Group(stringResource(R.string.settings_downloads)) {
                    SwitchRow(stringResource(R.string.settings_wifi_only), stringResource(R.string.settings_wifi_only_desc), settings.downloadWifiOnly) { v -> onUpdate { it.copy(downloadWifiOnly = v) } }
                }
            }
            item {
                Group(stringResource(R.string.settings_recommendations)) {
                    SwitchRow(
                        stringResource(R.string.settings_ai),
                        stringResource(R.string.settings_ai_desc),
                        settings.aiRecommendationsEnabled,
                    ) { v -> onUpdate { it.copy(aiRecommendationsEnabled = v) }; onAiToggled(v) }
                    if (settings.aiRecommendationsEnabled) recommendationsSection?.invoke()
                }
            }
            item {
                var advancedOpen by rememberSaveable { mutableStateOf(false) }
                CollapsibleGroup(stringResource(R.string.settings_advanced), advancedOpen, { advancedOpen = !advancedOpen }) {
                    Labeled(stringResource(R.string.settings_source_strategy)) {
                        ConnectedToggleGroup(
                            SourceStrategy.entries, settings.sourceStrategy, { v -> onUpdate { it.copy(sourceStrategy = v) } },
                            { if (it == SourceStrategy.RELIABLE) strategyReliable else strategyBest },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    AdvancedSources(sources, onMoveSource, onSourceEnabled)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_source_health)) },
                        supportingContent = { Text(stringResource(R.string.settings_source_health_desc)) },
                        modifier = Modifier.clickable(onClick = onOpenSourceHealth),
                        colors = transparent(),
                    )
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onExport, Modifier.weight(1f)) { Text(stringResource(R.string.settings_export)) }
                        FilledTonalButton(onClick = onImport, Modifier.weight(1f)) { Text(stringResource(R.string.settings_import)) }
                    }
                    OutlinedButton(onClick = onImportAniCli, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        Text(stringResource(R.string.settings_import_anicli))
                    }
                    if (trackingSection != null) trackingSection()
                }
            }
            item {
                Group(stringResource(R.string.settings_about)) {
                    SwitchRow(stringResource(R.string.settings_check_updates), stringResource(R.string.settings_check_updates_desc), settings.checkForUpdates) { v -> onUpdate { it.copy(checkForUpdates = v) } }
                    if (settings.checkForUpdates) SwitchRow(stringResource(R.string.settings_prereleases), stringResource(R.string.settings_prereleases_desc), settings.includePrereleases) { v -> onUpdate { it.copy(includePrereleases = v) } }
                    ListItem(headlineContent = { Text("Animadroid") }, supportingContent = { Text(stringResource(R.string.settings_version, version)) }, colors = transparent())
                    ListItem(headlineContent = { Text(stringResource(R.string.settings_license)) }, supportingContent = { Text("GNU GPL-3.0") }, colors = transparent())
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_credits)) },
                        supportingContent = { Text(stringResource(R.string.settings_credits_desc)) },
                        colors = transparent(),
                    )
                    TextButton(onClick = { uri.openUri(SOURCE_URL) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.settings_source_code)) }
                }
            }
        }
    }
}

@Composable
private fun QualityPreference.label() = when (this) {
    QualityPreference.BEST -> stringResource(R.string.quality_best)
    QualityPreference.WORST -> stringResource(R.string.quality_worst)
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

/** A [Group] whose body is hidden until the header (title + chevron) is tapped: "Advanced >" / "Advanced v". */
@Composable
private fun CollapsibleGroup(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = title, role = Role.Button, onClick = onToggle)
                .padding(start = 16.dp, top = 4.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(expanded) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth()) { content() }
            }
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

/** Own-language name of a content language code; "auto" is localised. */
@Composable
private fun languageName(code: String): String =
    if (code == "auto") stringResource(R.string.language_auto)
    else java.util.Locale.forLanguageTag(code).let { it.getDisplayLanguage(it).replaceFirstChar(Char::titlecase) }

@Composable
private fun AdvancedSources(sources: List<SourceItem>, onMove: (String, Int) -> Unit, onEnabled: (String, Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_advanced_sources)) },
            supportingContent = { Text(stringResource(R.string.settings_advanced_sources_desc)) },
            trailingContent = { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null) },
            modifier = Modifier.clickable { open = !open },
            colors = transparent(),
        )
        if (open) {
            sources.forEachIndexed { i, src ->
                ListItem(
                    headlineContent = { Text(src.name) },
                    leadingContent = { Switch(src.enabled, { onEnabled(src.id, it) }) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onMove(src.id, -1) }, enabled = i > 0) {
                                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.settings_move_up))
                            }
                            IconButton(onClick = { onMove(src.id, 1) }, enabled = i < sources.lastIndex) {
                                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.settings_move_down))
                            }
                        }
                    },
                    colors = transparent(),
                )
            }
        }
    }
}

@Composable
private fun SubtitleLanguageRow(selected: String, onSelect: (String) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Own-language names, sorted by name; computed once (ML Kit's language list is static).
    val languages = remember {
        MlKitLanguages.all().map { it to SubtitleLanguages.displayName(it) }.sortedBy { it.second.lowercase() }
    }
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_subtitle_language)) },
            supportingContent = {
                Text(if (selected.isEmpty()) stringResource(R.string.settings_subtitle_language_off) else SubtitleLanguages.displayName(selected))
            },
            modifier = Modifier.clickable { open = true },
            colors = transparent(),
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_subtitle_language_off)) },
                onClick = { onSelect(""); open = false },
            )
            languages.forEach { (code, name) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(code); open = false })
            }
        }
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
