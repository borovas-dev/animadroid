package dev.borovas.animadroid

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
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.borovas.animadroid.core.designsystem.AnimadroidTheme
import dev.borovas.animadroid.core.model.ThemeMode
import dev.borovas.animadroid.feature.player.PictureInPictureController
import dev.borovas.animadroid.navigation.AnimadroidApp

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.state.value.settings == null }
        enableEdgeToEdge()
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
                if (settings != null) AnimadroidApp(aiRecommendationsEnabled = settings.aiRecommendationsEnabled)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PictureInPictureController.onUserLeaveHint(this)
    }
}
