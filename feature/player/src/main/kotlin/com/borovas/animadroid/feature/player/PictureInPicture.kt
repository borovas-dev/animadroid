package dev.borovas.animadroid.feature.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.util.Rational
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.graphics.toComposeRect

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Picture-in-picture helper (API 31+). The player screen keeps [PictureInPictureController] up to
 * date; the host activity only needs to forward `onUserLeaveHint` as a fallback:
 *
 * ```
 * override fun onUserLeaveHint() {
 *     super.onUserLeaveHint()
 *     PictureInPictureController.onUserLeaveHint(this)
 * }
 * ```
 * Auto-enter (setAutoEnterEnabled) is already armed while a video plays, so on Android 12+ the system
 * enters PiP on its own for gesture navigation; the fallback covers 3-button navigation.
 * PiP state in Compose is observed in [PlayerScreen] through `OnPictureInPictureModeChangedProvider`.
 */
object PictureInPictureController {
    @Volatile var playerVisible: Boolean = false
        private set
    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile private var aspect: Float = 16f / 9f
    @Volatile private var sourceRect: Rect? = null

    internal fun setSourceRect(r: Rect) { sourceRect = r }

    fun buildParams(playing: Boolean, aspect: Float = 16f / 9f, sourceRect: Rect? = null): PictureInPictureParams {
        val clamped = aspect.coerceIn(0.5f, 2.3f)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((clamped * 1000).toInt(), 1000))
            .setAutoEnterEnabled(playing)
            .setSeamlessResizeEnabled(true)
            .apply { sourceRect?.let(::setSourceRectHint) }
            .build()
    }

    /** Called by the player screen whenever playback / video size / layout changes. */
    fun update(activity: Activity, playing: Boolean, aspect: Float = 16f / 9f) {
        playerVisible = true
        isPlaying = playing
        this.aspect = aspect
        runCatching { activity.setPictureInPictureParams(buildParams(playing, aspect, sourceRect)) }
    }

    /** Called when the player screen leaves composition. */
    fun disable(activity: Activity?) {
        playerVisible = false
        isPlaying = false
        sourceRect = null
        runCatching {
            activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }

    /** Fallback for activities not relying on auto-enter. Returns true if PiP was requested. */
    fun onUserLeaveHint(activity: Activity): Boolean {
        if (!playerVisible || !isPlaying || activity.isInPictureInPictureMode) return false
        return runCatching { activity.enterPictureInPictureMode(buildParams(true, aspect, sourceRect)) }
            .getOrDefault(false)
    }
}

/** Marks the video area as PiP source rect for a smooth transition animation. */
fun Modifier.pipSourceRect(): Modifier = onGloballyPositioned { coords ->
    PictureInPictureController.setSourceRect(coords.boundsInWindow().toAndroidRect())
}
