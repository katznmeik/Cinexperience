package io.github.katznmeik.cinexperience.core.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import io.github.katznmeik.cinexperience.core.data.db.TicketEntity
import io.github.katznmeik.cinexperience.core.data.json.TicketSeatsJson
import io.github.katznmeik.cinexperience.core.data.repository.SaveTicketResult
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The database itself refuses a second ticket with a booking number (its UNIQUE
 * index) instead of letting the write pass silently, and a ticket that can't be
 * written takes the visit saved with it back (one transaction). The repository's
 * own check normally refuses such a ticket first (RepositoryContract); these are
 * the backstops behind it. Test data is invented.
 */
internal class TicketDatabaseBackstopTest : DatabaseTest() {

    private val date = LocalDate.of(2026, 10, 24)
    private val seatsJson = TicketSeatsJson.encode(listOf(TicketSeat("1", "2")))

    private suspend fun hall(): Long = halls.createHall(cinemas.createCinema("Kiezkino"), "Saal A")

    private suspend fun savedWithTicket(visit: Visit, number: String): Long =
        (tickets.saveVisitWithTicket(visit, Ticket(visit.id, BookingNumber(number), listOf(TicketSeat("1", "2")))) as SaveTicketResult.Saved)
            .visitId

    /** Makes every [operation] (INSERT or UPDATE) on `ticket` fail, as a full disk or a damaged database might. */
    private fun failTicketWrites(operation: String) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_ticket_$operation BEFORE $operation ON ticket BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
    }

    @Test
    fun aConflictingBookingNumberFailsLoudlyInTheDatabase() = runTest {
        val hall = hall()
        val first = savedWithTicket(Visit(hallId = hall, date = date), "TEST123")
        val second = savedWithTicket(Visit(hallId = hall, date = date), "OTHER42")
        val dao = db.ticketDao()

        // Another visit's booking number: on insert and on update.
        val third = visits.save(Visit(hallId = hall, date = date))
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.insert(TicketEntity(third, "TEST123", seatsJson, null)) }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.update(TicketEntity(second, "TEST123", seatsJson, null)) }
        }
        // A second ticket for a visit.
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.insert(TicketEntity(first, "NEW1", seatsJson, null)) }
        }

        assertEquals(first, tickets.findVisitId(BookingNumber("TEST123")))
        assertEquals(BookingNumber("OTHER42"), tickets.getTicket(second)!!.bookingNumber)
        assertNull(tickets.getTicket(third))
        assertNull(tickets.findVisitId(BookingNumber("NEW1")))
    }

    @Test
    fun aTicketThatCantBeInsertedTakesTheNewVisitBack() = runTest {
        val hall = hall()
        failTicketWrites("INSERT")
        val visit = Visit(hallId = hall, date = date, filmTitle = "The Quiet Projectionist")
        assertThrows(SQLiteException::class.java) {
            runBlocking { tickets.saveVisitWithTicket(visit, Ticket(0, BookingNumber("TEST123"), listOf(TicketSeat("1", "2")))) }
        }
        assertTrue(visits.observeAllVisits().first().isEmpty())
        assertNull(tickets.findVisitId(BookingNumber("TEST123")))
    }

    @Test
    fun aTicketThatCantBeUpdatedLeavesTheVisitAsItWas() = runTest {
        val hall = hall()
        val id = savedWithTicket(Visit(hallId = hall, date = date, filmTitle = "The Quiet Projectionist"), "TEST123")
        failTicketWrites("UPDATE")
        val changed = visits.getVisit(id)!!.copy(filmTitle = "Changed")
        assertThrows(SQLiteException::class.java) {
            runBlocking { tickets.saveVisitWithTicket(changed, Ticket(id, BookingNumber("TEST123"), listOf(TicketSeat("7", "10")))) }
        }
        assertEquals("The Quiet Projectionist", visits.getVisit(id)!!.filmTitle)
        assertEquals(listOf(TicketSeat("1", "2")), tickets.getTicket(id)!!.seats)
    }
}
