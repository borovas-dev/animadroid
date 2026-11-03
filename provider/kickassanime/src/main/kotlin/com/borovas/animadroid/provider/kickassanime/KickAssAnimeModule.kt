package com.borovas.animadroid.provider.kickassanime

import com.borovas.animadroid.core.remoteconfig.RemoteConfigRepository
import com.borovas.animadroid.provider.api.AnimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object KickAssAnimeModule {
    @Provides
    @Singleton
    @IntoMap
    @StringKey(KickAssAnimeProvider.ID)
    fun kickAssAnimeProvider(client: OkHttpClient, remote: RemoteConfigRepository): AnimeProvider =
        KickAssAnimeProvider(client) { remote.forProvider(KickAssAnimeProvider.ID) }
}
