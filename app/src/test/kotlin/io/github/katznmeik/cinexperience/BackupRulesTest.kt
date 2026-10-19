package io.github.katznmeik.cinexperience

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.settings.SharedPreferencesReminderSettingsStore
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.program.ProgramFiles
import io.github.katznmeik.cinexperience.reminders.AlarmReminderScheduler
import io.github.katznmeik.cinexperience.reminders.ReminderIntentToken
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

    /** (section, domain) of every `<include>`, and (section, "domain/path") of every `<exclude>`. */
    private fun rules(resource: Int): Pair<List<Pair<String?, String>>, List<Pair<String?, String>>> {
        val parser = context.resources.getXml(resource)
        val includes = mutableListOf<Pair<String?, String>>()
        val excludes = mutableListOf<Pair<String?, String>>()
        var section: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "include" -> includes += section to parser.getAttributeValue(null, "domain")
                "exclude" -> excludes += section to parser.getAttributeValue(null, "domain") + "/" + parser.getAttributeValue(null, "path")
            }
        }
        return includes to excludes
    }

    /** The reminders' phone-local preference files (spec §11.3): their alarms' bookkeeping and the intents' token. */
    private val phoneLocal = listOf("sharedpref/reminder_alarms.xml", "sharedpref/reminder_intents.xml")

    @Test
    fun onlyTheDatabaseAndPreferencesAreBackedUp() {
        val (includes, excludes) = rules(R.xml.data_extraction_rules)
        val expected = listOf("database", "sharedpref")
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertEquals(expected, includes.filter { it.first == section }.map { it.second })
            // With includes, everything else (the files domain) is left out; only the phone-local preferences are excluded.
            assertEquals(phoneLocal, excludes.filter { it.first == section }.map { it.second })
        }

        val (legacy, legacyExcludes) = rules(R.xml.backup_rules)
        assertEquals(expected, legacy.map { it.second })
        assertEquals(phoneLocal, legacyExcludes.map { it.second })
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
        val (includes, excludes) = rules(R.xml.data_extraction_rules)
        assertTrue(includes.contains("cloud-backup" to "sharedpref"))
        assertTrue(includes.contains("device-transfer" to "sharedpref"))
        assertTrue(excludes.none { it.second == "sharedpref/" + file.name })
    }

    /** The names the exclusions use are the files' real names. */
    @Test
    fun theExcludedFilesAreTheRemindersPhoneLocalOnes() {
        assertEquals(
            phoneLocal,
            listOf(AlarmReminderScheduler.PREFERENCES_NAME, ReminderIntentToken.PREFERENCES_NAME).map { "sharedpref/$it.xml" },
        )
    }

    @Test
    fun theManifestUsesTheRules() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }
}
