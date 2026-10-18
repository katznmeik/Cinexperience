package io.github.katznmeik.cinexperience

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.settings.SharedPreferencesReminderSettingsStore
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.program.ProgramFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Android Auto Backup covers the database and shared preferences only (spec §8);
 * app files such as the reference screenshots (files/reference/) and the
 * downloaded program (files/program/) are left out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRulesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** (section, domain) of every `<include>`, and the number of `<exclude>` rules. */
    private fun rules(resource: Int): Pair<List<Pair<String?, String>>, Int> {
        val parser = context.resources.getXml(resource)
        val includes = mutableListOf<Pair<String?, String>>()
        var excludes = 0
        var section: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "include" -> includes += section to parser.getAttributeValue(null, "domain")
                "exclude" -> excludes++
            }
        }
        return includes to excludes
    }

    @Test
    fun onlyTheDatabaseAndPreferencesAreBackedUp() {
        val (includes, excludes) = rules(R.xml.data_extraction_rules)
        val expected = listOf("database", "sharedpref")
        assertEquals(expected, includes.filter { it.first == "cloud-backup" }.map { it.second })
        assertEquals(expected, includes.filter { it.first == "device-transfer" }.map { it.second })
        assertEquals(0, excludes) // with includes, everything else (the files domain) is left out

        val (legacy, _) = rules(R.xml.backup_rules)
        assertEquals(expected, legacy.map { it.second })
    }

    /**
     * The downloaded program (spec §8, §11.1 rule 9) lives in files/program/, the
     * "file" domain, which the include-only rules leave out.
     */
    @Test
    fun theDownloadedProgramIsNotBackedUp() {
        assertEquals(context.filesDir.canonicalFile, File(context.filesDir, ProgramFiles.FOLDER).canonicalFile.parentFile)
        val (includes, _) = rules(R.xml.data_extraction_rules)
        val (legacy, _) = rules(R.xml.backup_rules)
        assertTrue((includes + legacy).none { it.second == "file" || it.second == "root" })
    }

    /**
     * The reminder settings (spec §8) are app settings: shared preferences, which Auto
     * Backup covers (the "sharedpref" domain); the backup file doesn't hold them.
     */
    @Test
    fun theReminderSettingsAreBackedUpAsPreferences() {
        val store = SharedPreferencesReminderSettingsStore(context, Dispatchers.Unconfined)
        assertTrue(runBlocking { store.update { it.with(ReminderKind.BEFORE_SHOWING, true) } })
        val file = File(context.dataDir, "shared_prefs/${SharedPreferencesReminderSettingsStore.PREFERENCES_NAME}.xml")
        assertTrue(file.path, file.isFile)
        val (includes, _) = rules(R.xml.data_extraction_rules)
        assertTrue(includes.contains("cloud-backup" to "sharedpref"))
        assertTrue(includes.contains("device-transfer" to "sharedpref"))
    }

    @Test
    fun theManifestUsesTheRules() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }
}
