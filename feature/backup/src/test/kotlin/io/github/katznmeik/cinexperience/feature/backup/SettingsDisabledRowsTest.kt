package io.github.katznmeik.cinexperience.feature.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.katznmeik.cinexperience.core.data.backup.ImportSource
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings rows that can't be used right now (an import runs, no program to
 * delete) are buttons announced as disabled, not plain text; informational rows
 * are neither.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h1400dp-port-night-xxhdpi")
class SettingsDisabledRowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var opened = 0

    private val actions = object : BackupActions {
        override fun onExport() {
            opened++
        }

        override fun onImport() {
            opened++
        }
    }

    private val programActions = object : ProgramSettingsActions {
        override fun onDeleteProgram() {
            opened++
        }
    }

    private fun show(backup: BackupUiState, program: ProgramSettingsState) {
        val state = SettingsUiState(appVersion = "1.1.0", buildId = "abc1234", backup = backup, program = program)
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) {
                    SettingsScreen(
                        state,
                        onOpenManage = { opened++ },
                        onOpenAllVisits = { opened++ },
                        actions = actions,
                        programActions = programActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun row(title: String): SemanticsNodeInteraction = composeRule.onNodeWithText(title).performScrollTo()

    private fun SemanticsNodeInteraction.assertIsButton(): SemanticsNodeInteraction =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

    private val withProgram = ProgramSettingsState(isLoaded = true, downloadsOn = true, lastDownload = SampleProgram.FETCHED_AT)

    @Test
    fun rowsLockedByAnImportAreDisabledButtons() {
        show(BackupUiState(task = BackupTask.Importing(0.4f, ImportSource.FILE)), withProgram)
        for (title in listOf("Manage cinemas & halls", "All visits", "Export backup", "Delete downloaded program")) {
            row(title).assertIsButton().assertIsNotEnabled().performClick()
        }
        assertEquals(0, opened)
    }

    @Test
    fun deleteWithoutAProgramIsADisabledButton() {
        show(BackupUiState(), ProgramSettingsState(isLoaded = true, downloadsOn = true))
        row("Delete downloaded program").assertIsButton().assertIsNotEnabled().performClick()
        assertEquals(0, opened)
    }

    @Test
    fun idleRowsAreEnabledButtons() {
        show(BackupUiState(), withProgram)
        for (title in listOf("Export backup", "Import backup", "Manage cinemas & halls", "All visits", "Delete downloaded program")) {
            row(title).assertIsButton().assertIsEnabled().assertHasClickAction().performClick()
        }
        assertEquals(5, opened)
    }

    @Test
    fun informationalRowsAreNeitherButtonsNorDisabled() {
        show(BackupUiState(), withProgram)
        row("Android auto backup")
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
    }
}
