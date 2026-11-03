package com.borovas.animadroid.feature.dubbing

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice-over using the system TextToSpeech. Get it by injecting [DubEngine]. Work runs on [Dispatchers.Default]; the [onDuck],
 * `positionMs` and `playing` callbacks are invoked from background threads, so they must be thread-safe (e.g. read a volatile mirror
 * of ExoPlayer state, or hop to the main looper inside the lambda). [stop] may be called from any thread.
 */
@Singleton
class SystemTtsDubEngine @Inject constructor(@ApplicationContext context: Context) : DubEngine {
    private val synth = AndroidTtsSynthesizer(context)
    private val scheduler = DubScheduler(synth, AndroidAudioSink(), File(context.cacheDir, "dub"))

    override suspend fun availability(languageTag: String) = synth.availability(languageTag)

    override fun installVoiceDataIntent(): Intent? = synth.installIntent()

    override fun start(
        scope: CoroutineScope,
        languageTag: String,
        cues: StateFlow<List<DubCue>>,
        positionMs: () -> Long,
        playing: () -> Boolean,
        onDuck: (Boolean) -> Unit,
    ) = scheduler.start(CoroutineScope(scope.coroutineContext + Dispatchers.Default), languageTag, cues, positionMs, playing, onDuck)

    override fun stop() = scheduler.stop()

    override fun onSeek() = scheduler.onSeek()

    override fun clearCache() = scheduler.clearCache()
}
