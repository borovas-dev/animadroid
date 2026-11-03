package com.borovas.animadroid.provider.animefire

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import com.borovas.animadroid.core.remoteconfig.RemoteConfigRepository
import com.borovas.animadroid.provider.api.AnimeProvider
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AnimefireModule {
    @Provides
    @Singleton
    @IntoMap
    @StringKey(AnimefireProvider.ID)
    fun animefireProvider(client: OkHttpClient, remote: RemoteConfigRepository): AnimeProvider =
        AnimefireProvider(client) { remote.forProvider(AnimefireProvider.ID) }
}
