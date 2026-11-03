package com.borovas.animadroid.provider.shinden

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
object ShindenModule {
    @Provides
    @Singleton
    @IntoMap
    @StringKey(ShindenProvider.ID)
    fun shindenProvider(client: OkHttpClient, remote: RemoteConfigRepository): AnimeProvider =
        ShindenProvider(client, config = { remote.forProvider(ShindenProvider.ID) })
}
