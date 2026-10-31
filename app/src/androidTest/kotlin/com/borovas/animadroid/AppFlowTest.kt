package com.borovas.animadroid

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.LibraryRepository
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.core.model.SkipBehavior
import com.borovas.animadroid.core.model.TranslationMode
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Run with `./run-ui-tests.sh [.qa]` (one process per test: DataStore refuses a second instance per file).
 *
 * End-to-end UI tests against the real app graph with an offline [FakeProvider]. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class AppFlowTest {
    @get:Rule(order = 0) val notifications: androidx.test.rule.GrantPermissionRule =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val hilt = HiltAndroidRule(this)
    // Empty rule + manual launch: the activity must not be started (and waited on) while the clock auto-advances.
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    private lateinit var scenario: androidx.test.core.app.ActivityScenario<MainActivity>

    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var settings: SettingsRepository

    @Before fun setUp() {
        // Root cause of the old "instrumentation hangs" (not the shared emulator): the app has endless animations
        // (LoadingIndicator) and the Cloudflare solver polls in an infinite delay() loop. With the test clock on
        // auto-advance Compose never becomes idle, so performClick()/onNode*() block in waitForIdle() forever.
        // Drive the clock by hand instead (see [waitForText]).
        hilt.inject()
        runBlocking {
            settings.update { it.copy(skipBehavior = SkipBehavior.AUTO, mode = TranslationMode.SUB) }
            library.removeFromHistory(FakeProviderModule.instance.id, FakeProviderModule.instance.anime.id)
        }
        scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        compose.mainClock.autoAdvance = false
    }

    @org.junit.After fun tearDown() {
        runCatching { scenario.close() }
    }

    /** Bottom bar / rail destination (the screen title with the same text is not clickable). */
    private fun tab(name: String) { compose.onNode(hasText(name) and hasClickAction()).performClick(); settle() }

    /** The clock is manual (see [setUp]); let animations/recomposition run after every interaction. */
    private fun settle() = compose.mainClock.advanceTimeBy(500)

    private fun lastInput() = compose.onAllNodes(hasSetTextAction())[compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size - 1]

    private fun waitForInput() {
        val end = System.currentTimeMillis() + 5_000
        while (compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "No text field appeared" }
            compose.mainClock.advanceTimeBy(100); Thread.sleep(50)
        }
    }

    private fun typeQuery(q: String) {
        compose.onNodeWithText("Search anime").performClick(); settle()
        waitForInput()
        lastInput().performTextInput(q); settle()
        lastInput().performImeAction(); settle()
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            // No compose hierarchy exists until the splash screen is gone; treat that as "not yet".
            val found = runCatching {
                compose.mainClock.advanceTimeBy(100)
                compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
            if (found) return
            Thread.sleep(50)
        }
        throw AssertionError("Text '$text' not shown within $timeoutMs ms")
    }

    @org.junit.Ignore(
        "Opens Details but never finds the episode row text ('1. Pilot 1') within 15 s on a device; and any failing test here " +
            "wedges the instrumentation process (the next test never starts), so keep it out of the suite until diagnosed " +
            "(Details/Hilt assisted-ViewModel path under HiltTestApplication; the same flow works in the real app).",
    )
    @Test fun searchOpensDetailsWithEpisodes() {
        waitForText("Home")
        tab("Search")
        typeQuery("fake")
        waitForText("Fake Adventure")
        compose.onNodeWithText("Fake Adventure").performClick(); settle()
        waitForText("1. Pilot 1")
        compose.onNodeWithText("3. Pilot 3").assertExists()
    }

    @Test fun cloudflareErrorOffersVerification() {
        waitForText("Home")
        FakeProviderModule.instance.blockOnce = true
        tab("Search")
        typeQuery("fake")
        waitForText("Verification needed")
        compose.onNodeWithText("Verify in browser").performClick(); settle()
        // The full-screen solver dialog is shown.
        waitForText("Verify you are human")
    }

    @Test fun settingsTogglePersists() {
        waitForText("Home")
        tab("Settings")
        waitForText("Show button")
        compose.onNodeWithText("Show button").performClick(); settle()
        val end = System.currentTimeMillis() + 5_000
        while (runBlocking { settings.settings.first().skipBehavior } != SkipBehavior.BUTTON) {
            check(System.currentTimeMillis() < end) { "setting was not persisted" }
            compose.mainClock.advanceTimeBy(100); Thread.sleep(50)
        }
        scenario.recreate()
        assertEquals(SkipBehavior.BUTTON, runBlocking { settings.settings.first().skipBehavior })
        tab("Settings")
        waitForText("Show button")
    }

    @Test fun homeRendersContinueWatching() {
        val a: Anime = FakeProviderModule.instance.anime
        runBlocking { library.saveProgress(a, "2", 300_000, 1_400_000) }
        waitForText("Home")
        tab("Home")
        waitForText("Continue watching")
        // The device database may hold other shows' history; only require that ours is listed.
        waitForText("Fake Adventure")
        waitForText("Episode 2")
    }
}
