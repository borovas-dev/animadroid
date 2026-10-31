package com.borovas.animadroid.feature.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.LibraryRepository
import com.borovas.animadroid.core.model.QualityPreference
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.core.model.SkipBehavior
import com.borovas.animadroid.core.model.SkipTimes
import com.borovas.animadroid.core.model.SkipTimesRepository
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.feature.aniskip.MalIdResolver
import com.borovas.animadroid.feature.aniskip.SkipAction
import com.borovas.animadroid.feature.aniskip.SkipController
import com.borovas.animadroid.feature.aniskip.SkipSegment
import com.borovas.animadroid.feature.aniskip.mergeSkipTimes
import com.borovas.animadroid.provider.api.AnimeProvider
import com.borovas.animadroid.provider.api.CloudflareChallengeException
import com.borovas.animadroid.provider.api.CloudflareSolved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface PlayerError {
    val message: String
    data class Cloudflare(val url: String) : PlayerError {
        override val message = "Blocked by Cloudflare. Solve the challenge and retry."
    }
    data class Stream(override val message: String) : PlayerError
    data class Playback(override val message: String) : PlayerError
}

data class QualityOption(val label: String, val height: Int?)
data class TextTrackOption(val label: String, val group: Tracks.Group?, val trackIndex: Int, val selected: Boolean)

data class PlayerUiState(
    val title: String = "",
    val loading: Boolean = true,
    val buffering: Boolean = false,
    val isPlaying: Boolean = false,
    val error: PlayerError? = null,
    val speed: Float = 1f,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val episodeIndex: Int = 0,
    val skipTimes: SkipTimes? = null,
    val skipButton: SkipSegment? = null,
    val qualityOptions: List<QualityOption> = emptyList(),
    val selectedHeight: Int? = null,
    val textTracks: List<TextTrackOption> = emptyList(),
    val textOff: Boolean = true,
    val ended: Boolean = false,
    val videoAspect: Float = 16f / 9f,
)

/** High-frequency playback position, kept separate from [PlayerUiState] to limit recomposition. */
data class PlaybackPosition(val positionMs: Long = 0, val durationMs: Long = 0, val bufferedMs: Long = 0)

@HiltViewModel(assistedFactory = PlayerViewModel.Factory::class)
class PlayerViewModel @AssistedInject constructor(
    @Assisted private val route: PlayerRoute,
    @ApplicationContext private val context: Context,
    private val providers: Map<String, @JvmSuppressWildcards AnimeProvider>,
    private val settingsRepository: SettingsRepository,
    private val library: LibraryRepository,
    private val skipTimesRepository: SkipTimesRepository,
    private val malIdResolver: MalIdResolver,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(route: PlayerRoute): PlayerViewModel
    }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _position = MutableStateFlow(PlaybackPosition())
    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    /** Exposed so the UI can attach a video surface. Null until the controller connects. */
    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var controller: MediaController? = null
    private var settings = AppSettings()
    private var streams: StreamSet? = null
    private var skipController: SkipController? = null
    private var currentIndex = route.startIndex.coerceIn(0, (route.episodes.size - 1).coerceAtLeast(0))
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var lastSavedMs = -1L
    private var pendingButtonTargetMs = 0L

    private val episode get() = route.episodes[currentIndex]
    private val titleText get() = route.anime.title + " · Episode " + episode.number

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) saveProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
            if (playbackState == Player.STATE_ENDED) onEnded()
            if (playbackState == Player.STATE_READY) refreshTracks()
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            _state.update { it.copy(speed = playbackParameters.speed) }
        }

        override fun onTracksChanged(tracks: Tracks) = refreshTracks()

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _state.update { it.copy(videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            saveProgress()
            _state.update {
                it.copy(loading = false, buffering = false, error = PlayerError.Playback(error.message ?: "Playback error"))
            }
        }
    }

    init {
        viewModelScope.launch {
            CloudflareSolved.events.collect { if (_state.value.error is PlayerError.Cloudflare) retry() }
        }
        viewModelScope.launch {
            connect()
            loadEpisode(currentIndex, resumeFromProgress = true)
        }
    }

    private suspend fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val done = CompletableDeferred<MediaController>()
        future.addListener({
            try { done.complete(future.get()) } catch (e: Exception) { done.completeExceptionally(e) }
        }, ContextCompat.getMainExecutor(context))
        val c = try {
            done.await()
        } catch (e: CancellationException) {
            future.cancel(false); throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = PlayerError.Playback(e.message ?: "Cannot start player")) }
            return
        }
        c.addListener(listener)
        controller = c
        _player.value = c
        startTicker()
    }

    fun loadEpisode(index: Int, resumeFromProgress: Boolean = true, startOverrideMs: Long? = null) {
        if (index !in route.episodes.indices) return
        saveProgress()
        // Drop the previous item so late ticks cannot save its position under the new episode.
        controller?.run { pause(); clearMediaItems() }
        currentIndex = index
        lastSavedMs = -1L
        skipController = null
        loadJob?.cancel()
        _position.value = PlaybackPosition()
        _state.update {
            it.copy(
                title = titleText, loading = true, error = null, ended = false, skipButton = null, skipTimes = null,
                episodeIndex = index, hasPrevious = index > 0, hasNext = index < route.episodes.lastIndex,
                qualityOptions = emptyList(), selectedHeight = null,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                settings = settingsRepository.settings.first()
                val provider = providers[route.providerId] ?: error("Unknown provider ${route.providerId}")
                val set = provider.streams(route.anime, episode, settings.mode)
                streams = set
                val start = startOverrideMs ?: if (resumeFromProgress) resumePosition() else 0L
                play(set, settings.quality, null, start)
                _state.update { s ->
                    s.copy(
                        qualityOptions = buildList {
                            add(QualityOption("Auto", null))
                            set.variants.mapNotNull { it.quality }.distinct().sortedDescending()
                                .forEach { add(QualityOption("${it}p", it)) }
                        },
                    )
                }
                loadSkipTimes(set)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                _state.update { it.copy(loading = false, error = PlayerError.Cloudflare(e.url)) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = PlayerError.Stream(e.message ?: "Failed to load stream")) }
            }
        }
    }

    private suspend fun resumePosition(): Long {
        val p = library.getProgress(route.providerId, route.anime.id, episode.number)
        return if (p != null && !p.isFinished) p.positionMs else 0L
    }

    private fun play(set: StreamSet, pref: QualityPreference, height: Int?, startMs: Long) {
        val c = controller ?: return
        val item = MediaItemFactory.create(set, pref, titleText, height, mediaId = route.anime.id + "/" + episode.id)
            ?: error("No playable stream found")
        c.setMediaItem(item, startMs)
        c.prepare()
        c.play()
        _state.update { it.copy(loading = false, selectedHeight = height) }
    }

    private fun loadSkipTimes(set: StreamSet) {
        if (!settings.aniSkipEnabled) return
        val epNum = episode.number.toDoubleOrNull()?.toInt()
        val forEpisode = currentIndex
        viewModelScope.launch {
            val malId = if (epNum == null) null else (set.malId ?: route.anime.malId ?: malIdResolver.resolve(route.anime.title))
            val aniSkip = if (malId != null && epNum != null) skipTimesRepository.skipTimes(malId, epNum) else null
            // Fall back to the embed's own intro/outro markers when ani-skip has no data.
            val times = mergeSkipTimes(aniSkip, set.intro, set.outro) ?: return@launch
            if (forEpisode != currentIndex) return@launch
            skipController = SkipController(times, settings.skipOffsetSec, settings.skipBehavior)
            _state.update { it.copy(skipTimes = times) }
        }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var sinceSave = 0
            while (isActive) {
                val c = controller
                if (c != null && c.mediaItemCount > 0) {
                    val dur = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                    val pos = c.currentPosition
                    _position.value = PlaybackPosition(pos, dur, c.bufferedPosition)
                    if (c.isPlaying || c.playbackState == Player.STATE_READY) handleSkip(c, pos)
                    if (c.isPlaying && ++sinceSave >= SAVE_EVERY_TICKS) {
                        sinceSave = 0
                        saveProgress()
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun handleSkip(c: MediaController, posMs: Long) {
        val sc = skipController ?: return
        when (val a = sc.onPosition(posMs / 1000.0)) {
            SkipAction.None -> if (_state.value.skipButton != null) _state.update { it.copy(skipButton = null) }
            is SkipAction.AutoSeek -> c.seekTo(a.targetMs)
            is SkipAction.ShowButton -> {
                pendingButtonTargetMs = a.targetMs
                if (_state.value.skipButton != a.segment) _state.update { it.copy(skipButton = a.segment) }
            }
        }
    }

    fun onSkipButton() {
        val seg = _state.value.skipButton ?: return
        controller?.seekTo(pendingButtonTargetMs)
        skipController?.markSkipped(seg)
        _state.update { it.copy(skipButton = null) }
    }

    private fun refreshTracks() {
        val c = controller ?: return
        val options = buildList {
            c.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    add(TextTrackOption(f.label ?: f.language ?: "Track ${size + 1}", g, i, g.isTrackSelected(i)))
                }
            }
        }
        val off = c.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || options.none { it.selected }
        _state.update { it.copy(textTracks = options, textOff = off) }
    }

    fun selectTextTrack(option: TextTrackOption?) {
        val c = controller ?: return
        val b = c.trackSelectionParameters.buildUpon()
        if (option?.group == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            b.setOverrideForType(androidx.media3.common.TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
        }
        c.trackSelectionParameters = b.build()
    }

    fun selectQuality(height: Int?) {
        val set = streams ?: return
        val pos = controller?.currentPosition ?: 0L
        viewModelScope.launch {
            try {
                // null height => Auto (master playlist when available).
                play(set, QualityPreference.BEST, height, pos)
            } catch (e: Exception) {
                _state.update { it.copy(error = PlayerError.Stream(e.message ?: "Failed to switch quality")) }
            }
        }
    }

    fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }
    fun togglePlay() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
        if (c.isPlaying) c.pause() else c.play()
    }
    fun seekTo(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0)) }
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val dur = c.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, dur))
    }
    fun next() { if (currentIndex < route.episodes.lastIndex) loadEpisode(currentIndex + 1) }
    fun previous() { if (currentIndex > 0) loadEpisode(currentIndex - 1) }

    fun retry() {
        val pos = _position.value.positionMs
        loadEpisode(currentIndex, resumeFromProgress = pos == 0L, startOverrideMs = pos.takeIf { it > 0 })
    }

    private fun onEnded() {
        val c = controller
        val dur = c?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: _position.value.durationMs
        if (dur > 0) persist(dur, dur)
        if (settings.autoPlayNext && currentIndex < route.episodes.lastIndex) {
            loadEpisode(currentIndex + 1, resumeFromProgress = false)
        } else {
            _state.update { it.copy(ended = true) }
        }
    }

    private fun saveProgress() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val dur = c.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
        val pos = c.currentPosition
        if (pos == lastSavedMs) return
        lastSavedMs = pos
        persist(pos, dur)
    }

    private fun persist(pos: Long, dur: Long) {
        val anime = route.anime
        val ep = episode.number
        ioScope.launch { runCatching { library.saveProgress(anime, ep, pos, dur) } }
    }

    override fun onCleared() {
        tickJob?.cancel()
        saveProgress()
        controller?.let {
            it.removeListener(listener)
            it.stop()
            it.clearMediaItems()
            it.release()
        }
        controller = null
        _player.value = null
        super.onCleared()
    }

    private companion object {
        const val TICK_MS = 250L
        const val SAVE_EVERY_TICKS = 20 // 5 s
    }
}
