package io.github.katznmeik.cinexperience.feature.cinemas

import io.github.katznmeik.cinexperience.core.data.repository.HallLimitReachedException
import io.github.katznmeik.cinexperience.core.model.DataLimits.MAX_HALLS
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Adding a hall when the app already holds [MAX_HALLS] halls: the dialog closes
 * and the repository's reason is shown, not the generic "Couldn't save".
 */
class HallLimitMessageTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(
        SampleData.store().let { store ->
            val cinemaId = store.cinemas.first().id
            val extra = List(MAX_HALLS - store.halls.size) { i -> Hall(id = 50_000L + i, cinemaId = cinemaId, name = "Extra ${i + 1}") }
            store.copy(halls = store.halls + extra)
        },
    )

    private fun TestScope.viewModel(): ManageCinemasViewModel {
        val vm = ManageCinemasViewModel(repository, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun addingAHallBeyondTheCapShowsWhy() = runTest {
        val vm = viewModel()
        vm.addHall(repository.store.value.cinemas.last().id)
        vm.onNameChange("Saal Neu")
        vm.confirmDialog()

        assertNull(vm.uiState.value.dialog)
        assertEquals(HallLimitReachedException.MESSAGE, vm.uiState.value.message)
        assertEquals(MAX_HALLS, repository.store.value.halls.size)
    }

    @Test
    fun renamingAHallAtTheCapStillWorks() = runTest {
        val vm = viewModel()
        val hall = repository.store.value.halls.first()
        val summary = vm.uiState.value.cinemas.flatMap { it.halls }.single { it.hall.id == hall.id }
        vm.renameHall(summary)
        vm.onNameChange("Renamed")
        vm.confirmDialog()

        assertNull(vm.uiState.value.message)
        assertEquals("Renamed", repository.store.value.halls.single { it.id == hall.id }.name)
    }
}
