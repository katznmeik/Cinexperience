package io.github.katznmeik.cinexperience.core.ui.navigation

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one-off message a closing screen leaves in the saved state of the screen below. */
class ResultMessageTest {

    @Test
    fun aLeftMessageIsSeenThenCleared() {
        val handle = SavedStateHandle()
        val messages = handle.resultMessages()
        assertNull(messages.value)

        handle.leaveResultMessage("Deleted Atelier")
        assertEquals("Deleted Atelier", messages.value)

        handle.clearResultMessage()
        assertNull(messages.value)
        assertNull(handle.get<String>(RESULT_MESSAGE_KEY))
    }

    @Test
    fun aMessageLeftBeforeTheScreenLooksIsKept() {
        // The closing screen writes it before the screen below is shown again and collects it.
        val handle = SavedStateHandle()
        handle.leaveResultMessage("Deleted Atelier")
        assertEquals("Deleted Atelier", handle.resultMessages().value)
    }

    @Test
    fun theNewestMessageWins() {
        val handle = SavedStateHandle()
        handle.leaveResultMessage("Deleted Atelier")
        handle.leaveResultMessage("Deleted Delphi")
        assertEquals("Deleted Delphi", handle.resultMessages().value)
    }

    /** Also the key saved state restored after process death uses, so it stays the same. */
    @Test
    fun keyIsStable() {
        assertEquals("cinemas.resultMessage", RESULT_MESSAGE_KEY)
        val handle = SavedStateHandle(mapOf(RESULT_MESSAGE_KEY to "Deleted Atelier"))
        assertEquals("Deleted Atelier", handle.resultMessages().value)
    }
}
