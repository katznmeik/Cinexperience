package io.github.katznmeik.cinexperience.feature.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.katznmeik.cinexperience.core.data.backup.ImportSource
import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.ProgramError
import io.github.katznmeik.cinexperience.core.program.ProgramStatus
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings → Program (spec §6.7): the switch, the last download and deleting the program. */
class SettingsProgramViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val backup = FakeBackupRepository()
    private val program = FakeProgramRepository.withProgram()

    private fun TestScope.viewModel(): SettingsViewModel {
        val vm = SettingsViewModel(FakeCinexRepository(SampleData.store()), backup, program, "1.2.3", "abc1234")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private val SettingsViewModel.programState: ProgramSettingsState get() = uiState.value.program

    @Test
    fun showsTheSwitchAndTheLastDownload() = runTest {
        val vm = viewModel()
        assertTrue(vm.programState.downloadsOn)
        assertEquals(SampleProgram.FETCHED_AT, vm.programState.lastDownload)
    }

    @Test
    fun theSwitchTurnsDownloadsOffAndOnWithoutDownloading() = runTest {
        val vm = viewModel()
        vm.onProgramDownloadsChanged(false)
        assertEquals(DownloadPermission.OFF, program.state.value.permission)
        assertFalse(vm.programState.downloadsOn)
        vm.onProgramDownloadsChanged(true)
        assertEquals(DownloadPermission.ALLOWED, program.state.value.permission)
        assertTrue(program.refreshCalls.isEmpty())
        assertEquals(0, program.downloads)
    }

    @Test
    fun deletingAsksFirst() = runTest {
        val vm = viewModel()
        vm.onDeleteProgram()
        assertTrue(vm.programState.confirmingDelete)
        vm.onCancelDeleteProgram()
        assertFalse(vm.programState.confirmingDelete)
        assertTrue(vm.programState.hasProgram)

        vm.onDeleteProgram()
        vm.onConfirmDeleteProgram()
        assertNull(program.state.value.program)
        assertFalse(vm.programState.confirmingDelete)
        assertNull(vm.programState.lastDownload)
    }

    @Test
    fun nothingChangesWhileImporting() = runTest {
        val vm = viewModel()
        // Start an import: prepare, then commit (held by the gate).
        vm.onImportSourceChosen("content://downloads/1")
        backup.gate = kotlinx.coroutines.CompletableDeferred()
        vm.onConfirmImport()
        assertTrue(vm.uiState.value.backup.task is BackupTask.Importing)

        vm.onProgramDownloadsChanged(false)
        vm.onDeleteProgram()
        assertEquals(DownloadPermission.ALLOWED, program.state.value.permission)
        assertFalse(vm.programState.confirmingDelete)
        backup.gate!!.complete(Unit)
    }

    @Test
    fun onlySettingsFailuresAreShownHere() = runTest {
        val vm = viewModel()
        program.state.update { it.copy(status = ProgramStatus.Failed(ProgramError.OFFLINE)) }
        assertNull(vm.programState.errorMessage)
        program.state.update { it.copy(status = ProgramStatus.Failed(ProgramError.DELETE_FAILED)) }
        assertEquals(ProgramError.DELETE_FAILED.message, vm.programState.errorMessage)
    }
}

/** Screenshots of Settings → Program; PNGs in feature/backup/build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h1400dp-port-night-xxhdpi")
class SettingsProgramScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(program: ProgramSettingsState, importing: Boolean = false, actions: ProgramSettingsActions = ProgramSettingsActions.None) {
        val backup = if (importing) BackupUiState(task = BackupTask.Importing(0.3f, ImportSource.FILE)) else BackupUiState()
        val state = SettingsUiState(appVersion = "1.1.0", buildId = "abc1234", backup = backup, program = program)
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) {
                    SettingsScreen(state, onOpenManage = {}, onOpenAllVisits = {}, programActions = actions)
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun downloadsOn() {
        val changes = mutableListOf<Boolean>()
        show(
            ProgramSettingsState(isLoaded = true, downloadsOn = true, lastDownload = SampleProgram.FETCHED_AT),
            actions = object : ProgramSettingsActions {
                override fun onDownloadsChanged(enabled: Boolean) {
                    changes += enabled
                }
            },
        )
        composeRule.onNodeWithText("Last download: 15 Oct 2026, 10:00").performScrollTo().assertExists()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/settings-program-on.png")
        composeRule.onNodeWithText("Download program from yorck.de").assertExists()
        composeRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOn().performClick()
        assertEquals(listOf(false), changes)
    }

    @Test
    fun downloadsOff() {
        show(ProgramSettingsState(isLoaded = true, downloadsOn = false))
        composeRule.onNodeWithText("No program downloaded").performScrollTo().assertExists()
        composeRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOff()
        composeRule.onNodeWithText("Delete downloaded program").assertHasNoClickAction()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/settings-program-off.png")
    }

    @Test
    fun lockedWhileImporting() {
        show(ProgramSettingsState(isLoaded = true, downloadsOn = true, lastDownload = SampleProgram.FETCHED_AT), importing = true)
        composeRule.onNodeWithText("Delete downloaded program").performScrollTo().assertHasNoClickAction()
        composeRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsNotEnabled()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun deleteConfirmation() {
        show(ProgramSettingsState(isLoaded = true, downloadsOn = true, lastDownload = SampleProgram.FETCHED_AT, confirmingDelete = true))
        captureScreenRoboImage("build/outputs/roborazzi/settings-program-delete.png")
    }
}
