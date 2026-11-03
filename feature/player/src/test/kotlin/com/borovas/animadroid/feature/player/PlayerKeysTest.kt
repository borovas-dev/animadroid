package com.borovas.animadroid.feature.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerKeysTest {
    private fun kb(code: Int, fullscreen: Boolean = true) = mapPlayerKey(code, controlsVisible = true, tv = false, fullscreen = fullscreen)

    @Test fun keyboardShortcuts() {
        assertEquals(PlayerKeyAction.PlayPause, kb(KeyEvent.KEYCODE_SPACE))
        assertEquals(PlayerKeyAction.SeekBack, kb(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlayerKeyAction.SeekForward, kb(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PlayerKeyAction.VolumeUp, kb(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PlayerKeyAction.VolumeDown, kb(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(PlayerKeyAction.ToggleFullscreen, kb(KeyEvent.KEYCODE_F))
        assertEquals(PlayerKeyAction.ToggleMute, kb(KeyEvent.KEYCODE_M))
        assertEquals(PlayerKeyAction.ExitFullscreen, kb(KeyEvent.KEYCODE_ESCAPE))
        assertNull(kb(KeyEvent.KEYCODE_ESCAPE, fullscreen = false))
        assertNull(kb(KeyEvent.KEYCODE_Z))
    }

    @Test fun modifiersAreIgnored() {
        assertNull(mapPlayerKey(KeyEvent.KEYCODE_M, true, false, ctrlOrAltOrMeta = true))
    }

    @Test fun tvHiddenControls() {
        assertEquals(PlayerKeyAction.PlayPauseAndShowControls, mapPlayerKey(KeyEvent.KEYCODE_DPAD_CENTER, false, true))
        assertEquals(PlayerKeyAction.SeekBack, mapPlayerKey(KeyEvent.KEYCODE_DPAD_LEFT, false, true))
        assertEquals(PlayerKeyAction.ShowControls, mapPlayerKey(KeyEvent.KEYCODE_DPAD_UP, false, true))
        assertEquals(PlayerKeyAction.ShowControls, mapPlayerKey(KeyEvent.KEYCODE_DPAD_DOWN, false, true))
    }

    @Test fun tvVisibleControlsLeaveDpadToFocus() {
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            assertNull(mapPlayerKey(k, true, true))
        }
    }

    @Test fun keyboardEnterDoesNothingOnDesktop() {
        assertNull(kb(KeyEvent.KEYCODE_ENTER))
    }
}
