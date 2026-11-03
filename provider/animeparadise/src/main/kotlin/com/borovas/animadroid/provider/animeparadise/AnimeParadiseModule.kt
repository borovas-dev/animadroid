package com.borovas.animadroid.provider.animeparadise

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
object AnimeParadiseModule {
    @Provides
    @Singleton
    @IntoMap
    @StringKey(AnimeParadiseProvider.ID)
    fun animeParadiseProvider(client: OkHttpClient, remote: RemoteConfigRepository): AnimeProvider =
        AnimeParadiseProvider(client, config = { remote.forProvider(AnimeParadiseProvider.ID) })
}
