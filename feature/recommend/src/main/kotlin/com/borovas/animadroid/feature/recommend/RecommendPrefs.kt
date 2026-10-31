package dev.borovas.animadroid.feature.recommend

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recommendStore by preferencesDataStore("recommend")

/** Recommender-only preferences and the cached result. Lives in its own DataStore file; opened lazily. */
@Singleton
class RecommendPrefs @Inject constructor(@ApplicationContext private val context: Context) : RecommendationCache {
    private val store get() = context.recommendStore

    val wifiOnly: Flow<Boolean> = store.data.map { it[WIFI_ONLY] ?: true }
    val hfToken: Flow<String> = store.data.map { it[HF_TOKEN].orEmpty() }

    suspend fun setWifiOnly(v: Boolean) { store.edit { it[WIFI_ONLY] = v } }
    suspend fun setHfToken(v: String) { store.edit { if (v.isBlank()) it.remove(HF_TOKEN) else it[HF_TOKEN] = v.trim() } }
    suspend fun wifiOnlyNow() = wifiOnly.first()
    suspend fun hfTokenNow() = hfToken.first()

    override val cached: Flow<CachedRecommendations?> = store.data.map { p: Preferences ->
        p[CACHE]?.let { runCatching { RecommendJson.decodeFromString<CachedRecommendations>(it) }.getOrNull() }
    }

    override suspend fun save(value: CachedRecommendations?) {
        store.edit { if (value == null) it.remove(CACHE) else it[CACHE] = RecommendJson.encodeToString(CachedRecommendations.serializer(), value) }
    }

    private companion object {
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val HF_TOKEN = stringPreferencesKey("hf_token")
        val CACHE = stringPreferencesKey("cache_v1")
    }
}
