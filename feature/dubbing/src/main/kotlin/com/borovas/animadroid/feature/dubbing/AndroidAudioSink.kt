package com.borovas.animadroid.feature.dubbing

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import kotlinx.coroutines.delay

/** One MODE_STATIC [AudioTrack] per utterance; speed via PlaybackParams (time-stretch, pitch unchanged). */
class AndroidAudioSink : AudioSink {
    @Volatile private var track: AudioTrack? = null

    override suspend fun play(wav: WavData, speed: Float) {
        val channelMask = if (wav.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(wav.sampleRate).setChannelMask(channelMask).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(wav.pcm.size)
            .build()
        track = t
        try {
            if (t.write(wav.pcm, 0, wav.pcm.size) != wav.pcm.size) return
            val effective = runCatching { t.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f); speed }.getOrDefault(1f)
            t.play()
            val deadline = wav.durationMs / effective + 1_000
            var waited = 0L
            // playbackHeadPosition counts source frames regardless of speed.
            while (t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < wav.frames && waited < deadline) {
                delay(20); waited += 20
            }
        } finally {
            if (track === t) track = null
            runCatching { t.stop() }
            t.release()
        }
    }

    override fun stop() {
        track?.let { runCatching { it.pause(); it.flush() } }
    }
}
