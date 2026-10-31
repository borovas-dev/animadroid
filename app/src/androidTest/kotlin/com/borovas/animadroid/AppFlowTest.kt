package com.borovas.animadroid

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.LibraryRepository
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.core.model.SkipBehavior
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** End-to-end UI tests against the real app graph with an offline [FakeProvider]. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class AppFlowTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var settings: SettingsRepository

    @Before fun setUp() {
        hilt.inject()
        runBlocking {
            settings.update { it.copy(skipBehavior = SkipBehavior.AUTO) }
            library.removeFromHistory(FakeProviderModule.instance.id, FakeProviderModule.instance.anime.id)
        }
    }

    private fun typeQuery(q: String) {
        compose.onNodeWithText("Search anime").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput(q)
        compose.onAllNodes(hasSetTextAction()).onLast().performImeAction()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun searchOpensDetailsWithEpisodes() {
        compose.onNodeWithText("Search").performClick()
        typeQuery("fake")
        waitForText("Fake Adventure")
        compose.onNodeWithText("Fake Adventure").performClick()
        waitForText("1. Pilot 1")
        compose.onNodeWithText("3. Pilot 3").assertExists()
    }

    @Test fun cloudflareErrorOffersVerification() {
        FakeProviderModule.instance.blockOnce = true
        compose.onNodeWithText("Search").performClick()
        typeQuery("fake")
        waitForText("Verification needed")
        compose.onNodeWithText("Verify in browser").performClick()
        // The full-screen solver dialog is shown.
        waitForText("Verify you are human")
    }

    @Test fun settingsTogglePersists() {
        compose.onNodeWithText("Settings").performClick()
        waitForText("Show button")
        compose.onNodeWithText("Show button").performClick()
        compose.waitUntil(5_000) { runBlocking { settings.settings.first().skipBehavior } == SkipBehavior.BUTTON }
        compose.activityRule.scenario.recreate()
        assertEquals(SkipBehavior.BUTTON, runBlocking { settings.settings.first().skipBehavior })
        compose.onNodeWithText("Settings").performClick()
        waitForText("Show button")
    }

    @Test fun homeRendersContinueWatching() {
        val a: Anime = FakeProviderModule.instance.anime
        runBlocking { library.saveProgress(a, "2", 300_000, 1_400_000) }
        compose.onNodeWithText("Home").performClick()
        waitForText("Continue watching")
        compose.onNodeWithText("Fake Adventure").assertExists()
        compose.onNodeWithText("Episode 2").assertExists()
    }
}
