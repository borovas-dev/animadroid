package com.borovas.animadroid.core.datastore

import androidx.datastore.core.DataStoreFactory
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.ThemeMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(name: String = "s.json") =
        DataStoreSettingsRepository.forStore(DataStoreFactory.create(AppSettingsSerializer) { File(tmp.root, name) })

    @Test fun defaultsAndUpdate() = runTest {
        val r = repo()
        assertEquals(FreshSettings, r.settings.first())
        r.update { it.copy(themeMode = ThemeMode.DARK, skipOffsetSec = 3) }
        assertEquals(ThemeMode.DARK, r.settings.first().themeMode)
        assertEquals(3, r.settings.first().skipOffsetSec)
    }

    @Test fun exportImportRoundTrip() = runTest {
        val a = repo("a.json")
        a.update { it.copy(providerId = "x", autoPlayNext = false) }
        val json = a.exportJson()
        val b = repo("b.json")
        b.importJson(json)
        assertEquals(migrateSettingsV2(a.settings.first()), b.settings.first())
    }

    @Test fun unknownKeysIgnored() = runTest {
        val r = repo()
        r.importJson("""{"providerId":"y","future":1}""")
        assertEquals("y", r.settings.first().providerId)
    }

    @Test fun migrationMapsLegacyProviderOnce() = runTest {
        val shinden = migrateSettingsV2(AppSettings(providerId = "shinden"))
        assertEquals("pl", shinden.contentLanguage)
        assertEquals(true, shinden.migratedV2)
        assertEquals("en", migrateSettingsV2(AppSettings(providerId = "hianime")).contentLanguage)
        // Already migrated: the user's later choice of "auto" is not overwritten.
        val again = migrateSettingsV2(shinden.copy(contentLanguage = "auto"))
        assertEquals("auto", again.contentLanguage)
        // Explicit language wins over the legacy provider.
        assertEquals("zh", migrateSettingsV2(AppSettings(providerId = "shinden", contentLanguage = "zh")).contentLanguage)
    }

    @Test fun freshInstallStaysAuto() = runTest {
        val r = DataStoreSettingsRepository.forStore(
            DataStoreFactory.create(AppSettingsSerializer, migrations = listOf(SettingsV2Migration)) { File(tmp.root, "fresh.json") },
        )
        assertEquals("auto", r.settings.first().contentLanguage)
        assertEquals(true, r.settings.first().migratedV2)
    }

    @Test fun migrationRunsThroughStore() = runTest {
        File(tmp.root, "m.json").writeText("""{"providerId":"shinden"}""")
        val r = DataStoreSettingsRepository.forStore(
            DataStoreFactory.create(AppSettingsSerializer, migrations = listOf(SettingsV2Migration)) { File(tmp.root, "m.json") },
        )
        assertEquals("pl", r.settings.first().contentLanguage)
    }
}
