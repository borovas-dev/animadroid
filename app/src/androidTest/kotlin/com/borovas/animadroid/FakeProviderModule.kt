package com.borovas.animadroid

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.provider.api.AnimeProvider
import com.borovas.animadroid.provider.api.CloudflareChallengeException
import com.borovas.animadroid.provider.hianime.HianimeModule
import com.borovas.animadroid.provider.hianime.HianimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Singleton

/** Offline provider used by instrumented tests. [blockOnce] simulates one Cloudflare challenge. */
class FakeProvider : AnimeProvider {
    override val id = HianimeProvider.ID
    override val displayName = "Fake"
    @Volatile var blockOnce = false

    val anime = Anime("fake-anime-1", id, "Fake Adventure", synopsis = "A story told by tests.", subEpisodes = 3, dubEpisodes = 0)

    override suspend fun search(query: String, mode: TranslationMode): List<Anime> {
        if (blockOnce) { blockOnce = false; throw CloudflareChallengeException("https://example.invalid/") }
        return if (query.contains("fake", ignoreCase = true)) listOf(anime) else emptyList()
    }

    override suspend fun episodes(anime: Anime, mode: TranslationMode) =
        (1..3).map { Episode("ep$it", "$it", "Pilot $it") }

    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet =
        throw IllegalStateException("fake provider has no streams")
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [HianimeModule::class])
object FakeProviderModule {
    val instance = FakeProvider()

    @Provides @Singleton @IntoMap @StringKey(HianimeProvider.ID)
    fun fake(): AnimeProvider = instance
}
