package com.borovas.animadroid.feature.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import com.borovas.animadroid.core.designsystem.AnimadroidTheme
import com.borovas.animadroid.core.designsystem.EmptyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object RecommendRoute : NavKey

/** Placeholder: on-device (Gemma) recommendations are not implemented yet. */
data class RecommendUiState(val comingSoon: Boolean = true)

@HiltViewModel
class RecommendViewModel @Inject constructor() : ViewModel() {
    val state: StateFlow<RecommendUiState> = MutableStateFlow(RecommendUiState())
}

fun EntryProviderScope<NavKey>.recommendEntry() {
    entry<RecommendRoute> {
        @Suppress("UNUSED_VARIABLE") val vm: RecommendViewModel = hiltViewModel()
        RecommendScreen()
    }
}

@Composable
fun RecommendScreen(modifier: Modifier = Modifier) {
    EmptyState(
        Icons.Rounded.AutoAwesome, "Recommendations are coming soon",
        "Personalized picks generated on your device from your watch history.", modifier,
    )
}

/** Compact card for the home screen. */
@Composable
fun RecommendCard(modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null)
            Column {
                Text("For you", style = MaterialTheme.typography.titleMedium)
                Text("On-device recommendations are coming soon.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RecommendPreview() = AnimadroidTheme(dynamicColor = false) {
    Column { RecommendCard(Modifier.padding(16.dp)); RecommendScreen() }
}
