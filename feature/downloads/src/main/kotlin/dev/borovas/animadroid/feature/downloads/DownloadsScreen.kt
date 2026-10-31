package dev.borovas.animadroid.feature.downloads

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.borovas.animadroid.core.designsystem.AnimadroidTheme
import dev.borovas.animadroid.core.designsystem.EmptyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object DownloadsRoute : NavKey

/** Placeholder: offline downloads are not implemented yet. */
data class DownloadsUiState(val comingSoon: Boolean = true)

@HiltViewModel
class DownloadsViewModel @Inject constructor() : ViewModel() {
    val state: StateFlow<DownloadsUiState> = MutableStateFlow(DownloadsUiState())
}

fun EntryProviderScope<NavKey>.downloadsEntry() {
    entry<DownloadsRoute> {
        val vm: DownloadsViewModel = hiltViewModel()
        @Suppress("UNUSED_VARIABLE") val state = vm.state.collectAsStateWithLifecycle().value
        DownloadsScreen()
    }
}

@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    EmptyState(
        Icons.Rounded.Download, "Downloads are coming soon",
        "Soon you will be able to save episodes and watch them offline.", modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun DownloadsPreview() = AnimadroidTheme(dynamicColor = false) { DownloadsScreen() }
