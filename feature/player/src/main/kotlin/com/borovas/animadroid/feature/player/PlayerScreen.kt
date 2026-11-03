@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.borovas.animadroid.feature.player

import com.borovas.animadroid.core.designsystem.R as DR
import android.content.pm.ActivityInfo
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import com.borovas.animadroid.core.designsystem.LocalIsTelevision
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.ContentFrame
import com.borovas.animadroid.feature.aniskip.SkipSegment
import kotlinx.coroutines.delay

private const val AUTO_HIDE_MS = 3_000L
private const val DOUBLE_TAP_SEEK_MS = 10_000L

/** Full-screen immersive player. Hosts the controls overlay, gestures, PiP and system-bar handling. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(route: PlayerRoute, onBack: () -> Unit, modifier: Modifier = Modifier, onCloudflare: (String) -> Unit = {}) {
    val vm = hiltViewModel<PlayerViewModel, PlayerViewModel.Factory>(creationCallback = { it.create(route) })
    val state by vm.state.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val scrubPreview by vm.scrubPreview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    val installVoiceLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { vm.recheckDub() }
    var interaction by remember { mutableIntStateOf(0) }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    var hud by remember { mutableStateOf<HudInfo?>(null) }
    var seekFlash by remember { mutableStateOf<String?>(null) }

    val tv = LocalIsTelevision.current
    val largeScreen = tv || (activity?.resources?.configuration?.smallestScreenWidthDp ?: 0) >= 600
    var fullscreen by rememberSaveable { mutableStateOf(true) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }

    // System bars follow the fullscreen flag (F / Esc on a keyboard).
    LaunchedEffect(activity, fullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val c = WindowInsetsControllerCompat(window, view)
        if (fullscreen) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
    }

    // Immersive, landscape-preferred on phones, keep screen on.
    DisposableEffect(activity) {
        val window = activity?.window
        val previousOrientation = activity?.requestedOrientation
        // Large screens / TV / free-form windows ignore or fight an orientation lock; only lock on phone-sized devices.
        if (!largeScreen) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, view).systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            if (window != null) {
                WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
                window.attributes = window.attributes.also { it.screenBrightness = -1f }
            }
            previousOrientation?.let { activity.requestedOrientation = it }
        }
    }

    // PiP mode tracking + params.
    DisposableEffect(activity) {
        val host = activity as? ComponentActivity
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        host?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            host?.removeOnPictureInPictureModeChangedListener(listener)
            PictureInPictureController.disable(activity)
        }
    }
    LaunchedEffect(state.isPlaying, state.videoAspect, activity) {
        activity?.let { PictureInPictureController.update(it, state.isPlaying, state.videoAspect) }
    }

    BackHandler { if (locked) Unit else if (tv && controlsVisible) { controlsVisible = false } else onBack() }

    val currentVisible by rememberUpdatedState(controlsVisible)
    LaunchedEffect(controlsVisible, state.isPlaying, interaction, sheetOpen) {
        if (currentVisible && state.isPlaying && !sheetOpen) {
            delay(AUTO_HIDE_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }
    LaunchedEffect(seekFlash) { if (seekFlash != null) { delay(700); seekFlash = null } }

    // Keyboard focus lives on the root so shortcuts work without clicking; on TV the play button takes it while controls show.
    LaunchedEffect(tv, controlsVisible, inPip) {
        if (inPip) return@LaunchedEffect
        runCatching { if (tv && controlsVisible) playFocus.requestFocus() else rootFocus.requestFocus() }
    }

    fun volumeStep(raise: Boolean) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        hud = HudInfo(Icons.Rounded.VolumeUp, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
    }

    Box(
        modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus).focusable()
            .onPreviewKeyEvent { e ->
                if (locked || inPip) return@onPreviewKeyEvent false
                val action = mapPlayerKey(
                    e.nativeKeyEvent.keyCode, controlsVisible, tv, fullscreen,
                    ctrlOrAltOrMeta = e.isCtrlPressed || e.isAltPressed || e.isMetaPressed,
                ) ?: return@onPreviewKeyEvent false
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                when (action) {
                    PlayerKeyAction.PlayPause -> { vm.togglePlay(); interaction++ }
                    PlayerKeyAction.PlayPauseAndShowControls -> { vm.togglePlay(); controlsVisible = true; interaction++ }
                    PlayerKeyAction.ShowControls -> { controlsVisible = true; interaction++ }
                    PlayerKeyAction.SeekBack -> { vm.seekBy(-KEY_SEEK_STEP_MS); seekFlash = "-10s"; interaction++ }
                    PlayerKeyAction.SeekForward -> { vm.seekBy(KEY_SEEK_STEP_MS); seekFlash = "+10s"; interaction++ }
                    PlayerKeyAction.VolumeUp -> volumeStep(true)
                    PlayerKeyAction.VolumeDown -> volumeStep(false)
                    PlayerKeyAction.ToggleFullscreen -> fullscreen = !fullscreen
                    PlayerKeyAction.ExitFullscreen -> fullscreen = false
                    PlayerKeyAction.ToggleMute -> {
                        val muted = vm.toggleMute()
                        hud = HudInfo(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (muted) 0 else 100)
                    }
                }
                true
            },
    ) {
        player?.let { p ->
            ContentFrame(p, Modifier.fillMaxSize().pipSourceRect(), contentScale = ContentScale.Fit)
            val translatedCues by vm.translatedCues.collectAsStateWithLifecycle()
            SubtitleOverlay(
                p, bottomPadding = if (controlsVisible && !inPip) 72.dp else 16.dp,
                translatedCues = translatedCues, translatedActive = state.translatedSelected,
            )
        }

        if (!inPip) {
            GestureLayer(
                locked = locked,
                onToggleControls = { controlsVisible = !controlsVisible; interaction++ },
                onDoubleTapSeek = { forward ->
                    vm.seekBy(if (forward) DOUBLE_TAP_SEEK_MS else -DOUBLE_TAP_SEEK_MS)
                    seekFlash = if (forward) "+10s" else "-10s"
                },
                onHud = { hud = it },
            )

            seekFlash?.let {
                Text(
                    it, color = Color.White, style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(0.5f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            hud?.let { HudPill(it, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 16.dp)) }

            TranslationMessages(
                state = state,
                onConfirmDownload = vm::confirmPackDownload,
                onDeclineDownload = vm::declinePackDownload,
                onDismissNotice = vm::dismissNotice,
                modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp),
            )

            state.autoSkipped?.let { seg ->
                AutoSkipUndo(
                    seg, onUndo = vm::undoAutoSkip, onDismiss = vm::dismissAutoSkip,
                    modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 96.dp),
                )
            }

            if (state.loading || state.buffering) {
                LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
            }

            val err = state.error
            if (err != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp).background(Color.Black.copy(0.7f), RoundedCornerShape(28.dp)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(err.message, color = Color.White, textAlign = TextAlign.Center)
                    if (err is PlayerError.Cloudflare) Button(onClick = { onCloudflare(err.url) }) { Text(stringResource(DR.string.verify_in_browser)) }
                    else Button(onClick = vm::retry) { Text(stringResource(DR.string.common_retry)) }
                    TextButtonLike(stringResource(DR.string.common_back), onBack)
                }
            }

            // Skip button (visible regardless of controls).
            AnimatedVisibility(
                visible = state.skipButton != null && !locked && err == null,
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 24.dp, bottom = 96.dp),
                enter = fadeIn(), exit = fadeOut(),
            ) {
                val seg = state.skipButton
                ExtendedFloatingActionButton(
                    onClick = vm::onSkipButton,
                    icon = { Icon(Icons.Rounded.SkipNext, null) },
                    text = { Text(stringResource(if (seg == SkipSegment.ENDING) R.string.player_skip_ending else R.string.player_skip_opening)) },
                )
            }

            AnimatedVisibility(visible = controlsVisible && err == null, enter = fadeIn(), exit = fadeOut()) {
                PlayerControls(
                    state = state,
                    positionFlow = vm.position,
                    locked = locked,
                    onBack = onBack,
                    onLockToggle = { locked = !locked; interaction++ },
                    onSettings = { sheetOpen = true },
                    onPlayPause = { vm.togglePlay(); interaction++ },
                    onPrevious = vm::previous,
                    onNext = vm::next,
                    onSeek = { vm.seekTo(it); interaction++ },
                    onInteraction = { interaction++ },
                    scrubPreview = scrubPreview,
                    playFocus = playFocus,
                )
            }

            AnimatedVisibility(
                visible = controlsVisible && err == null && state.sourceLabel != null,
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(start = 16.dp, top = 64.dp),
                enter = fadeIn(), exit = fadeOut(),
            ) {
                val label = state.sourceLabel.orEmpty()
                Text(
                    text = if (state.isFallback) stringResource(R.string.player_source_fallback, label) else stringResource(R.string.player_source, label),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.background(Color.Black.copy(0.55f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            if (sheetOpen) {
                PlayerSettingsSheet(
                    state = state,
                    onDismiss = { sheetOpen = false; interaction++ },
                    onQuality = vm::selectQuality,
                    onSubtitle = vm::selectTextTrack,
                    onTranslated = vm::selectTranslated,
                    onSpeed = vm::setSpeed,
                    onDubEnabled = vm::setDubEnabled,
                    onInstallVoice = {
                        val intent = vm.dubInstallIntent()
                        if (intent != null) {
                            try { installVoiceLauncher.launch(intent) } catch (e: android.content.ActivityNotFoundException) { vm.dubInstallFailed() }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TextButtonLike(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick) { Text(text, color = Color.White) }
}

internal data class HudInfo(val icon: ImageVector, val percent: Int)

@Composable
private fun HudPill(info: HudInfo, modifier: Modifier) {
    Row(
        modifier.background(Color.Black.copy(0.6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(info.icon, null, tint = Color.White)
        Text("${info.percent}%", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** Left/right halves: tap toggles controls, double tap seeks, vertical drag adjusts brightness / volume. */
@Composable
private fun GestureLayer(
    locked: Boolean,
    onToggleControls: () -> Unit,
    onDoubleTapSeek: (forward: Boolean) -> Unit,
    onHud: (HudInfo) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    Row(Modifier.fillMaxSize()) {
        for (left in listOf(true, false)) {
            var accum by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.weight(1f).fillMaxSize()
                    .pointerInput(locked, left) {
                        detectTapGestures(
                            onTap = { onToggleControls() },
                            onDoubleTap = { if (!locked) onDoubleTapSeek(!left) },
                        )
                    }
                    .pointerInput(locked, left) {
                        if (locked) return@pointerInput
                        var start = 0f
                        detectVerticalDragGestures(
                            onDragStart = {
                                accum = 0f
                                start = if (left) {
                                    val b = activity?.window?.attributes?.screenBrightness ?: -1f
                                    if (b < 0) 0.5f else b
                                } else {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                                }
                            },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                accum -= dy / size.height * 1.2f
                                val v = (start + accum).coerceIn(0f, 1f)
                                if (left) {
                                    activity?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = v.coerceAtLeast(0.01f) } }
                                    onHud(HudInfo(Icons.Rounded.BrightnessMedium, (v * 100).toInt()))
                                } else {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt(), 0)
                                    onHud(HudInfo(Icons.Rounded.VolumeUp, (v * 100).toInt()))
                                }
                            },
                        )
                    },
            )
        }
    }
}
