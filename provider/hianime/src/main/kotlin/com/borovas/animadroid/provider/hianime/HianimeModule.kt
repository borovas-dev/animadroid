package com.borovas.animadroid.provider.hianime

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import com.borovas.animadroid.provider.api.AnimeProvider
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object HianimeModule {
    @Provides
    @Singleton
    @IntoMap
    @StringKey(HianimeProvider.ID)
    fun hianimeProvider(client: OkHttpClient): AnimeProvider = HianimeProvider(client)
}
