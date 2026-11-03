package com.borovas.animadroid.feature.dubbing

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

sealed interface DubAvailability {
    /** An offline voice for the language exists. */
    data object Ready : DubAvailability
    /** Engine present but the language voice is missing/not installed -> UI should offer [DubEngine.installVoiceDataIntent]. */
    data object NeedsVoiceData : DubAvailability
    /** The engine cannot do this language at all. */
    data object Unsupported : DubAvailability
    /** No TTS engine on the device. */
    data object NoEngine : DubAvailability
}

data class DubCue(val startMs: Long, val endMs: Long, val text: String)

interface DubEngine {
    /** Checks (async, initialises the TTS engine on first use) whether [languageTag] (BCP-47, e.g. "pl", "zh-CN") can be spoken offline. */
    suspend fun availability(languageTag: String): DubAvailability

    /** Intent that makes the TTS engine download voice data (TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA); null if none can handle it. */
    fun installVoiceDataIntent(): Intent?

    /**
     * Starts voice-over. [cues] are the translated cues (may grow over time as translation progresses; collect it). [positionMs] returns the
     * current playback position; [playing] whether playback is running (not paused/buffering). [onDuck] is called with true when a cue starts
     * speaking and false when it ends (the caller lowers/restores the main player's volume). Idempotent: calling start again restarts.
     */
    fun start(
        scope: CoroutineScope,
        languageTag: String,
        cues: StateFlow<List<DubCue>>,
        positionMs: () -> Long,
        playing: () -> Boolean,
        onDuck: (Boolean) -> Unit,
    )

    /** Stops speaking, cancels pre-generation, releases the AudioTrack; synthesis cache stays on disk. */
    fun stop()

    /** Call on seek: drops the current utterance and re-schedules from the new position. */
    fun onSeek()

    /** Deletes the cached per-cue audio (e.g. when the episode changes). */
    fun clearCache()
}
