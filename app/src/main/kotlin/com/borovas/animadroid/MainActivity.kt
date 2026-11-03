package com.borovas.animadroid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.annotation.StringRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.borovas.animadroid.feature.downloads.EXTRA_OPEN_DOWNLOADS
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.borovas.animadroid.core.designsystem.AnimadroidTheme
import com.borovas.animadroid.core.model.ThemeMode
import com.borovas.animadroid.core.network.DebugCloudflare
import com.borovas.animadroid.feature.player.PictureInPictureController
import com.borovas.animadroid.navigation.AnimadroidApp

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    @javax.inject.Inject lateinit var sync: com.borovas.animadroid.core.sync.SyncRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        armDebugFlags(intent)
        handleAuthRedirect(intent)
        splash.setKeepOnScreenCondition { viewModel.state.value.settings == null }
        enableEdgeToEdge()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) openDownloads = true
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val settings = state.settings
            val dark = when (settings?.themeMode ?: ThemeMode.SYSTEM) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AnimadroidTheme(darkTheme = dark, dynamicColor = settings?.dynamicColor ?: true) {
                if (settings != null) {
                    androidx.compose.foundation.layout.Box {
                        AnimadroidApp(
                            aiRecommendationsEnabled = settings.aiRecommendationsEnabled,
                            openDownloads = openDownloads,
                            onOpenDownloadsHandled = { openDownloads = false },
                        )
                        UpdateBanner(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter))
                    }
                }
            }
        }
    }

    private var openDownloads by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        armDebugFlags(intent)
        handleAuthRedirect(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) openDownloads = true
    }

    /** AniList / MAL OAuth redirect (animadroid://auth/...). */
    private fun handleAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "animadroid" && data.host == "auth") {
            lifecycleScope.launch { sync.handleRedirect(data.toString()) }
        }
    }

    /** Debug builds only: `am start ... --ez debug_force_cf true` simulates a Cloudflare challenge. */
    private fun armDebugFlags(intent: Intent?) {
        if (intent?.getBooleanExtra("debug_force_cf", false) == true) {
            DebugCloudflare.arm(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        }
    }

    /** Feeds the system Keyboard Shortcuts Helper (Meta+/ on ChromeOS/Googlebook/desktop mode). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        fun s(@StringRes label: Int, key: Int) = KeyboardShortcutInfo(getString(label), key, 0)
        data += KeyboardShortcutGroup(
            getString(R.string.shortcuts_player_group),
            listOf(
                s(R.string.shortcut_play_pause, KeyEvent.KEYCODE_SPACE),
                s(R.string.shortcut_seek_back, KeyEvent.KEYCODE_DPAD_LEFT),
                s(R.string.shortcut_seek_forward, KeyEvent.KEYCODE_DPAD_RIGHT),
                s(R.string.shortcut_volume_up, KeyEvent.KEYCODE_DPAD_UP),
                s(R.string.shortcut_volume_down, KeyEvent.KEYCODE_DPAD_DOWN),
                s(R.string.shortcut_mute, KeyEvent.KEYCODE_M),
                s(R.string.shortcut_fullscreen, KeyEvent.KEYCODE_F),
                s(R.string.shortcut_exit_fullscreen, KeyEvent.KEYCODE_ESCAPE),
            ),
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PictureInPictureController.onUserLeaveHint(this)
    }
}
