package com.borovas.animadroid.feature.dubbing

import java.io.File

/** Framework seam: text-to-speech synthesis. */
interface TtsSynthesizer {
    suspend fun availability(languageTag: String): DubAvailability
    /** Writes a PCM16 WAV of [text] spoken at natural rate into [out]; false on failure/timeout. */
    suspend fun synthesize(languageTag: String, text: String, out: File): Boolean
}

/** Framework seam: audio output. */
interface AudioSink {
    /** Plays [wav] at [speed] (pitch preserved), suspending until finished. Cancellation stops playback immediately. */
    suspend fun play(wav: WavData, speed: Float)
    /** Releases any live output; safe from any thread. */
    fun stop()
}
