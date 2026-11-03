package com.borovas.animadroid.feature.player

import android.view.KeyEvent

/** What a key press means for the player. Kept free of Compose/Android state so the mapping is unit-testable. */
enum class PlayerKeyAction {
    PlayPause, SeekBack, SeekForward, VolumeUp, VolumeDown, ToggleFullscreen, ToggleMute, ExitFullscreen,
    /** D-pad: reveal the controls without otherwise acting. */
    ShowControls,
    /** D-pad centre while controls are hidden: pause/resume and reveal the controls. */
    PlayPauseAndShowControls,
}

const val KEY_SEEK_STEP_MS = 10_000L

/**
 * Maps a key-down to an action, or null to let normal focus handling / the system deal with it.
 *
 * Physical keyboard: Space/K play-pause, Left/Right seek 10 s, Up/Down volume, F fullscreen, M mute, Esc leave fullscreen.
 * TV remote ([tv] = true): while the controls are visible the D-pad navigates focus between them (returns null);
 * while hidden, centre pauses and shows them, left/right seek, up/down just reveal them.
 */
fun mapPlayerKey(keyCode: Int, controlsVisible: Boolean, tv: Boolean, fullscreen: Boolean = true, ctrlOrAltOrMeta: Boolean = false): PlayerKeyAction? {
    if (ctrlOrAltOrMeta) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlayerKeyAction.PlayPause
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerKeyAction.PlayPause
        KeyEvent.KEYCODE_MEDIA_REWIND -> PlayerKeyAction.SeekBack
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlayerKeyAction.SeekForward
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
            if (tv && !controlsVisible) PlayerKeyAction.PlayPauseAndShowControls else null
        KeyEvent.KEYCODE_DPAD_LEFT -> if (tv && controlsVisible) null else PlayerKeyAction.SeekBack
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (tv && controlsVisible) null else PlayerKeyAction.SeekForward
        KeyEvent.KEYCODE_DPAD_UP -> when {
            !tv -> PlayerKeyAction.VolumeUp
            !controlsVisible -> PlayerKeyAction.ShowControls
            else -> null
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> when {
            !tv -> PlayerKeyAction.VolumeDown
            !controlsVisible -> PlayerKeyAction.ShowControls
            else -> null
        }
        KeyEvent.KEYCODE_F -> PlayerKeyAction.ToggleFullscreen
        KeyEvent.KEYCODE_M -> PlayerKeyAction.ToggleMute
        KeyEvent.KEYCODE_ESCAPE -> if (fullscreen) PlayerKeyAction.ExitFullscreen else null
        else -> null
    }
}
