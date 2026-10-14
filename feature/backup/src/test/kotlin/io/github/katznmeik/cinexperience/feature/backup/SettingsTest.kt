package io.github.katznmeik.cinexperience.feature.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.katznmeik.cinexperience.core.data.backup.BackupCounts
import io.github.katznmeik.cinexperience.core.data.backup.BackupError
import io.github.katznmeik.cinexperience.core.data.backup.BackupException
import io.github.katznmeik.cinexperience.core.data.backup.ImportPreview
import io.github.katznmeik.cinexperience.core.data.backup.ImportSource
import io.github.katznmeik.cinexperience.core.data.backup.LastExport
import io.github.katznmeik.cinexperience.core.data.backup.SafetyCopy
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val EXPORTED_AT: Instant = Instant.parse("2026-10-03T12:05:00Z")
private val COUNTS = BackupCounts(cinemas = 13, halls = 36, visits = 27, overrides = 4, photos = 9, referenceImages = 2)

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val backup = FakeBackupRepository()

    private fun TestScope.viewModel(repository: FakeCinexRepository = FakeCinexRepository(SampleData.store())): SettingsViewModel {
        val vm = SettingsViewModel(repository, backup, "1.2.3", today = { LocalDate.of(2026, 10, 3) })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private val SettingsViewModel.backupState: BackupUiState get() = uiState.value.backup

    @Test
    fun countsComeFromTheRepository() = runTest {
        val vm = viewModel()
        val state = vm.uiState.value
        assertEquals("1.2.3", state.appVersion)
        assertEquals(DataCounts(cinemas = 13, halls = 36, layoutsDrawn = 4, visits = SampleData.visits.size), state.counts)
    }

    @Test
    fun loadFailureDoesNotCrash() = runTest {
        val vm = viewModel(FakeCinexRepository(SampleData.store()).apply { observeFailure = IllegalStateException("db") })
        assertNull(vm.uiState.value.counts)
        assertTrue(vm.uiState.value.countsError)
    }

    @Test
    fun lastExportAndSafetyCopyComeFromTheBackupRepository() = runTest {
        backup.lastExport.value = LastExport(EXPORTED_AT, COUNTS)
        backup.latestSafetyCopy.value = SafetyCopy(EXPORTED_AT)
        val vm = viewModel()
        assertEquals(LastExport(EXPORTED_AT, COUNTS), vm.backupState.lastExport)
        assertEquals(SafetyCopy(EXPORTED_AT), vm.backupState.safetyCopy)
    }

    @Test
    fun exportFileNameHasTodaysDate() = runTest {
        assertEquals("cinexperience-backup-2026-10-03.zip", viewModel().exportFileName())
    }

    @Test
    fun exportShowsProgressThenTheResultAndTheLastExport() = runTest {
        val vm = viewModel()
        backup.gate = CompletableDeferred()
        vm.onExportDestinationChosen("content://downloads/1")
        assertEquals(BackupTask.Exporting(0.5f), vm.backupState.task)
        assertTrue(vm.backupState.isBusy)

        backup.gate!!.complete(Unit)
        assertNull(vm.backupState.task)
        assertEquals("Backup exported · 13 cinemas, 36 halls, 27 visits, 9 photos", vm.backupState.message)
        assertEquals(LastExport(EXPORTED_AT, COUNTS), vm.backupState.lastExport)
        assertEquals(listOf("content://downloads/1"), backup.exportedTo)

        vm.onMessageShown()
        assertNull(vm.backupState.message)
    }

    @Test
    fun cancelledPickerDoesNothing() = runTest {
        val vm = viewModel()
        vm.onExportDestinationChosen(null)
        vm.onImportSourceChosen(null)
        assertEquals(BackupUiState(), vm.backupState)
        assertTrue(backup.exportedTo.isEmpty() && backup.preparedFrom.isEmpty())
    }

    @Test
    fun failedExportShowsTheErrorAndKeepsTheLastExport() = runTest {
        val previous = LastExport(Instant.parse("2026-09-01T10:00:00Z"), COUNTS)
        backup.lastExport.value = previous
        backup.exportFailure = BackupException(BackupError.WRITE_FAILED, "Couldn't write the backup file. Nothing was exported.")
        val vm = viewModel()
        vm.onExportDestinationChosen("content://downloads/1")
        assertNull(vm.backupState.task)
        assertEquals("Couldn't write the backup file. Nothing was exported.", vm.backupState.message)
        assertEquals(previous, vm.backupState.lastExport)
    }

    @Test
    fun unexpectedFailuresBecomeMessages() = runTest {
        backup.exportFailure = IOException("boom")
        backup.prepareFailure = IllegalStateException("boom")
        val vm = viewModel()
        vm.onExportDestinationChosen("content://downloads/1")
        assertEquals("Couldn't export the backup.", vm.backupState.message)
        vm.onImportSourceChosen("content://downloads/2")
        assertEquals("Couldn't read the backup.", vm.backupState.message)
        assertNull(vm.backupState.task)
    }

    @Test
    fun importShowsThePreviewAndReplacesOnConfirm() = runTest {
        val vm = viewModel()
        backup.gate = CompletableDeferred()
        vm.onImportSourceChosen("content://downloads/backup.zip")
        assertEquals(BackupTask.CheckingBackup, vm.backupState.task)
        backup.gate!!.complete(Unit)

        val preview = vm.backupState.preview!!
        assertNull(vm.backupState.task)
        assertEquals(ImportSource.FILE, preview.source)
        assertEquals(COUNTS, preview.counts)
        assertTrue(backup.committed.isEmpty())

        backup.gate = CompletableDeferred()
        vm.onConfirmImport()
        assertNull(vm.backupState.preview)
        assertEquals(BackupTask.Importing(0.5f, ImportSource.FILE), vm.backupState.task)
        assertTrue(backup.isImporting.value)
        backup.gate!!.complete(Unit)
        assertTrue(!backup.isImporting.value)

        assertEquals(listOf(preview), backup.committed)
        assertNull(vm.backupState.task)
        assertEquals("Backup imported · 13 cinemas, 36 halls, 27 visits, 9 photos", vm.backupState.message)
        assertEquals(SafetyCopy(EXPORTED_AT), vm.backupState.safetyCopy)
    }

    @Test
    fun cancelDiscardsThePreparedImport() = runTest {
        val vm = viewModel()
        vm.onImportSourceChosen("content://downloads/backup.zip")
        val preview = vm.backupState.preview!!
        vm.onCancelImport()
        assertNull(vm.backupState.preview)
        assertEquals(listOf(preview), backup.discarded)
        assertTrue(backup.committed.isEmpty())
        assertTrue(!vm.backupState.isBusy)
    }

    @Test
    fun invalidBackupShowsWhyAndNoPreview() = runTest {
        backup.prepareFailure = BackupException(
            BackupError.NEWER_VERSION,
            "This backup was made by a newer version of Cinexperience. Update the app to import it.",
        )
        val vm = viewModel()
        vm.onImportSourceChosen("content://downloads/backup.zip")
        assertNull(vm.backupState.preview)
        assertNull(vm.backupState.task)
        assertEquals("This backup was made by a newer version of Cinexperience. Update the app to import it.", vm.backupState.message)
    }

    @Test
    fun failedImportShowsTheError() = runTest {
        backup.commitFailure = BackupException(BackupError.WRITE_FAILED, "Couldn't import the backup. Your data was not changed.")
        val vm = viewModel()
        vm.onImportSourceChosen("content://downloads/backup.zip")
        vm.onConfirmImport()
        assertNull(vm.backupState.task)
        assertNull(vm.backupState.preview)
        assertEquals("Couldn't import the backup. Your data was not changed.", vm.backupState.message)
    }

    @Test
    fun restoringTheSafetyCopyGoesThroughTheSamePreview() = runTest {
        backup.latestSafetyCopy.value = SafetyCopy(EXPORTED_AT)
        val vm = viewModel()
        vm.onRestoreSafetyCopy()
        assertEquals(listOf(FakeBackupRepository.SAFETY_COPY), backup.preparedFrom)
        assertEquals(ImportSource.SAFETY_COPY, vm.backupState.preview!!.source)
        vm.onConfirmImport()
        assertEquals("Safety copy restored · 13 cinemas, 36 halls, 27 visits, 9 photos", vm.backupState.message)
    }

    @Test
    fun onlyOneBackupOperationAtATime() = runTest {
        val vm = viewModel()
        backup.gate = CompletableDeferred()
        vm.onExportDestinationChosen("content://downloads/1")
        vm.onImportSourceChosen("content://downloads/2")
        vm.onRestoreSafetyCopy()
        vm.onExportDestinationChosen("content://downloads/3")
        backup.gate!!.complete(Unit)
        assertEquals(listOf("content://downloads/1"), backup.exportedTo)
        assertTrue(backup.preparedFrom.isEmpty())

        // An open preview blocks other actions too.
        backup.gate = null
        vm.onImportSourceChosen("content://downloads/2")
        vm.onExportDestinationChosen("content://downloads/4")
        assertEquals(listOf("content://downloads/1"), backup.exportedTo)
    }

    @Test
    fun missingPickerIsReported() = runTest {
        val vm = viewModel()
        vm.onPickerUnavailable()
        assertEquals("No file picker is available on this device.", vm.backupState.message)
    }
}

class BackupTextsTest {
    @Test
    fun texts() {
        assertEquals("Last export: 3 Oct 2026 · 27 visits, 9 photos", lastExportText(LastExport(EXPORTED_AT, COUNTS), ZoneOffset.UTC))
        assertEquals(
            "Last export: 1 Jan 2026 · 1 visit, 0 photos",
            lastExportText(LastExport(Instant.parse("2025-12-31T23:30:00Z"), COUNTS.copy(visits = 1, photos = 0)), ZoneOffset.ofHours(1)),
        )
        assertEquals("3 Oct 2026, 14:05", dateTimeText(EXPORTED_AT, ZoneOffset.ofHours(2)))
        assertEquals("13 cinemas, 36 halls, 27 visits, 9 photos", countsSummary(COUNTS))
        assertEquals(
            listOf("13 cinemas · 36 halls", "27 visits · 9 photos", "4 marked seats · 2 reference images"),
            previewCountLines(COUNTS),
        )
        assertEquals("45%", percentText(0.449f))
        assertEquals("100%", percentText(1.2f))
    }
}

/** Settings screenshots (phone, dark theme); PNGs in feature/backup/build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class SettingsScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun state(backup: BackupUiState): SettingsUiState {
        val cinemas = runBlocking { FakeCinexRepository(SampleData.store()).observeCinemaDetails().first() }
        return SettingsUiState(appVersion = "0.1.0", counts = DataCounts.of(cinemas), backup = backup)
    }

    private fun show(state: SettingsUiState, onOpenManage: () -> Unit = {}, onOpenAllVisits: () -> Unit = {}) {
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) {
                    SettingsScreen(state, onOpenManage = onOpenManage, onOpenAllVisits = onOpenAllVisits)
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** While an import replaces the data, nothing that could write is reachable from Settings. */
    @Test
    fun everythingIsLockedWhileImporting() {
        var opened = 0
        show(
            state(BackupUiState(lastExport = LastExport(EXPORTED_AT, COUNTS), task = BackupTask.Importing(0.6f, ImportSource.FILE))),
            onOpenManage = { opened++ },
            onOpenAllVisits = { opened++ },
        )
        for (row in listOf("Manage cinemas & halls", "All visits", "Export backup")) {
            composeRule.onNodeWithText(row).assertHasNoClickAction().performClick()
        }
        composeRule.onNodeWithText("Importing… 60%").assertExists()
        assertEquals(0, opened)
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/settings-importing.png")
    }

    @Test
    fun dataRowsWorkWhenIdle() {
        var opened = 0
        show(state(BackupUiState()), onOpenManage = { opened++ }, onOpenAllVisits = { opened++ })
        composeRule.onNodeWithText("Manage cinemas & halls").assertHasClickAction().performClick()
        composeRule.onNodeWithText("All visits").assertHasClickAction().performClick()
        assertEquals(2, opened)
    }

    @Test
    fun settings() {
        show(state(BackupUiState(lastExport = LastExport(EXPORTED_AT, COUNTS), safetyCopy = SafetyCopy(Instant.parse("2026-10-01T18:40:00Z")))))
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/settings.png")
    }

    @Test
    fun settingsNeverExported() {
        show(state(BackupUiState()))
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/settings-never-exported.png")
    }

    @Test
    fun exportInProgress() {
        show(state(BackupUiState(lastExport = LastExport(EXPORTED_AT, COUNTS), task = BackupTask.Exporting(0.45f))))
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/settings-exporting.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun importPreview() {
        val preview = ImportPreview("p", ImportSource.FILE, EXPORTED_AT, "0.1.0", 1, COUNTS)
        show(state(BackupUiState(lastExport = LastExport(EXPORTED_AT, COUNTS), preview = preview)))
        captureScreenRoboImage("build/outputs/roborazzi/settings-import-preview.png")
    }
}
