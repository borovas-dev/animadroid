package dev.borovas.animadroid.feature.player

import androidx.navigation3.runtime.NavKey
import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.Episode
import kotlinx.serialization.Serializable

@Serializable
data class PlayerRoute(
    val providerId: String,
    val anime: Anime,
    val episodes: List<Episode>,
    val startIndex: Int,
) : NavKey
