package com.borovas.animadroid.feature.settings

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.provider.api.AnimeProvider
import com.borovas.animadroid.provider.api.ProviderChain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private class FakeSettings(initial: AppSettings) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}

private class P(
    override val id: String, override val contentLanguages: Set<String>,
    override val gfwBlocked: Boolean = false, override val priority: Int = 100,
) : AnimeProvider {
    override val displayName = id
    override suspend fun search(query: String, mode: TranslationMode) = emptyList<Anime>()
    override suspend fun episodes(anime: Anime, mode: TranslationMode) = emptyList<Episode>()
    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet = error("unused")
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(settings: AppSettings, repo: FakeSettings = FakeSettings(settings)): Pair<SettingsViewModel, FakeSettings> {
        val providers = listOf(
            P("hianime", setOf("en"), gfwBlocked = true, priority = 0), P("anidb", setOf("en"), priority = 1),
            P("shinden", setOf("pl")), P("xifan", setOf("zh")), P("ja", setOf("ja")),
        )
        return SettingsViewModel(repo, ProviderChain(providers.associateBy { it.id }, repo)) to repo
    }

    @Test fun listsLanguagesAndSources() = runTest {
        val (vm, _) = vm(AppSettings(contentLanguage = "pl", disabledProviders = setOf("anidb")))
        val st = vm.state.first { it.sources.isNotEmpty() }
        assertEquals(listOf("auto", "en", "pl", "zh", "ru", "pt", "ja"), st.languages)
        assertEquals(listOf("shinden" to true, "hianime" to true, "anidb" to false), st.sources.map { it.id to it.enabled })
    }

    @Test fun moveAndDisablePersist() {
        val (vm, repo) = vm(AppSettings(contentLanguage = "pl"))
        vm.moveSource("anidb", -1)
        assertEquals(listOf("shinden", "anidb", "hianime"), repo.state.value.providerOrder["pl"])
        vm.setSourceEnabled("anidb", false)
        assertEquals(setOf("anidb"), repo.state.value.disabledProviders)
        vm.setSourceEnabled("anidb", true)
        assertEquals(emptySet<String>(), repo.state.value.disabledProviders)
    }

    @Test fun zhHidesBlockedProviders() {
        val (vm, repo) = vm(AppSettings(contentLanguage = "zh"))
        vm.moveSource("anidb", -1)
        assertEquals(listOf("anidb", "xifan"), repo.state.value.providerOrder["zh"])
    }
}
