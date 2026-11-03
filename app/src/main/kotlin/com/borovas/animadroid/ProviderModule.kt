package com.borovas.animadroid

import com.borovas.animadroid.core.model.ProviderRegistry
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.provider.api.AnimeProvider
import com.borovas.animadroid.core.remoteconfig.RemoteConfigRepository
import com.borovas.animadroid.provider.api.ProviderAvailability
import com.borovas.animadroid.provider.api.ProviderChain
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Wires every multibound [AnimeProvider] into the singleton [ProviderChain]. */
@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {
    @Provides
    @Singleton
    fun providerChain(
        providers: Map<String, @JvmSuppressWildcards AnimeProvider>,
        settings: SettingsRepository,
        remote: RemoteConfigRepository,
    ): ProviderChain =
        ProviderChain(providers, settings, availability = ProviderAvailability { remote.forProvider(it).enabled })

    @Provides
    fun providerRegistry(chain: ProviderChain): ProviderRegistry = chain
}
