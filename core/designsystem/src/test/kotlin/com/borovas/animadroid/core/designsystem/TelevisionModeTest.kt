package com.borovas.animadroid.core.designsystem

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelevisionModeTest {
    @Test fun detectsTelevisionUiModeIgnoringNightBits() {
        assertTrue(isTelevisionUiMode(Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES))
        assertFalse(isTelevisionUiMode(Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES))
        assertFalse(isTelevisionUiMode(Configuration.UI_MODE_TYPE_DESK))
    }
}
