package dev.borovas.animadroid.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Seed = Color(0xFF7C4DFF)

private val LightFallback = lightColorScheme(primary = Seed, secondary = Color(0xFFFF6E91), tertiary = Color(0xFF00BFA5))
private val DarkFallback = darkColorScheme(primary = Color(0xFFCBBEFF), secondary = Color(0xFFFFB1C3), tertiary = Color(0xFF5DDBBC))

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AnimadroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(ctx)
        dynamicColor -> dynamicLightColorScheme(ctx)
        darkTheme -> DarkFallback
        else -> LightFallback
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}
