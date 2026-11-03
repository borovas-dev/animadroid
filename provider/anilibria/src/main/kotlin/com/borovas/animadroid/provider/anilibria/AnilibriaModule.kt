package com.borovas.animadroid.provider.anilibria

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
object AnilibriaModule {
    @Provides
    @Singleton
    @IntoMap
    @StringKey(AnilibriaProvider.ID)
    fun anilibriaProvider(client: OkHttpClient, remote: RemoteConfigRepository): AnimeProvider =
        AnilibriaProvider(client) { remote.forProvider(AnilibriaProvider.ID) }
}
