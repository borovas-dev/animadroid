package com.borovas.animadroid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.borovas.animadroid.core.designsystem.AnimadroidTheme
import com.borovas.animadroid.core.model.ThemeMode
import com.borovas.animadroid.core.network.DebugCloudflare
import com.borovas.animadroid.feature.player.PictureInPictureController
import com.borovas.animadroid.navigation.AnimadroidApp

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        armDebugFlags(intent)
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
                    AnimadroidApp(
                        aiRecommendationsEnabled = settings.aiRecommendationsEnabled,
                        openDownloads = openDownloads,
                        onOpenDownloadsHandled = { openDownloads = false },
                    )
                }
            }
        }
    }

    private var openDownloads by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        armDebugFlags(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) openDownloads = true
    }

    /** Debug builds only: `am start ... --ez debug_force_cf true` simulates a Cloudflare challenge. */
    private fun armDebugFlags(intent: Intent?) {
        if (intent?.getBooleanExtra("debug_force_cf", false) == true) {
            DebugCloudflare.arm(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PictureInPictureController.onUserLeaveHint(this)
    }
}
