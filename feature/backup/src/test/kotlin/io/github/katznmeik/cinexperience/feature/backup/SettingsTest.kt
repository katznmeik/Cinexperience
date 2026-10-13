package io.github.katznmeik.cinexperience.feature.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlinx.coroutines.launch
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

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun countsComeFromTheRepository() = runTest {
        val vm = SettingsViewModel(FakeCinexRepository(SampleData.store()), "1.2.3")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val state = vm.uiState.value
        assertEquals("1.2.3", state.appVersion)
        assertEquals(DataCounts(cinemas = 13, halls = 36, layoutsDrawn = 4, visits = SampleData.visits.size), state.counts)
    }

    @Test
    fun loadFailureDoesNotCrash() = runTest {
        val repository = FakeCinexRepository(SampleData.store()).apply { observeFailure = IllegalStateException("db") }
        val vm = SettingsViewModel(repository, "1.2.3")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertNull(vm.uiState.value.counts)
        assertTrue(vm.uiState.value.countsError)
    }
}

/** Settings screenshot (phone, dark theme); PNG in feature/backup/build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class SettingsScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settings() {
        val state = SettingsUiState(appVersion = "0.1.0", counts = DataCounts(13, 36, 4, 27))
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) {
                    SettingsScreen(state, onOpenManage = {}, onOpenAllVisits = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/settings.png")
    }
}
