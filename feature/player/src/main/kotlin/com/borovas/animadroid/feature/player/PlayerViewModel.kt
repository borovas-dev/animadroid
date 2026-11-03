package com.borovas.animadroid.feature.player

import com.borovas.animadroid.feature.player.R as PR
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
import com.borovas.animadroid.core.model.DownloadRepository
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
import com.borovas.animadroid.feature.subtitles.MeteredDownloadRequired
import com.borovas.animadroid.feature.subtitles.R
import com.borovas.animadroid.feature.subtitles.SubtitleCue
import com.borovas.animadroid.feature.dubbing.DubAvailability
import com.borovas.animadroid.feature.dubbing.DubCue
import com.borovas.animadroid.feature.dubbing.DubEngine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.borovas.animadroid.feature.subtitles.SubtitleDecision
import com.borovas.animadroid.feature.subtitles.SubtitleLanguages
import com.borovas.animadroid.feature.subtitles.SubtitleTranslationService
import com.borovas.animadroid.feature.subtitles.TrackPicker
import com.borovas.animadroid.feature.subtitles.TranslationProgress
import com.borovas.animadroid.feature.subtitles.TranslationRequest
import com.borovas.animadroid.feature.subtitles.UnsupportedLanguageException
import kotlinx.coroutines.flow.flowOn
import com.borovas.animadroid.provider.api.ProviderChain
import com.borovas.animadroid.provider.api.ResolvedStreams
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
    data class Cloudflare(val url: String, override val message: String) : PlayerError
    data class Stream(override val message: String) : PlayerError
    data class Playback(override val message: String) : PlayerError
}

data class QualityOption(val label: String, val height: Int?)
data class TextTrackOption(
    val label: String,
    val group: Tracks.Group?,
    val trackIndex: Int,
    val selected: Boolean,
    /** Normalised two-letter language of the track, when it declares one. */
    val language: String? = null,
)

/** The extra "machine translated" subtitle entry offered next to the provider's own tracks. */
data class TranslatedOption(val label: String, val languageName: String)

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
    /** Segment that was just auto-skipped; drives the "Skipped intro - Undo" prompt. */
    val autoSkipped: SkipSegment? = null,
    val qualityOptions: List<QualityOption> = emptyList(),
    val selectedHeight: Int? = null,
    val textTracks: List<TextTrackOption> = emptyList(),
    val textOff: Boolean = true,
    val translated: TranslatedOption? = null,
    val translatedSelected: Boolean = false,
    /** Progress text while the first translated batch / language pack is prepared; null once cues flow. */
    val translationStatus: String? = null,
    /** Language name awaiting the user's OK to download a pack over a metered connection. */
    val confirmPackDownload: String? = null,
    /** Short non-blocking message (e.g. translation failed, original subtitles shown). */
    val notice: String? = null,
    val ended: Boolean = false,
    val videoAspect: Float = 16f / 9f,
    /** Display name of the provider serving the current stream; null for offline playback or before it resolves. */
    val sourceLabel: String? = null,
    /** True when [sourceLabel] is not the provider the show came from (ProviderChain failed over). */
    val isFallback: Boolean = false,
    /** The user opted in to generated voice-over (mirror of the setting). */
    val dubEnabled: Boolean = false,
    /** Voice-over is running right now (enabled, translated cues flowing, an offline voice is ready). */
    val dubActive: Boolean = false,
    /** Enabled, but the TTS engine has no offline voice for the language: the UI offers "Install voice data". */
    val dubVoiceMissing: Boolean = false,
    /** DUB was chosen but no recorded dub exists for the target language: the original audio (SUB streams) is playing. */
    val generatedDub: Boolean = false,
)

/** Result of [PlayerViewModel.resolveForPlayback]: the streams plus whether SUB streams stand in for a missing DUB. */
internal data class PlaybackResolution(val resolved: ResolvedStreams, val generatedDub: Boolean)

/** High-frequency playback position, kept separate from [PlayerUiState] to limit recomposition. */
data class PlaybackPosition(val positionMs: Long = 0, val durationMs: Long = 0, val bufferedMs: Long = 0)

@HiltViewModel(assistedFactory = PlayerViewModel.Factory::class)
class PlayerViewModel @AssistedInject constructor(
    @Assisted private val route: PlayerRoute,
    @ApplicationContext private val context: Context,
    private val chain: ProviderChain,
    private val settingsRepository: SettingsRepository,
    private val library: LibraryRepository,
    private val skipTimesRepository: SkipTimesRepository,
    private val malIdResolver: MalIdResolver,
    private val sync: com.borovas.animadroid.core.sync.SyncRepository,
    private val downloads: DownloadRepository,
    private val subtitleTranslation: SubtitleTranslationService,
    private val dubEngine: DubEngine,
    scrubPreviewFactory: ScrubPreviewManagerFactory,
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

    private val scrubPreviews = scrubPreviewFactory.create { _state.value.buffering }

    /** Thumbnail source for the seek-bar scrub preview; null while unavailable or disabled. */
    val scrubPreview: StateFlow<ScrubPreviewSource?> = scrubPreviews.source

    private val _translatedCues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    /** Machine-translated cues so far for the current episode; drawn by [SubtitleOverlay] while selected. */
    val translatedCues: StateFlow<List<SubtitleCue>> = _translatedCues.asStateFlow()
    private var translationJob: Job? = null

    // Voice-over. The engine calls positionMs/playing/onDuck from Dispatchers.Default, so it only ever sees these @Volatile mirrors;
    // the controller itself is touched on the main thread only (listener, ticker, viewModelScope).
    @Volatile private var dubSamplePos = 0L
    @Volatile private var dubSampleAt = 0L
    @Volatile private var dubPlaying = false
    @Volatile private var dubSpeed = 1f
    private var userVolume = 1f
    private var ducked = false
    private val dubRecheck = MutableStateFlow(0)
    /** Cues of the provider's own track in the target language (voice-over source (i)); empty when translated cues are used. */
    private val providedCues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    private var providedJob: Job? = null
    /** The one cue list the voice-over session reads: provided cues when present, else the machine-translated ones. */
    private val dubCues: StateFlow<List<DubCue>> by lazy {
        combine(providedCues, _translatedCues) { p, t -> (p.ifEmpty { t }).map { DubCue(it.startMs, it.endMs, it.text) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }
    /** Language the subtitles / voice-over target for the current episode (see [DubPolicy.targetLanguage]). */
    private var targetLang: String = "en"
    private var decision: SubtitleDecision = SubtitleDecision.None
    private var providedLangPending: String? = null

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
    private var undoPositionMs = 0L
    private val prefetcher = NextEpisodePrefetcher<PlaybackResolution>({ android.os.SystemClock.elapsedRealtime() })
    private val network = com.borovas.animadroid.feature.subtitles.NetworkChecker {
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
    }

    private val episode get() = route.episodes[currentIndex]
    private val titleText get() = route.anime.title + " · Episode " + episode.number

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            controller?.let { sampleDub(it) }
            if (!isPlaying) saveProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
            if (playbackState == Player.STATE_ENDED) onEnded()
            if (playbackState == Player.STATE_READY) refreshTracks()
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            _state.update { it.copy(speed = playbackParameters.speed) }
            controller?.let { sampleDub(it) }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
        ) {
            controller?.let { sampleDub(it) }
            if (_state.value.dubActive) dubEngine.onSeek()
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
                it.copy(loading = false, buffering = false, error = PlayerError.Playback(error.message ?: context.getString(PR.string.player_error_playback)))
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
        viewModelScope.launch {
            combine(
                settingsRepository.settings.map { it.dubEnabled }.distinctUntilChanged(),
                dubCues.map { it.isNotEmpty() }.distinctUntilChanged(),
                _state.map { it.generatedDub }.distinctUntilChanged(),
                dubRecheck,
            ) { enabled, hasCues, generated, _ -> Triple(enabled, hasCues, generated) }
                .collectLatest { (enabled, hasCues, generated) -> runDub(enabled, hasCues, generated) }
        }
    }

    private fun sampleDub(c: MediaController) {
        dubSamplePos = c.currentPosition
        dubSampleAt = android.os.SystemClock.elapsedRealtime()
        dubPlaying = c.isPlaying
        dubSpeed = c.playbackParameters.speed
    }

    private fun applyVolume() { controller?.volume = DubPolicy.volume(userVolume, ducked) }

    /** One voice-over session; cancelled (and cleaned up) whenever the setting, the translated cues or [recheckDub] change. */
    private suspend fun runDub(enabled: Boolean, hasCues: Boolean, generatedDub: Boolean) {
        _state.update { it.copy(dubEnabled = enabled, dubActive = false, dubVoiceMissing = false) }
        val lang = targetLang
        if (!DubPolicy.shouldRun(enabled, settings.mode, recordedDubAvailable = !generatedDub, hasCues = hasCues, targetLang = lang)) return
        try {
            when (dubEngine.availability(lang)) {
                DubAvailability.Ready -> coroutineScope {
                    controller?.let { sampleDub(it) }
                    dubEngine.start(
                        scope = this, languageTag = lang, cues = dubCues,
                        positionMs = { DubPolicy.extrapolate(dubSamplePos, dubSampleAt, android.os.SystemClock.elapsedRealtime(), dubPlaying, dubSpeed) },
                        playing = { dubPlaying },
                        onDuck = { d -> viewModelScope.launch(Dispatchers.Main) { ducked = d; applyVolume() } },
                    )
                    _state.update { it.copy(dubActive = true) }
                    awaitCancellation()
                }
                DubAvailability.NeedsVoiceData -> _state.update { it.copy(dubVoiceMissing = true) }
                DubAvailability.Unsupported -> _state.update { it.copy(notice = context.getString(PR.string.player_dub_unsupported)) }
                DubAvailability.NoEngine -> _state.update { it.copy(notice = context.getString(PR.string.player_dub_no_engine)) }
            }
        } finally {
            dubEngine.stop()
            ducked = false
            applyVolume()
            _state.update { it.copy(dubActive = false) }
        }
    }

    fun setDubEnabled(on: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(dubEnabled = on) }
            settings = settings.copy(dubEnabled = on)
            // Switched on mid-episode after a DUB -> SUB fallback: load the cues the voice-over needs.
            val set = streams
            if (on && set != null && _state.value.generatedDub && dubCues.value.isEmpty()) planSubtitles(set)
        }
    }

    /** Intent for the TTS engine's voice-data installer, or null when nothing can handle it (a notice is shown). */
    fun dubInstallIntent(): android.content.Intent? =
        dubEngine.installVoiceDataIntent() ?: run {
            _state.update { it.copy(notice = context.getString(PR.string.player_dub_install_failed)) }
            null
        }

    fun dubInstallFailed() = _state.update { it.copy(notice = context.getString(PR.string.player_dub_install_failed)) }

    /** Re-checks voice availability, e.g. after the user returned from the voice-data installer. */
    fun recheckDub() { if (_state.value.dubVoiceMissing) dubRecheck.value++ }

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
            _state.update { it.copy(loading = false, error = PlayerError.Playback(e.message ?: context.getString(PR.string.player_error_cannot_start))) }
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
        scrubPreviews.clear()
        loadJob?.cancel()
        dubEngine.stop()
        dubEngine.clearCache()
        resetTranslation()
        _position.value = PlaybackPosition()
        _state.update {
            it.copy(
                title = titleText, loading = true, error = null, ended = false, skipButton = null, autoSkipped = null, skipTimes = null,
                episodeIndex = index, hasPrevious = index > 0, hasNext = index < route.episodes.lastIndex,
                qualityOptions = emptyList(), selectedHeight = null, textTracks = emptyList(),
                sourceLabel = null, isFallback = false, generatedDub = false,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                settings = settingsRepository.settings.first()
                // A finished download wins: play it from the offline cache and never touch the provider.
                val offline = downloads.get(route.providerId, route.anime.id, episode.number)?.takeIf { it.isPlayable }
                if (offline == null && route.offline) error("Episode ${episode.number} is not downloaded")
                targetLang = DubPolicy.targetLanguage(settings.subtitleLanguage, chain.effectiveLanguage(settings))
                val set = offline?.streamSet ?: run {
                    val playback = prefetchKey(currentIndex)?.let { prefetcher.take(it) } ?: resolveForPlayback(route.anime, episode)
                    val resolved = playback.resolved
                    _state.update {
                        it.copy(
                            sourceLabel = chain.provider(resolved.providerId)?.displayName ?: resolved.providerId,
                            isFallback = resolved.isFallback, generatedDub = playback.generatedDub,
                        )
                    }
                    resolved.set
                }
                streams = set
                scrubPreviews.configure(set, offline != null, settings)
                android.util.Log.i("AnimadroidPlayer", "stream mode=${settings.mode} ep=${episode.number} master=${set.masterUrl} offline=${offline != null}")
                planSubtitles(set)
                val start = startOverrideMs ?: if (resumeFromProgress) resumePosition() else 0L
                play(set, settings.quality, null, start)
                _state.update { s ->
                    s.copy(
                        qualityOptions = buildList {
                            add(QualityOption(context.getString(PR.string.player_quality_auto), null))
                            set.variants.mapNotNull { it.quality }.distinct().sortedDescending()
                                .forEach { add(QualityOption("${it}p", it)) }
                        },
                    )
                }
                loadSkipTimes(set)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                _state.update { it.copy(loading = false, error = PlayerError.Cloudflare(e.url, context.getString(PR.string.player_error_cloudflare))) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = PlayerError.Stream(e.message ?: context.getString(PR.string.player_error_stream))) }
            }
        }
    }

    /**
     * Resolves streams for [mode][AppSettings.mode]. For DUB with no recorded dub in the target language (provider reports 0
     * dubbed episodes, the DUB request fails with a [ProviderException], or - only with voice-over on - the dub that resolved is
     * in another language than the target) it resolves the SUB streams (original audio) instead and flags [PlaybackResolution.generatedDub].
     * That fallback is independent of the voice-over switch: with it off the player just shows subtitles like SUB mode.
     * Cloudflare / network errors are not swallowed. Used by playback and the next-episode prefetcher alike.
     */
    private suspend fun resolveForPlayback(anime: com.borovas.animadroid.core.model.Anime, ep: com.borovas.animadroid.core.model.Episode): PlaybackResolution {
        val mode = settings.mode
        if (mode != com.borovas.animadroid.core.model.TranslationMode.DUB) return PlaybackResolution(chain.streams(anime, ep, mode), false)
        val sub = com.borovas.animadroid.core.model.TranslationMode.SUB
        if (DubPolicy.dubKnownMissing(mode, anime.dubEpisodes)) return PlaybackResolution(chain.streams(anime, ep, sub), true)
        val dub = try {
            chain.streams(anime, ep, mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!DubPolicy.isNoDubFailure(e)) throw e
            android.util.Log.i("AnimadroidPlayer", "no recorded dub; falling back to original audio", e)
            return PlaybackResolution(chain.streams(anime, ep, sub), true)
        }
        val langs = chain.provider(dub.providerId)?.contentLanguages
        if (DubPolicy.dubLanguageMismatch(settings.dubEnabled, langs, targetLang)) return PlaybackResolution(chain.streams(anime, ep, sub), true)
        return PlaybackResolution(dub, false)
    }

    private suspend fun resumePosition(): Long {
        val p = library.getProgress(route.providerId, route.anime.id, episode.number)
        return if (p != null && !p.isFinished) p.positionMs else 0L
    }

    private fun play(set: StreamSet, pref: QualityPreference, height: Int?, startMs: Long) {
        val c = controller ?: return
        val item = MediaItemFactory.create(set, pref, titleText, height, mediaId = route.anime.id + "/" + episode.id)
            ?: error(context.getString(PR.string.player_error_no_stream))
        c.setMediaItem(item, startMs)
        // The translated overlay replaces the source track, so keep ExoPlayer from drawing it as well.
        if (_state.value.translatedSelected) setTextDisabled(true)
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
            skipController = SkipController(times, settings.skipOffsetSec, settings.skipBehavior, settings.autoSkipIntro, settings.autoSkipOutro)
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
                    sampleDub(c)
                    if (c.isPlaying || c.playbackState == Player.STATE_READY) handleSkip(c, pos)
                    if (c.isPlaying) maybePrefetch(pos, dur)
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
            is SkipAction.AutoSeek -> {
                undoPositionMs = posMs
                c.seekTo(a.targetMs)
                _state.update { it.copy(autoSkipped = a.segment, skipButton = null) }
            }
            is SkipAction.ShowButton -> {
                pendingButtonTargetMs = a.targetMs
                if (_state.value.skipButton != a.segment) _state.update { it.copy(skipButton = a.segment) }
            }
        }
    }

    /** Undo for the last auto-skip: back to where it happened. The segment stays marked so it is not skipped again. */
    fun undoAutoSkip() {
        if (_state.value.autoSkipped == null) return
        controller?.seekTo(undoPositionMs)
        _state.update { it.copy(autoSkipped = null) }
    }

    fun dismissAutoSkip() = _state.update { it.copy(autoSkipped = null) }

    private fun prefetchKey(index: Int): String? =
        route.episodes.getOrNull(index)?.let { "${route.providerId}/${route.anime.id}/${it.id}" }

    /** Opt-in: resolve (never download) the next episode's streams near the end so "next" starts instantly. Failures are silent. */
    private fun maybePrefetch(posMs: Long, durMs: Long) {
        if (route.offline) return
        val index = currentIndex + 1
        val key = prefetchKey(index) ?: return
        if (!prefetcher.shouldStart(key, settings.prefetchNextEpisode, network.isMetered(), settings.prefetchOnMetered, posMs, durMs)) return
        prefetcher.markAttempted(key)
        val next = route.episodes[index]
        viewModelScope.launch {
            try {
                if (downloads.get(route.providerId, route.anime.id, next.number)?.isPlayable == true) return@launch
                prefetcher.put(key, resolveForPlayback(route.anime, next))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.d("AnimadroidPlayer", "next-episode prefetch failed; will resolve normally", e)
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
        val raw = buildList {
            c.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }.forEach { g ->
                for (i in 0 until g.length) add(Triple(g, i, g.getTrackFormat(i)))
            }
        }
        // ExoPlayer adds an unlabeled CEA-608 placeholder ("Track 1") to every HLS stream that does not declare
        // CLOSED-CAPTIONS. It never carries text, so hide it when real (sidecar/WebVTT) tracks exist.
        fun isCea(f: androidx.media3.common.Format) =
            f.sampleMimeType == androidx.media3.common.MimeTypes.APPLICATION_CEA608 ||
                f.sampleMimeType == androidx.media3.common.MimeTypes.APPLICATION_CEA708
        val hasRealTrack = raw.any { !isCea(it.third) }
        val options = raw
            .filter { (_, _, f) -> !(hasRealTrack && isCea(f) && f.label == null && f.language == null) }
            .map { (g, i, f) ->
                val lang = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }
                    ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.getDefault()).takeIf { n -> n.isNotBlank() } }
                val label = f.label ?: lang ?: context.getString(if (isCea(f)) PR.string.player_closed_captions else PR.string.player_subtitles)
                TextTrackOption(label, g, i, g.isTrackSelected(i), TrackPicker.languageOf(
                    com.borovas.animadroid.core.model.SubtitleTrack("", f.language, f.label, false),
                ))
            }
            // Same label twice (e.g. the same sidecar exposed by two sources): keep one, preferring the selected one.
            .groupBy { it.label }.values.map { dup -> dup.firstOrNull { it.selected } ?: dup.first() }
        val off = c.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || options.none { it.selected }
        _state.update { it.copy(textTracks = options, textOff = off) }
        applyProvidedTrackPreference(options)
    }

    private fun setTextDisabled(disabled: Boolean) {
        val c = controller ?: return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled).build()
    }

    /** Selects the provider's own track in the preferred language, once per episode, as soon as it is exposed. */
    private fun applyProvidedTrackPreference(options: List<TextTrackOption>) {
        val lang = providedLangPending ?: return
        val match = options.firstOrNull { it.language == lang } ?: return
        providedLangPending = null
        if (!match.selected || _state.value.textOff) selectTextTrack(match)
    }

    private fun resetTranslation() {
        translationJob?.cancel()
        translationJob = null
        providedJob?.cancel()
        providedJob = null
        providedCues.value = emptyList()
        decision = SubtitleDecision.None
        providedLangPending = null
        _translatedCues.value = emptyList()
        _state.update { it.copy(translated = null, translatedSelected = false, translationStatus = null, confirmPackDownload = null) }
    }

    /** Applies the "subtitle language" setting: pick the provider's track, or offer/start a translation. */
    private fun planSubtitles(set: StreamSet) {
        // Voice-over wants cues even when subtitles are off / translation is disabled in settings.
        val voiceOver = settings.dubEnabled && _state.value.generatedDub
        val preferred = SubtitleLanguages.normalize(settings.subtitleLanguage) ?: (if (voiceOver) targetLang else return)
        val d = TrackPicker.decide(set.subtitles, preferred, settings.translateSubtitles || voiceOver, set.hardsubLanguage, subtitleTranslation::isSupported)
        decision = d
        if (voiceOver && d == SubtitleDecision.None) _state.update { it.copy(notice = context.getString(PR.string.player_dub_no_subtitles)) }
        when (d) {
            is SubtitleDecision.UseProvided -> {
                providedLangPending = preferred
                if (voiceOver) loadProvidedCues(d.track, set.headers)
            }
            is SubtitleDecision.Translate -> {
                val name = SubtitleLanguages.displayName(preferred)
                _state.update {
                    it.copy(translated = TranslatedOption(context.getString(R.string.subtitles_translated_label, name), name), translatedSelected = true)
                }
                startTranslation(allowMetered = false)
            }
            SubtitleDecision.None -> Unit
        }
    }

    /** Voice-over source (i): download and parse the provider's own track in the target language. */
    private fun loadProvidedCues(track: com.borovas.animadroid.core.model.SubtitleTrack, headers: Map<String, String>) {
        val forEpisode = currentIndex
        providedJob?.cancel()
        providedJob = viewModelScope.launch {
            try {
                val cues = subtitleTranslation.fetchCues(track.url, headers)
                if (forEpisode == currentIndex) providedCues.value = cues
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AnimadroidPlayer", "could not load subtitles for voice-over", e)
                _state.update { it.copy(notice = context.getString(PR.string.player_dub_no_subtitles)) }
            }
        }
    }

    private fun startTranslation(allowMetered: Boolean) {
        val d = decision as? SubtitleDecision.Translate ?: return
        val target = SubtitleLanguages.normalize(settings.subtitleLanguage) ?: targetLang
        val set = streams ?: return
        val forEpisode = currentIndex
        translationJob?.cancel()
        _state.update { it.copy(translationStatus = context.getString(R.string.subtitles_preparing), confirmPackDownload = null) }
        val request = TranslationRequest(
            episodeKey = "${route.providerId}/${route.anime.id}/${episode.id}",
            trackUrl = d.track.url, headers = set.headers, sourceLang = d.sourceLang, targetLang = target, allowMetered = allowMetered,
        )
        translationJob = viewModelScope.launch {
            try {
                subtitleTranslation.translate(request) { _position.value.positionMs }.flowOn(Dispatchers.Default).collect { p ->
                    if (forEpisode != currentIndex) return@collect
                    when (p) {
                        TranslationProgress.Preparing -> Unit
                        TranslationProgress.DownloadingModel ->
                            _state.update { it.copy(translationStatus = context.getString(R.string.subtitles_downloading_pack)) }
                        is TranslationProgress.Translating -> {
                            _translatedCues.value = p.cues
                            _state.update {
                                it.copy(translationStatus = if (p.cues.isEmpty()) context.getString(R.string.subtitles_translating, 0) else null)
                            }
                        }
                        is TranslationProgress.Done -> {
                            _translatedCues.value = p.cues
                            _state.update { it.copy(translationStatus = null) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MeteredDownloadRequired) {
                _state.update { it.copy(translationStatus = null, confirmPackDownload = SubtitleLanguages.displayName(target)) }
            } catch (e: Exception) {
                android.util.Log.w("AnimadroidPlayer", "subtitle translation failed", e)
                val msg = when (e) {
                    is UnsupportedLanguageException -> context.getString(R.string.subtitles_failed_unsupported, SubtitleLanguages.displayName(target))
                    is com.borovas.animadroid.feature.subtitles.TranslationFailedException ->
                        if (e.message?.contains("pack", ignoreCase = true) == true) context.getString(R.string.subtitles_failed_download)
                        else context.getString(R.string.subtitles_failed_generic)
                    else -> context.getString(R.string.subtitles_failed_generic)
                }
                val reason = e.message ?: e::class.java.simpleName
                fallBackToOriginal(if (e is UnsupportedLanguageException || reason in msg) msg else "$msg ($reason)")
            }
        }
    }

    /** Drops the translated entry's selection and shows the source track again; playback is never interrupted. */
    private fun fallBackToOriginal(notice: String?) {
        translationJob?.cancel()
        _translatedCues.value = emptyList()
        _state.update { it.copy(translatedSelected = false, translationStatus = null, confirmPackDownload = null, notice = notice ?: it.notice) }
        val sourceLang = (decision as? SubtitleDecision.Translate)?.sourceLang
        val original = _state.value.textTracks.let { tracks -> tracks.firstOrNull { it.language == sourceLang } ?: tracks.firstOrNull() }
        if (original != null) selectTextTrack(original) else setTextDisabled(false)
    }

    fun selectTranslated() {
        if (decision !is SubtitleDecision.Translate) return
        setTextDisabled(true)
        _state.update { it.copy(translatedSelected = true, textOff = true) }
        if (translationJob?.isActive != true && _translatedCues.value.isEmpty()) startTranslation(allowMetered = false)
    }

    fun confirmPackDownload() = startTranslation(allowMetered = true)

    fun declinePackDownload() = fallBackToOriginal(null)

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun selectTextTrack(option: TextTrackOption?) {
        val c = controller ?: return
        _state.update { it.copy(translatedSelected = false, translationStatus = null) }
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
                _state.update { it.copy(error = PlayerError.Stream(e.message ?: context.getString(PR.string.player_error_switch_quality))) }
            }
        }
    }

    fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }
    fun togglePlay() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
        if (c.isPlaying) c.pause() else c.play()
    }
    /** Toggles player-level mute (independent of the system stream volume). Returns true when now muted. */
    fun toggleMute(): Boolean {
        if (controller == null) return false
        val muted = userVolume > 0f
        userVolume = if (muted) 0f else 1f
        applyVolume()
        return muted
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
        scrobbleIfWatched(anime, ep, pos, dur)
    }

    private val scrobbled = HashSet<String>()

    /** Past ~85% an episode counts as watched: hand it to AniList/MAL sync once per episode (the queue is idempotent anyway). */
    private fun scrobbleIfWatched(anime: com.borovas.animadroid.core.model.Anime, ep: String, pos: Long, dur: Long) {
        if (dur <= 0 || pos.toFloat() / dur < com.borovas.animadroid.core.sync.WATCHED_FRACTION || !scrobbled.add(ep)) return
        ioScope.launch {
            runCatching {
                val withMal = anime.malId?.let { anime } ?: malIdResolver.resolve(anime.title)?.let { anime.copy(malId = it) } ?: return@launch
                sync.onEpisodeWatched(withMal, ep, null)
            }
        }
    }

    override fun onCleared() {
        scrubPreviews.close()
        prefetcher.clear()
        tickJob?.cancel()
        dubEngine.stop()
        ducked = false
        applyVolume()
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
