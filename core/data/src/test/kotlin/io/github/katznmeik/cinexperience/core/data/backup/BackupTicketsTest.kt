package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.repository.RoomTicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomVisitRepository
import io.github.katznmeik.cinexperience.core.data.repository.SaveTicketResult
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.TextLimits
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Start times and tickets in backups (spec §8, version 3): booking numbers and
 * seats go through export and import, the QR modules never do (importing the
 * ticket PDF again brings them back), and the validator refuses tickets and
 * start times the app couldn't have written. Test data is invented.
 */
internal class BackupTicketsTest : BackupTestBase() {

    // Exports and imports are compared row by row: no seeding in between.
    override val seedAfterImport: Boolean = false

    @Test
    fun startTimesAndTicketsRoundTripWithoutTheirQrModules() = runTest {
        buildRealisticData()
        val visitA = tickets.findVisitId(BookingNumber("TEST123"))!!
        val visitB = tickets.findVisitId(BookingNumber("TEST456"))!!
        val qr = tickets.getTicket(visitA)!!.qrModules!!
        val doc = deviceA.document("backup.zip")
        deviceA.repository.export(doc)

        // data.json holds the start time, booking numbers and seats, but nothing of the QR code.
        val dataJson = readZip(File(doc)).single { it.first == BackupFormat.DATA }.second.decodeToString()
        val data = BackupFormat.strictJson.decodeFromString(BackupData.serializer(), dataJson)
        assertEquals("17:45", data.visits.single { it.id == visitA }.startTime)
        assertNull(data.visits.single { it.id == visitB }.startTime)
        assertEquals(
            listOf(
                BackupTicket(visitA, "TEST123", listOf(BackupTicketSeat("2", "4"))),
                BackupTicket(visitB, "TEST456", listOf(BackupTicketSeat(null, "12"), BackupTicketSeat("R1", "R"))),
            ),
            data.tickets,
        )
        assertFalse(dataJson.contains(qr.encode().substringAfter(':')))
        assertFalse(dataJson.contains("qr", ignoreCase = true))

        val deviceB = newDevice("b")
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        assertEquals(deviceA.snapshot(), deviceB.snapshot())
        val ticketsB = RoomTicketRepository(deviceB.db, Dispatchers.Default)
        assertEquals(Ticket(visitA, BookingNumber("TEST123"), listOf(TicketSeat("2", "4"))), ticketsB.getTicket(visitA))
        assertEquals(
            Ticket(visitB, BookingNumber("TEST456"), listOf(TicketSeat(null, "12"), TicketSeat("R1", "R"))),
            ticketsB.getTicket(visitB),
        )
        assertEquals(LocalTime.of(17, 45), RoomVisitRepository(deviceB.db, Dispatchers.Default).getVisit(visitA)!!.startTime)

        // Imported again, the ticket finds its visit by the booking number and gets its QR code back.
        assertEquals(visitA, ticketsB.findVisitId(BookingNumber("TEST123")))
        assertTrue(ticketsB.restoreQrModules(visitA, BookingNumber("TEST123"), qr))
        assertEquals(qr, ticketsB.getTicket(visitA)!!.qrModules)
    }

    @Test
    fun aTicketWithoutSeatsRoundTrips() = runTest {
        buildRealisticData()
        val hallId = visits.getVisit(tickets.findVisitId(BookingNumber("TEST123"))!!)!!.hallId
        // The seats couldn't be read from this (invented) ticket.
        val saved = tickets.saveVisitWithTicket(Visit(hallId = hallId, date = LocalDate.of(2026, 10, 24)), Ticket(0, BookingNumber("TEST789"), emptyList()))
        val visitId = (saved as SaveTicketResult.Saved).visitId
        val doc = deviceA.document("backup.zip")
        deviceA.repository.export(doc)

        val dataJson = readZip(File(doc)).single { it.first == BackupFormat.DATA }.second.decodeToString()
        val data = BackupFormat.strictJson.decodeFromString(BackupData.serializer(), dataJson)
        assertEquals(BackupTicket(visitId, "TEST789", emptyList()), data.tickets.single { it.visitId == visitId })

        val deviceB = newDevice("b")
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        assertEquals(deviceA.snapshot(), deviceB.snapshot())
        assertEquals(Ticket(visitId, BookingNumber("TEST789"), emptyList()), RoomTicketRepository(deviceB.db, Dispatchers.Default).getTicket(visitId))
    }

    @Test
    fun anImportReplacesTheCurrentTicketsAndTheirQrModules() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val ticketsB = RoomTicketRepository(deviceB.db, Dispatchers.Default)
        val visitB = RoomVisitRepository(deviceB.db, Dispatchers.Default).observeAllVisitsOnce().single()
        val own = Ticket(visitB.id, BookingNumber("OWN9"), listOf(TicketSeat("1", "1")), QrModules.of(25) { r, c -> r == c })
        ticketsB.saveVisitWithTicket(visitB, own)

        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        assertNull(ticketsB.findVisitId(BookingNumber("OWN9")))
        assertEquals(deviceA.snapshot().tickets, deviceB.snapshot().tickets)
        assertTrue(deviceB.db.backupDao().tickets().all { it.qrModules == null })
        // The safety copy holds the replaced ticket (without its QR code).
        assertEquals(listOf("OWN9"), deviceB.latestCopyData()!!.tickets.map { it.bookingNumber })
    }

    @Test
    fun aBackupWithAnInvalidTicketIsRefusedBeforeAnythingIsTouched() = runTest {
        buildRealisticData()
        val doc = File(deviceA.document("backup.zip"))
        deviceA.repository.export(doc.path)
        val entries = readZip(doc)
        val i = entries.indexOfFirst { it.first == BackupFormat.DATA }
        val data = BackupFormat.strictJson.decodeFromString(BackupData.serializer(), entries[i].second.decodeToString())
        val bad = data.copy(tickets = data.tickets + data.tickets.first().copy(bookingNumber = "TEST999"))
        entries[i] = BackupFormat.DATA to BackupFormat.writeJson.encodeToString(BackupData.serializer(), bad).toByteArray()
        val crafted = File(deviceA.document("crafted.zip")).apply { writeBytes(zipBytes(entries)) }

        val before = deviceA.snapshot()
        val qrBefore = deviceA.db.backupDao().tickets()
        try {
            deviceA.repository.prepareImport(crafted.path)
            fail("A visit with two tickets was accepted")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_DATA, e.error)
            assertTrue(e.message!!, e.message!!.contains("has two tickets"))
        }
        assertEquals(before, deviceA.snapshot())
        assertEquals(qrBefore, deviceA.db.backupDao().tickets()) // the QR modules too
    }

    // ---- The validator's rules, on a small backup ----

    private val layout = LayoutJson.encode(SeatLayout.fromPatterns("SS|SS", firstSeatId = 1))

    private fun visit(id: Long, startTime: String? = null) = BackupVisit(
        id = id, hallId = 2, date = "2026-10-24", startTime = startTime, filmTitle = "The Quiet Projectionist", seatId = 1,
        screeningFormat = null, hallRatings = BackupHallRatings(null, null, null, null, null, null), seatRating = null,
        cinemaRatings = BackupCinemaRatings(null, null, null, null), note = null, photoPath = null,
    )

    private fun ticket(visitId: Long = 3, number: String = "TEST123", seats: List<BackupTicketSeat> = listOf(BackupTicketSeat("1", "2"))) =
        BackupTicket(visitId, number, seats)

    private fun data(visits: List<BackupVisit> = listOf(visit(3), visit(4)), tickets: List<BackupTicket> = listOf(ticket())) = BackupData(
        backupVersion = BackupFormat.BACKUP_VERSION,
        cinemas = listOf(BackupCinema(1, "Kino", null, null, isPreloaded = false, seedKey = null, isFavourite = false)),
        halls = listOf(
            BackupHall(
                id = 2, cinemaId = 1, name = "Saal 1", layoutJson = layout, screenWidth = null, screenDistance = null,
                referenceImagePath = null, notes = null, isPreloaded = false, seedKey = null, nextSeatId = 5, screenOffset = 0f,
            ),
        ),
        visits = visits,
        tickets = tickets,
        seatOverrides = emptyList(),
        seedSnapshots = emptyList(),
        metadata = emptyList(),
    )

    private fun problem(data: BackupData): String {
        try {
            BackupValidator.validate(data, emptySet())
        } catch (p: ValidationProblem) {
            return p.detail
        }
        fail("Expected the data to be refused")
        error("unreachable")
    }

    @Test
    fun validTicketsAndStartTimesAreAccepted() {
        BackupValidator.validate(data(), emptySet())
        BackupValidator.validate(data(tickets = emptyList()), emptySet())
        val most = List(Ticket.MAX_SEATS) { BackupTicketSeat("R".repeat(TextLimits.MAX_ROW_LABEL_LENGTH), "${it + 1}") }
        BackupValidator.validate(
            data(
                visits = listOf(visit(3, "00:00"), visit(4, "23:59")),
                tickets = listOf(
                    ticket(3, "A1B2C3D4E5F6G7H8", most),
                    ticket(4, "ABCD", listOf(BackupTicketSeat(null, "12"), BackupTicketSeat("8", "R"), BackupTicketSeat("8", "2147483647"))),
                ),
            ),
            emptySet(),
        )
    }

    @Test
    fun startTimesMustBeHoursAndMinutes() {
        for (bad in listOf("", "8:15", "08:5", "24:00", "20:60", "20:15:00", "20.15", " 20:15", "20:15 ", "T20:15", "\u0662\u0660:15")) {
            assertEquals(bad, "visit 3 has an invalid start time.", problem(data(visits = listOf(visit(3, bad), visit(4)))))
        }
    }

    @Test
    fun ticketsMustBelongToOneVisitThatExists() {
        assertEquals("a ticket belongs to a visit that isn't there.", problem(data(tickets = listOf(ticket(visitId = 99)))))
        assertEquals("visit 3 has two tickets.", problem(data(tickets = listOf(ticket(), ticket(number = "TEST456")))))
    }

    @Test
    fun bookingNumbersMustBeValidAndUnique() {
        for (bad in listOf("", "ABC", "A".repeat(17), "test123", "TEST 123", "TEST-123", "TEST\u200B123", "T\u00C9ST123")) {
            assertEquals(bad, "the ticket of visit 3 has an invalid booking number.", problem(data(tickets = listOf(ticket(number = bad)))))
        }
        assertEquals(
            "two tickets have the same booking number.",
            problem(data(tickets = listOf(ticket(3, "TEST123"), ticket(4, "TEST123")))),
        )
    }

    @Test
    fun seatsMustBeWhatTheAppCouldHaveWritten() {
        val tooMany = List(Ticket.MAX_SEATS + 1) { BackupTicketSeat("5", "${it + 1}") }
        val badSeats = listOf(
            tooMany,
            listOf(BackupTicketSeat("1", "2"), BackupTicketSeat("1", "2")), // twice
            listOf(BackupTicketSeat("-", "2")), // a row without a name is null
            listOf(BackupTicketSeat("", "2")),
            listOf(BackupTicketSeat(" 7", "2")),
            listOf(BackupTicketSeat("R".repeat(TextLimits.MAX_ROW_LABEL_LENGTH + 1), "2")),
            listOf(BackupTicketSeat("7\u202E", "2")),
            listOf(BackupTicketSeat("7", "")),
            listOf(BackupTicketSeat("7", "ABCDE")),
            listOf(BackupTicketSeat("7", "012345")),
            listOf(BackupTicketSeat("7", "R\u200B")),
        )
        for (seats in badSeats) {
            assertEquals(seats.take(2).toString(), "the ticket of visit 3 has invalid seats.", problem(data(tickets = listOf(ticket(seats = seats)))))
        }
    }

    /** The visits of the repository, read once. */
    private suspend fun RoomVisitRepository.observeAllVisitsOnce() = observeAllVisits().first().map { it.visit }
}
