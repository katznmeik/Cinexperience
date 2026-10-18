package io.github.katznmeik.cinexperience.core.data.contract

import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.DeleteResult
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.SaveTicketResult
import io.github.katznmeik.cinexperience.core.data.repository.SeatOverrideRepository
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.data.save
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.labels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The repository rules that the in-memory fake of core:testing
 * (`FakeCinexRepository`, which every ViewModel and screenshot test runs on)
 * re-implements, checked against the Room repositories and the fake alike
 * ([RoomRepositoryContractTest], [FakeRepositoryContractTest]), so the two can't
 * drift apart unnoticed: seat ids and the hall's id mark, the overrides a layout
 * edit prunes, one favourite seat per hall, a seat cleared on save, deletes,
 * favourite cinemas and tickets (unique booking numbers, deleted with their
 * visit, QR modules stored again and expired). Each scenario starts on empty
 * data and uses only the repository interfaces, except [insertUnchecked] for
 * data the repositories themselves refuse to write.
 */
internal abstract class RepositoryContract {

    /** The repositories under test, on empty data. */
    protected class Repositories(
        val cinemas: CinemaRepository,
        val halls: HallRepository,
        val visits: VisitRepository,
        val overrides: SeatOverrideRepository,
        val tickets: TicketRepository,
    )

    /** Creates the repositories for one test. */
    protected abstract fun createRepositories(): Repositories

    /** Stores [visit] as it is (no seat check), like data from an import or an older build. */
    protected abstract suspend fun insertUnchecked(visit: Visit)

    /** Stores [override] as it is (no seat check), like data from an import or an older build. */
    protected abstract suspend fun insertUnchecked(override: SeatOverride)

    private lateinit var repositories: Repositories
    private val cinemas get() = repositories.cinemas
    private val halls get() = repositories.halls
    private val visits get() = repositories.visits
    private val overrides get() = repositories.overrides
    private val tickets get() = repositories.tickets

    @Before
    fun createTheRepositories() {
        repositories = createRepositories()
    }

    private val date = LocalDate.of(2026, 10, 13)

    /** 3 × 4 seats, ids 1..12; seat 12 is the last of row 3. */
    private val grid = SeatLayout.create(3, 4, firstSeatId = 1)

    private suspend fun hallWith(layout: SeatLayout?): Hall {
        val hallId = halls.createHall(cinemas.createCinema("Kino"), "Saal 1")
        if (layout != null) assertTrue(halls.updateHall(halls.getHall(hallId)!!.copy(layout = layout)))
        return halls.getHall(hallId)!!
    }

    private suspend fun storedOverrides(hallId: Long): List<SeatOverride> =
        halls.observeHallDetail(hallId).first()!!.overrides

    private fun assertRejected(block: suspend () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { block() } }
    }

    @Test
    fun seatIdsOfRemovedSeatsAreNeverReused() = runTest {
        val hall = hallWith(grid)
        assertEquals(13, hall.nextSeatId)

        // A new grid numbered from 1 would give old ids to other seats.
        assertRejected { halls.updateHall(hall.copy(layout = SeatLayout.create(2, 6, firstSeatId = 1))) }
        assertEquals(grid, halls.getHall(hall.id)!!.layout)

        // Seat 12 removed: the mark stays, so the seat can't come back with its old id ...
        assertTrue(halls.updateHall(hall.copy(layout = grid.withCellType(2, 3, CellType.GAP))))
        val removed = halls.getHall(hall.id)!!
        assertEquals(13, removed.nextSeatId)
        assertRejected { halls.updateHall(removed.copy(layout = grid)) }
        // ... while an edit session gives it a new one.
        val redrawn = removed.startLayoutEdit().setCellType(2, 3, CellType.SEAT).layout!!
        assertEquals(SeatId(13), redrawn.rows[2].seatIds.last())
        assertTrue(halls.updateHall(removed.copy(layout = redrawn)))
        assertEquals(14, halls.getHall(hall.id)!!.nextSeatId)
        assertEquals(14, halls.observeHallDetail(hall.id).first()!!.hall.nextSeatId)
    }

    @Test
    fun theIdMarkCoversSeatIdsThatVisitsAndOverridesUse() = runTest {
        val hall = hallWith(null)
        assertEquals(SeatLayout.FIRST_SEAT_ID, hall.nextSeatId)
        insertUnchecked(Visit(hallId = hall.id, date = date, seatId = SeatId(40)))
        insertUnchecked(SeatOverride(hall.id, SeatId(50), SeatColour.RED))

        // Every read gives the full mark ...
        assertEquals(51, halls.getHall(hall.id)!!.nextSeatId)
        assertEquals(51, halls.observeHallDetail(hall.id).first()!!.hall.nextSeatId)
        assertEquals(51, cinemas.observeCinemaDetail(hall.cinemaId).first()!!.halls.single().hall.nextSeatId)

        // ... a grid below it is refused, one from the mark is saved, and the mark moves on.
        assertRejected { halls.updateHall(halls.getHall(hall.id)!!.copy(layout = SeatLayout.create(5, 8, firstSeatId = 1))) }
        val fresh = halls.getHall(hall.id)!!.startLayoutEdit().newGrid(5, 8).layout!!
        assertTrue(fresh.seatIds.all { it.value >= 51 })
        assertTrue(halls.updateHall(halls.getHall(hall.id)!!.copy(layout = fresh)))
        assertEquals(fresh, halls.getHall(hall.id)!!.layout)
        assertEquals(91, halls.getHall(hall.id)!!.nextSeatId)
    }

    @Test
    fun overridesNeedASeatOfTheCurrentLayoutAndAHallHasOneFavourite() = runTest {
        val hall = hallWith(grid)
        assertRejected { overrides.setSeatColour(hall.id, SeatId(99), SeatColour.RED) }
        assertRejected { overrides.setFavouriteSeat(hall.id, SeatId(99)) }
        assertRejected { overrides.setFavouriteSeat(12_345, null) }

        overrides.setSeatColour(hall.id, SeatId(2), SeatColour.RED)
        overrides.setFavouriteSeat(hall.id, SeatId(2))
        overrides.setFavouriteSeat(hall.id, SeatId(3)) // moves: one favourite per hall
        assertEquals(
            listOf(SeatOverride(hall.id, SeatId(2), SeatColour.RED), SeatOverride(hall.id, SeatId(3), isFavourite = true)),
            storedOverrides(hall.id),
        )
        assertEquals(SeatId(3), halls.observeHallDetail(hall.id).first()!!.favouriteSeat)
        assertEquals(
            grid.labels().labelOf(SeatId(3)),
            cinemas.observeCinemaDetail(hall.cinemaId).first()!!.halls.single().favouriteSeat,
        )

        overrides.setFavouriteSeat(hall.id, null)
        overrides.clearSeatOverride(hall.id, SeatId(2))
        assertTrue(storedOverrides(hall.id).isEmpty())
        assertNull(halls.observeHallDetail(hall.id).first()!!.favouriteSeat)
    }

    @Test
    fun layoutEditsPruneOnlyOverridesOfRemovedSeats() = runTest {
        val hall = hallWith(grid)
        overrides.setSeatColour(hall.id, SeatId(1), SeatColour.GREEN)
        overrides.setFavouriteSeat(hall.id, SeatId(4))

        // Edits that keep the layout keep every override.
        assertTrue(halls.renameHall(hall.id, "Saal 2"))
        assertTrue(halls.updateHall(halls.getHall(hall.id)!!.copy(notes = "notes", screenWidth = 4f, screenDistance = 2f)))
        assertEquals(2, storedOverrides(hall.id).size)

        // Removing seat 4 drops its favourite, not seat 1's colour.
        assertTrue(halls.updateHall(halls.getHall(hall.id)!!.copy(layout = grid.withCellType(0, 3, CellType.GAP))))
        assertEquals(listOf(SeatOverride(hall.id, SeatId(1), SeatColour.GREEN)), storedOverrides(hall.id))

        // Clearing the layout drops them all.
        assertTrue(halls.updateHall(halls.getHall(hall.id)!!.copy(layout = null)))
        assertTrue(storedOverrides(hall.id).isEmpty())
    }

    @Test
    fun aSeatNotInTheLayoutIsClearedOnSaveUnlessItIsTheVisitsUnchangedSeat() = runTest {
        val hall = hallWith(grid)
        val unknown = visits.saveVisit(Visit(hallId = hall.id, date = date, seatId = SeatId(99)))
        assertTrue(unknown.seatCleared)
        assertNull(visits.getVisit(unknown.visitId)!!.seatId)

        val saved = visits.saveVisit(Visit(hallId = hall.id, date = date, seatId = SeatId(12)))
        assertFalse(saved.seatCleared)
        // Seat 12 is removed from the layout: saving that visit again keeps its seat ...
        assertTrue(halls.updateHall(halls.getHall(hall.id)!!.copy(layout = grid.withCellType(2, 3, CellType.GAP))))
        val visit = visits.getVisit(saved.visitId)!!
        assertFalse(visits.saveVisit(visit.copy(filmTitle = "Film")).seatCleared)
        assertEquals(SeatId(12), visits.getVisit(saved.visitId)!!.seatId)
        // ... but another visit can't pick it.
        assertTrue(visits.saveVisit(Visit(hallId = hall.id, date = date, seatId = SeatId(12))).seatCleared)
    }

    @Test
    fun deletingAUserCinemaOrHallTakesItsHallsAndVisitsWithIt() = runTest {
        val hall = hallWith(grid)
        val other = hallWith(grid) // in a cinema of its own
        val visit = visits.saveVisit(Visit(hallId = hall.id, date = date, seatId = SeatId(1))).visitId
        val kept = visits.saveVisit(Visit(hallId = other.id, date = date, seatId = SeatId(1))).visitId
        // Seat colours and favourites don't keep a cinema or hall from being deleted.
        overrides.setFavouriteSeat(hall.id, SeatId(1))
        overrides.setSeatColour(other.id, SeatId(1), SeatColour.RED)

        assertEquals(DeleteResult.DELETED, cinemas.deleteCinema(hall.cinemaId))
        assertNull(cinemas.observeCinemaDetail(hall.cinemaId).first())
        assertNull(halls.getHall(hall.id))
        assertNull(visits.getVisit(visit))
        assertEquals(listOf(kept), visits.observeAllVisits().first().map { it.visit.id })
        assertEquals(DeleteResult.NOT_FOUND, cinemas.deleteCinema(hall.cinemaId))

        assertEquals(DeleteResult.DELETED, halls.deleteHall(other.id))
        assertNull(visits.getVisit(kept))
        assertTrue(visits.observeAllVisits().first().isEmpty())
        assertTrue(cinemas.observeCinemaDetail(other.cinemaId).first()!!.halls.isEmpty())
        assertEquals(DeleteResult.NOT_FOUND, halls.deleteHall(other.id))
    }

    @Test
    fun favouriteCinemasAreStoredReadEverywhereAndKeptByEdits() = runTest {
        val first = cinemas.createCinema("Kino 10")
        val second = cinemas.createCinema("Kino 2")
        assertFalse(cinemas.observeCinemaDetail(first).first()!!.cinema.isFavourite)

        assertTrue(cinemas.setFavourite(first, true))
        assertTrue(cinemas.setFavourite(second, true)) // any number of favourites
        assertTrue(cinemas.setFavourite(second, true)) // again: nothing changes
        // In natural name order: "Kino 2" before "Kino 10".
        assertEquals(listOf(second, first), cinemas.observeCinemas().first().filter { it.cinema.isFavourite }.map { it.cinema.id })
        assertEquals(listOf(second, first), cinemas.observeCinemaDetails().first().filter { it.cinema.isFavourite }.map { it.cinema.id })

        assertTrue(cinemas.updateCinema(first, "Kino 1", "Street", null))
        assertTrue(cinemas.renameCinema(second, "Kino 3"))
        assertTrue(cinemas.observeCinemaDetail(first).first()!!.cinema.isFavourite)
        assertTrue(cinemas.observeCinemaDetail(second).first()!!.cinema.isFavourite)

        assertTrue(cinemas.setFavourite(first, false))
        assertFalse(cinemas.observeCinemaDetail(first).first()!!.cinema.isFavourite)
        assertFalse(cinemas.setFavourite(999_999, true))
    }

    // ---- Tickets (spec §4.7). Invented booking numbers and seats. ----

    private val booking = BookingNumber("TEST123")
    private val evening = LocalTime.of(20, 15)

    /** An invented module pattern, not a real QR code. */
    private val qr = QrModules.of(21) { row, column -> (row + 2 * column) % 3 == 0 }

    private fun ticket(
        visitId: Long = 0,
        number: BookingNumber = booking,
        seats: List<TicketSeat> = listOf(TicketSeat("1", "2")),
        qrModules: QrModules? = qr,
    ) = Ticket(visitId, number, seats, qrModules)

    private suspend fun saved(visit: Visit, ticket: Ticket = ticket(visit.id)): Long =
        (tickets.saveVisitWithTicket(visit, ticket) as SaveTicketResult.Saved).visitId

    @Test
    fun aBookingNumberBelongsToOneVisitOnly() = runTest {
        val hall = hallWith(grid)
        val first = saved(Visit(hallId = hall.id, date = date, filmTitle = "The Quiet Projectionist", startTime = evening))
        assertEquals(first, tickets.findVisitId(booking))
        assertNull(tickets.findVisitId(BookingNumber("OTHER42")))

        // A new visit with that booking number: refused, nothing is saved.
        val refused = tickets.saveVisitWithTicket(Visit(hallId = hall.id, date = date, filmTitle = "Copy"), ticket())
        assertEquals(SaveTicketResult.BookingNumberInUse(first), refused)
        assertEquals(listOf(first), visits.observeAllVisits().first().map { it.visit.id })

        // An existing visit with another visit's booking number: refused, the visit is left as it was.
        val other = visits.save(Visit(hallId = hall.id, date = date, filmTitle = "Other"))
        val changed = visits.getVisit(other)!!.copy(filmTitle = "Changed")
        assertEquals(SaveTicketResult.BookingNumberInUse(first), tickets.saveVisitWithTicket(changed, ticket(other)))
        assertEquals("Other", visits.getVisit(other)!!.filmTitle)
        assertNull(tickets.getTicket(other))
        assertEquals(booking, tickets.getTicket(first)!!.bookingNumber)

        // Another booking number is fine.
        assertEquals(other, saved(changed, ticket(other, BookingNumber("OTHER42"))))
        assertEquals(other, tickets.findVisitId(BookingNumber("OTHER42")))
        assertEquals("Changed", visits.getVisit(other)!!.filmTitle)
    }

    @Test
    fun savingAVisitAgainWithItsOwnBookingNumberReplacesItsTicket() = runTest {
        val hall = hallWith(grid)
        val id = saved(Visit(hallId = hall.id, date = date, seatId = SeatId(2), startTime = evening))
        assertEquals(ticket(id), tickets.getTicket(id))
        assertEquals(ticket(id), tickets.observeTicket(id).first())

        val visit = visits.getVisit(id)!!
        assertEquals(evening, visit.startTime)
        val twoSeats = ticket(id, seats = listOf(TicketSeat("1", "2"), TicketSeat("1", "3")), qrModules = null)
        assertEquals(
            SaveTicketResult.Saved(id, seatCleared = false),
            tickets.saveVisitWithTicket(visit.copy(filmTitle = "The Quiet Projectionist"), twoSeats),
        )
        assertEquals(twoSeats, tickets.getTicket(id)) // replaced, also the QR modules
        assertEquals("The Quiet Projectionist", visits.getVisit(id)!!.filmTitle)

        // A plain save keeps the ticket and the start time.
        assertFalse(visits.saveVisit(visits.getVisit(id)!!.copy(note = "Front row")).seatCleared)
        assertEquals(twoSeats, tickets.getTicket(id))
        assertEquals(evening, visits.getVisit(id)!!.startTime)

        // The visit's save rules apply: a seat not in the layout is cleared, the ticket is saved.
        val other = tickets.saveVisitWithTicket(Visit(hallId = hall.id, date = date, seatId = SeatId(99)), ticket(number = BookingNumber("OTHER42")))
        other as SaveTicketResult.Saved
        assertTrue(other.seatCleared)
        assertNull(visits.getVisit(other.visitId)!!.seatId)
        assertEquals(other.visitId, tickets.getTicket(other.visitId)!!.visitId)

        // The ticket must be the visit's (0 for a new one); an unknown visit or hall is refused, nothing is saved.
        assertRejected { tickets.saveVisitWithTicket(visits.getVisit(id)!!, ticket(visitId = 0)) }
        assertRejected { tickets.saveVisitWithTicket(Visit(id = 777_777, hallId = hall.id, date = date), ticket(777_777, BookingNumber("NEW1"))) }
        assertRejected { tickets.saveVisitWithTicket(Visit(hallId = 888_888, date = date), ticket(number = BookingNumber("NEW2"))) }
        assertNull(tickets.findVisitId(BookingNumber("NEW1")))
        assertNull(tickets.findVisitId(BookingNumber("NEW2")))
    }

    @Test
    fun deletingAVisitHallOrCinemaDeletesItsTicket() = runTest {
        val hall = hallWith(grid)
        val visit = saved(Visit(hallId = hall.id, date = date))
        assertTrue(visits.deleteVisit(visit))
        assertNull(tickets.getTicket(visit))
        assertNull(tickets.findVisitId(booking))
        // The booking number is free again.
        val again = saved(Visit(hallId = hall.id, date = date))
        assertEquals(again, tickets.findVisitId(booking))

        assertEquals(DeleteResult.DELETED, halls.deleteHall(hall.id))
        assertNull(tickets.getTicket(again))
        assertNull(tickets.findVisitId(booking))

        val other = hallWith(grid)
        val third = saved(Visit(hallId = other.id, date = date))
        assertEquals(DeleteResult.DELETED, cinemas.deleteCinema(other.cinemaId))
        assertNull(tickets.getTicket(third))
        assertNull(tickets.findVisitId(booking))
    }

    @Test
    fun aTicketCanBeRemovedAndTheVisitListSaysWhichVisitsHaveOne() = runTest {
        val hall = hallWith(grid)
        val withTicket = saved(Visit(hallId = hall.id, date = date, startTime = evening))
        val without = visits.save(Visit(hallId = hall.id, date = date.minusDays(1)))
        val items = visits.observeAllVisits().first().associateBy { it.visit.id }
        assertTrue(items.getValue(withTicket).hasTicket)
        assertEquals(evening, items.getValue(withTicket).startTime)
        assertFalse(items.getValue(without).hasTicket)
        assertNull(items.getValue(without).startTime)
        assertEquals(evening, visits.observeVisit(withTicket).first()!!.startTime)

        assertTrue(tickets.removeTicket(withTicket))
        assertFalse(tickets.removeTicket(withTicket))
        assertFalse(tickets.removeTicket(without))
        assertNull(tickets.observeTicket(withTicket).first())
        assertNull(tickets.findVisitId(booking))
        assertFalse(visits.observeAllVisits().first().single { it.visit.id == withTicket }.hasTicket)
        assertEquals(evening, visits.getVisit(withTicket)!!.startTime) // the visit stays
    }

    @Test
    fun qrModulesAreStoredAgainAndExpireSevenDaysAfterTheShowing() = runTest {
        val hall = hallWith(grid)
        // 13 Oct 2026 20:15 Berlin (summer time) = 18:15 UTC; expires 20 Oct 18:15 UTC.
        val timed = saved(Visit(hallId = hall.id, date = date, startTime = evening))
        // Without a start time: 7 days after the end of 14 Oct, i.e. 22 Oct 00:00 Berlin = 21 Oct 22:00 UTC.
        val untimed = saved(Visit(hallId = hall.id, date = date.plusDays(1)), ticket(number = BookingNumber("OTHER42")))
        val noModules = saved(Visit(hallId = hall.id, date = date.minusDays(30)), ticket(number = BookingNumber("NOQR1"), qrModules = null))

        assertEquals(0, tickets.removeExpiredQrModules(Instant.parse("2026-10-20T18:14:59Z")))
        assertEquals(qr, tickets.getTicket(timed)!!.qrModules)
        assertEquals(1, tickets.removeExpiredQrModules(Instant.parse("2026-10-20T18:15:00Z")))
        assertNull(tickets.getTicket(timed)!!.qrModules)
        assertEquals(qr, tickets.getTicket(untimed)!!.qrModules)
        assertEquals(0, tickets.removeExpiredQrModules(Instant.parse("2026-10-21T21:59:59Z")))
        assertEquals(1, tickets.removeExpiredQrModules(Instant.parse("2026-10-21T22:00:00Z")))
        assertEquals(0, tickets.removeExpiredQrModules(Instant.parse("2027-01-01T00:00:00Z")))
        // The tickets stay, only their QR modules go.
        assertEquals(listOf(booking, BookingNumber("OTHER42"), BookingNumber("NOQR1")), listOf(timed, untimed, noModules).map { tickets.getTicket(it)!!.bookingNumber })
        assertTrue(listOf(timed, untimed, noModules).all { tickets.getTicket(it)!!.qrModules == null })

        // Imported again: the modules are stored again, and expire again.
        assertTrue(tickets.storeQrModules(timed, qr))
        assertEquals(ticket(timed), tickets.getTicket(timed))
        assertEquals(qr, tickets.observeTicket(timed).first()!!.qrModules)
        assertFalse(tickets.storeQrModules(visits.save(Visit(hallId = hall.id, date = date)), qr)) // a visit without a ticket
        assertFalse(tickets.storeQrModules(999_999, qr))
        assertEquals(1, tickets.removeExpiredQrModules(Instant.parse("2026-10-20T18:15:00Z")))
        assertNull(tickets.getTicket(timed)!!.qrModules)
    }
}
