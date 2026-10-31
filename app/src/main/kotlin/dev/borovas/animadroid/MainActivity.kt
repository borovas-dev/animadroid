package dev.borovas.animadroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import dagger.hilt.android.AndroidEntryPoint
import dev.borovas.animadroid.core.designsystem.AnimadroidTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnimadroidTheme {
                Scaffold { pad ->
                    Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                        Text("animadroid", Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}
