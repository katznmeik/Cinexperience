package io.github.katznmeik.cinexperience.feature.backup

import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.testing.FakeReminderSettingsStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Settings → Reminders (spec §6.7, §11.3 rule 1): the switches, the lead time and the notification permission. */
class ReminderSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val store = FakeReminderSettingsStore()
    private val importing = MutableStateFlow(false)
    private val savedState = SavedStateHandle()

    private fun TestScope.viewModel(): ReminderSettingsViewModel {
        val vm = ReminderSettingsViewModel(store, importing, savedState)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private val ReminderSettingsViewModel.state: ReminderSettingsState get() = uiState.value

    @Test
    fun bothAreOffUntilSwitchedOn() = runTest {
        val vm = viewModel()
        assertTrue(vm.state.isLoaded)
        assertEquals(ReminderSettings(), vm.state.settings)
        assertFalse(vm.state.showsPermissionNote)
    }

    @Test
    fun withThePermissionASwitchGoesOnAtOnce() = runTest {
        val vm = viewModel()
        assertFalse(vm.onSwitchChanged(ReminderKind.BEFORE_SHOWING, on = true, notificationsAllowed = true))
        assertEquals(ReminderSettings(beforeShowing = true), store.current.value)
        assertFalse(vm.onSwitchChanged(ReminderKind.RATE_VISIT, on = true, notificationsAllowed = true))
        assertEquals(ReminderSettings(beforeShowing = true, rateAfterVisit = true), vm.state.settings)
        // Off needs no permission and is stored at once (the app then cancels the alarms).
        assertFalse(vm.onSwitchChanged(ReminderKind.BEFORE_SHOWING, on = false, notificationsAllowed = false))
        assertEquals(ReminderSettings(rateAfterVisit = true), store.current.value)
    }

    @Test
    fun withoutThePermissionItIsAskedForFirst() = runTest {
        val vm = viewModel()
        assertTrue(vm.onSwitchChanged(ReminderKind.RATE_VISIT, on = true, notificationsAllowed = false))
        assertEquals("not before the answer", ReminderSettings(), store.current.value)
        vm.onPermissionResult(allowed = true)
        assertEquals(ReminderSettings(rateAfterVisit = true), store.current.value)
        assertFalse(vm.state.showsPermissionNote)
        // The answer is used once.
        vm.onSwitchChanged(ReminderKind.RATE_VISIT, on = false, notificationsAllowed = true)
        vm.onPermissionResult(allowed = true)
        assertEquals(ReminderSettings(), store.current.value)
    }

    @Test
    fun refusedTheSwitchStaysOffAndTheNoteIsShown() = runTest {
        val vm = viewModel()
        assertTrue(vm.onSwitchChanged(ReminderKind.BEFORE_SHOWING, on = true, notificationsAllowed = false))
        vm.onPermissionResult(allowed = false)
        assertEquals(ReminderSettings(), store.current.value)
        assertTrue(vm.state.permissionRefused)
        assertTrue(vm.state.showsPermissionNote)
        // Allowed in Android's settings meanwhile: the note goes when the screen checks again.
        vm.onNotificationsChecked(true)
        assertFalse(vm.state.showsPermissionNote)
        assertFalse(vm.state.permissionRefused)
    }

    @Test
    fun aPermissionRevokedLaterShowsTheNoteWithTheSwitchStillOn() = runTest {
        store.current.value = ReminderSettings(beforeShowing = true)
        val vm = viewModel()
        vm.onNotificationsChecked(true)
        assertFalse(vm.state.showsPermissionNote)
        vm.onNotificationsChecked(false)
        assertTrue(vm.state.settings.beforeShowing)
        assertTrue(vm.state.showsPermissionNote)
        // Switched off, the note goes too (nothing is waiting for notifications).
        vm.onSwitchChanged(ReminderKind.BEFORE_SHOWING, on = false, notificationsAllowed = false)
        assertFalse(vm.state.showsPermissionNote)
    }

    @Test
    fun theWaitingSwitchSurvivesTheProcess() = runTest {
        viewModel().onSwitchChanged(ReminderKind.BEFORE_SHOWING, on = true, notificationsAllowed = false)
        // A new ViewModel with the same saved state (the activity was recreated while Android asked).
        viewModel().onPermissionResult(allowed = true)
        assertEquals(ReminderSettings(beforeShowing = true), store.current.value)
    }

    @Test
    fun theLeadTimeIsOneTwoOrThreeHours() = runTest {
        val vm = viewModel()
        assertEquals(ReminderLeadTime.TWO_HOURS, vm.state.settings.leadTime)
        vm.onLeadTimeChanged(ReminderLeadTime.THREE_HOURS)
        assertEquals(ReminderLeadTime.THREE_HOURS, store.current.value.leadTime)
        vm.onLeadTimeChanged(ReminderLeadTime.ONE_HOUR)
        assertEquals(ReminderLeadTime.ONE_HOUR, vm.state.settings.leadTime)
    }

    @Test
    fun aFailedWriteSaysSo() = runTest {
        val vm = viewModel()
        store.failWrites = true
        vm.onSwitchChanged(ReminderKind.BEFORE_SHOWING, on = true, notificationsAllowed = true)
        assertEquals(ReminderSettingsViewModel.NOT_SAVED, vm.state.errorMessage)
        assertFalse(vm.state.settings.beforeShowing)
        store.failWrites = false
        vm.onSwitchChanged(ReminderKind.BEFORE_SHOWING, on = true, notificationsAllowed = true)
        assertNull(vm.state.errorMessage)
        vm.onNotificationSettingsUnavailable()
        assertEquals(ReminderSettingsViewModel.SETTINGS_UNAVAILABLE, vm.state.errorMessage)
    }

    @Test
    fun nothingChangesWhileABackupImportRuns() = runTest {
        val vm = viewModel()
        assertTrue(vm.onSwitchChanged(ReminderKind.RATE_VISIT, on = true, notificationsAllowed = false))
        importing.value = true
        assertFalse(vm.onSwitchChanged(ReminderKind.BEFORE_SHOWING, on = true, notificationsAllowed = true))
        vm.onLeadTimeChanged(ReminderLeadTime.ONE_HOUR)
        vm.onPermissionResult(allowed = true)
        assertEquals(ReminderSettings(), store.current.value)
        assertEquals(0, store.writes)
    }
}
