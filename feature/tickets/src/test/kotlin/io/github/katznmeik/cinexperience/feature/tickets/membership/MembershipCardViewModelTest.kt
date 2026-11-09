package io.github.katznmeik.cinexperience.feature.tickets.membership

import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.model.MembershipCard
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.testing.FakeMembershipCardStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleMembershipCard
import io.github.katznmeik.cinexperience.core.ticket.card.CardImageError
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketFileSource
import kotlinx.coroutines.Dispatchers
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
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/** The membership card screen and its form (spec §6.12, §6.13). */
class MembershipCardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val otherQr = QrModules.of(21) { row, column -> (row + 2 * column) % 3 == 0 }

    private fun TestScope.cardViewModel(store: FakeMembershipCardStore): MembershipCardViewModel {
        val vm = MembershipCardViewModel(store)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    /** Files by URI; any other URI can't be opened. */
    private class Files(vararg files: Pair<String, ByteArray>) : TicketFileSource {
        private val files = files.toMap()
        val opened = mutableListOf<String>()
        override fun open(uri: String) = ByteArrayInputStream(files[uri] ?: throw FileNotFoundException(uri)).also { opened += uri }
    }

    /** Reads "card" as [otherQr]; other bytes as refused with the error named by their text. */
    private val reader = CardImageReader { bytes ->
        when (val text = String(bytes)) {
            "card" -> CardImageReadResult.Read(otherQr)
            else -> CardImageReadResult.Refused(CardImageError.valueOf(text))
        }
    }

    private fun editViewModel(
        store: FakeMembershipCardStore,
        files: TicketFileSource = Files("content://media/card" to "card".toByteArray(), "content://media/none" to "NO_QR_CODE".toByteArray()),
        savedState: SavedStateHandle = SavedStateHandle(),
    ) = EditMembershipCardViewModel(store, files, reader, savedState, Dispatchers.Main, Dispatchers.Main)

    @Test
    fun showsTheStoredCardOrNone() = runTest {
        val store = FakeMembershipCardStore()
        val vm = cardViewModel(store)
        assertEquals(MembershipCardContent.None, vm.uiState.value.content)

        store.current.value = SampleMembershipCard.card
        assertEquals(MembershipCardContent.Shown(SampleMembershipCard.card), vm.uiState.value.content)
    }

    @Test
    fun removesTheCardAfterAConfirmation() = runTest {
        val store = FakeMembershipCardStore(SampleMembershipCard.card)
        val vm = cardViewModel(store)

        vm.onRemove()
        assertTrue(vm.uiState.value.confirmRemove)
        vm.onCancelRemove()
        assertFalse(vm.uiState.value.confirmRemove)
        assertEquals(SampleMembershipCard.card, store.current.value)

        vm.onRemove()
        vm.onConfirmRemove()
        assertNull(store.current.value)
        assertEquals(MembershipCardContent.None, vm.uiState.value.content)
        assertEquals(MembershipCardViewModel.REMOVED, vm.uiState.value.message)
        vm.onMessageShown()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun aFailedRemovalKeepsTheCardAndSaysSo() = runTest {
        val store = FakeMembershipCardStore(SampleMembershipCard.card).apply { failWrites = true }
        val vm = cardViewModel(store)

        vm.onRemove()
        vm.onConfirmRemove()

        assertEquals(MembershipCardContent.Shown(SampleMembershipCard.card), vm.uiState.value.content)
        assertEquals(MembershipCardViewModel.REMOVE_FAILED, vm.uiState.value.message)
    }

    @Test
    fun aNewCardStartsWithTheDefaultTypeAndNeedsAQrCode() = runTest {
        val vm = editViewModel(FakeMembershipCardStore())
        val state = vm.uiState.value

        assertFalse(state.isLoading)
        assertTrue(state.isNew)
        assertNull(state.qrModules)
        assertEquals("Yorck Unlimited", state.membershipType)
        assertFalse(state.canSave)
    }

    @Test
    fun addsACardFromAScreenshotAndTypedTexts() = runTest {
        val store = FakeMembershipCardStore()
        val vm = editViewModel(store)

        vm.onImageChosen("content://media/card")
        vm.onName("  Alex Example ")
        vm.onMemberNumber("TEST-0042")
        assertTrue(vm.uiState.value.canSave)
        vm.onSave()

        assertTrue(vm.uiState.value.isSaved)
        assertEquals(MembershipCard("Alex Example", "TEST-0042", "Yorck Unlimited", otherQr), store.current.value)
    }

    @Test
    fun editingKeepsTheStoredQrCodeUnlessAnotherScreenshotIsChosen() = runTest {
        val store = FakeMembershipCardStore(SampleMembershipCard.card)
        val vm = editViewModel(store)
        assertFalse(vm.uiState.value.isNew)
        assertEquals(SampleMembershipCard.NAME, vm.uiState.value.name)

        vm.onMembershipType("")
        vm.onSave()
        assertEquals(SampleMembershipCard.card.copy(membershipType = null), store.current.value)

        val again = editViewModel(store)
        again.onImageChosen("content://media/card")
        again.onSave()
        assertEquals(otherQr, store.current.value!!.qrModules)
    }

    @Test
    fun aScreenshotWithoutACodeChangesNothingAndSaysWhy() = runTest {
        val store = FakeMembershipCardStore(SampleMembershipCard.card)
        val vm = editViewModel(store)

        vm.onImageChosen("content://media/none")
        assertEquals(CardImageMessage.NO_QR_CODE.message, vm.uiState.value.message)
        assertEquals(SampleMembershipCard.qrModules, vm.uiState.value.qrModules)

        vm.onImageChosen("content://media/missing")
        assertEquals(CardImageMessage.OPEN_FAILED.message, vm.uiState.value.message)

        vm.onImageChosen(null) // cancelled
        assertEquals(SampleMembershipCard.card, store.current.value)
        assertEquals(0, store.writes)
    }

    @Test
    fun everyRefusalHasAMessage() {
        for (error in CardImageError.entries) assertTrue(CardImageMessage.of(error).message.isNotBlank())
    }

    @Test
    fun aTooLargeFileIsNotReadToTheEnd() = runTest {
        val big = object : TicketFileSource {
            override fun open(uri: String) = object : java.io.InputStream() {
                var read = 0L
                override fun read(): Int = if (read++ < Long.MAX_VALUE) 0 else -1
            }
        }
        val vm = editViewModel(FakeMembershipCardStore(), files = big)

        vm.onImageChosen("content://media/big")

        assertEquals(CardImageMessage.TOO_LARGE.message, vm.uiState.value.message)
    }

    @Test
    fun aFailedSaveKeepsTheFormAndSaysSo() = runTest {
        val store = FakeMembershipCardStore().apply { failWrites = true }
        val vm = editViewModel(store)
        vm.onImageChosen("content://media/card")

        vm.onSave()

        assertFalse(vm.uiState.value.isSaved)
        assertEquals(EditMembershipCardViewModel.SAVE_FAILED, vm.uiState.value.message)
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun theFormSurvivesProcessDeath() = runTest {
        val savedState = SavedStateHandle()
        val vm = editViewModel(FakeMembershipCardStore(), savedState = savedState)
        vm.onImageChosen("content://media/card")
        vm.onName("Alex")

        // The stored card changed meanwhile: the restored form keeps what was typed and read.
        val restored = editViewModel(FakeMembershipCardStore(SampleMembershipCard.card), savedState = savedState).uiState.value

        assertEquals("Alex", restored.name)
        assertEquals(otherQr, restored.qrModules)
        assertTrue(restored.isNew)
    }

    @Test
    fun typedTextsAreCappedAtTheCardsLimits() = runTest {
        val vm = editViewModel(FakeMembershipCardStore())

        vm.onName("x".repeat(MembershipCard.MAX_NAME_LENGTH + 5))
        vm.onMemberNumber("🎬".repeat(MembershipCard.MAX_MEMBER_NUMBER_LENGTH + 1))

        assertEquals(MembershipCard.MAX_NAME_LENGTH, vm.uiState.value.name.length)
        assertEquals(MembershipCard.MAX_MEMBER_NUMBER_LENGTH, vm.uiState.value.memberNumber.codePointCount(0, vm.uiState.value.memberNumber.length))
    }
}
