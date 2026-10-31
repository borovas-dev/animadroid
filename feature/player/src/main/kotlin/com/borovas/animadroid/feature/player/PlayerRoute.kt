package com.borovas.animadroid.feature.player

import androidx.navigation3.runtime.NavKey
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import kotlinx.serialization.Serializable

@Serializable
data class PlayerRoute(
    val providerId: String,
    val anime: Anime,
    val episodes: List<Episode>,
    val startIndex: Int,
    /** Play only downloaded media: never calls the provider; episodes must be downloaded. */
    val offline: Boolean = false,
) : NavKey
