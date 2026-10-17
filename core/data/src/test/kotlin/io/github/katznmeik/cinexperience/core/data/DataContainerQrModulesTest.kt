package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.repository.SaveTicketResult
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * At app start, [DataContainer.removeExpiredQrModules] removes the QR codes of
 * tickets whose showing is more than 7 days past (spec §4.7), and never throws.
 * Test data is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataContainerQrModulesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun data(scope: CoroutineScope) = DataContainer(context, scope, "1.2.3", Dispatchers.Default, Dispatchers.IO)

    @Test
    fun expiredQrModulesAreRemovedTheTicketStays() = runTest {
        val data = data(backgroundScope)
        val hall = data.hallRepository.createHall(data.cinemaRepository.createCinema("Kiezkino"), "Saal A")
        val qr = QrModules.of(21) { row, column -> row == column }
        // 13 Oct 2026, 20:15 Berlin = 18:15 UTC: the QR code expires on 20 Oct at 18:15 UTC.
        val visit = Visit(hallId = hall, date = LocalDate.of(2026, 10, 13), startTime = LocalTime.of(20, 15))
        val saved = data.ticketRepository.saveVisitWithTicket(visit, Ticket(0, BookingNumber("TEST123"), listOf(TicketSeat("1", "1")), qr))
        val id = (saved as SaveTicketResult.Saved).visitId

        assertEquals(0, data.removeExpiredQrModules(Instant.parse("2026-10-20T18:14:00Z")))
        assertEquals(qr, data.ticketRepository.getTicket(id)!!.qrModules)
        assertEquals(1, data.removeExpiredQrModules(Instant.parse("2026-10-20T18:15:00Z")))
        assertNull(data.ticketRepository.getTicket(id)!!.qrModules)
        assertEquals(BookingNumber("TEST123"), data.ticketRepository.getTicket(id)!!.bookingNumber)
        assertEquals(0, data.removeExpiredQrModules(Instant.parse("2026-10-20T18:15:00Z")))
    }

    @Test
    fun aDatabaseThatCantBeOpenedIsLoggedNotThrown() = runTest {
        // A folder where the database file belongs: opening the database fails.
        assertTrue(context.getDatabasePath(DatabaseConfig.DATABASE_NAME).mkdirs())
        assertEquals(0, data(backgroundScope).removeExpiredQrModules(Instant.parse("2026-10-20T18:15:00Z")))
    }
}
