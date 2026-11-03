package com.borovas.animadroid.feature.dubbing

import android.speech.tts.TextToSpeech

/** What we know about an installed/uninstalled voice for a language. */
data class VoiceState(val installed: Boolean, val offline: Boolean)

/** Maps [TextToSpeech.isLanguageAvailable] to our availability; [voices] are the engine's voices for that language (may be empty). */
fun availabilityFromCode(code: Int, voices: List<VoiceState> = emptyList()): DubAvailability = when (code) {
    TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE ->
        if (voices.isNotEmpty() && voices.none { it.installed && it.offline }) DubAvailability.NeedsVoiceData else DubAvailability.Ready
    TextToSpeech.LANG_MISSING_DATA -> DubAvailability.NeedsVoiceData
    else -> DubAvailability.Unsupported // LANG_NOT_SUPPORTED and ERROR (-1)
}
