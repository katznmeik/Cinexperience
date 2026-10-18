package io.github.katznmeik.cinexperience.feature.tickets.importing

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.program.ProgramState
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.testing.buildProgram
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits
import io.github.katznmeik.cinexperience.feature.tickets.importing.ImportFixtures.content
import io.github.katznmeik.cinexperience.feature.tickets.importing.ImportFixtures.found
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ticket import (spec §6.10): reading the file within its limit, every refusal as
 * a message, "imported before" (with the QR code restored only when it should be),
 * and the hand-over to Add visit. The file source and the reader are fakes; data is invented.
 */
class TicketImportViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val uri = "content://com.example.mail/attachments/1"
    private val clock = Clock.fixed(SampleProgram.FETCHED_AT, ZoneOffset.UTC)
    private val newQr = SampleTickets.qrModules(25)
    private val programs = FakeProgramRepository()
    private val backupImporting = MutableStateFlow(false)
    private var repository = FakeCinexRepository(FakeStore())

    /** The bytes the reader was given, per call. */
    private val readBytes = mutableListOf<ByteArray>()

    private fun reader(result: TicketReadResult = TicketReadResult.Read(content(), newQr)) = TicketReader { bytes ->
        readBytes += bytes
        result
    }

    private fun source(bytes: ByteArray = PDF) = TicketFileSource { ByteArrayInputStream(bytes) }

    private fun viewModel(
        source: TicketFileSource = source(),
        reader: TicketReader = reader(),
        tickets: TicketRepository = repository,
        uri: String? = this.uri,
    ) = TicketImportViewModel(
        uri, source, reader, repository, repository, tickets, programs, clock, backupImporting,
        ioDispatcher = mainDispatcherRule.dispatcher,
        readDispatcher = mainDispatcherRule.dispatcher,
    )

    private fun failure(vm: TicketImportViewModel): TicketImportError? = (vm.state.value as? TicketImportState.Failed)?.error

    // ---- Reading the file ----

    @Test
    fun withoutAFileTheImportSaysItCantOpenIt() {
        assertEquals(TicketImportError.OPEN_FAILED, failure(viewModel(uri = null)))
        assertTrue(readBytes.isEmpty())
    }

    @Test
    fun everyFailureOfTheSourceIsAMessage() {
        val failures = listOf<() -> Nothing>(
            { throw SecurityException("no permission") },
            { throw IOException("gone") },
            { throw OutOfMemoryError("big") },
            { throw IllegalStateException("provider crashed") },
        )
        for (fail in failures) {
            assertEquals(TicketImportError.OPEN_FAILED, failure(viewModel(source = TicketFileSource { fail() })))
        }
        val breaksWhileReading = TicketFileSource {
            object : InputStream() {
                override fun read(): Int = throw IOException("connection lost")
            }
        }
        assertEquals(TicketImportError.OPEN_FAILED, failure(viewModel(source = breaksWhileReading)))
        assertTrue("nothing reached the reader", readBytes.isEmpty())
    }

    @Test
    fun aFileOfTheLimitIsReadWhole() {
        val bytes = ByteArray(TicketPdfLimits.MAX_FILE_BYTES) { (it % 251).toByte() }
        viewModel(source = source(bytes))
        assertArrayEquals(bytes, readBytes.single())
    }

    @Test
    fun aBiggerFileIsRefusedAfterTheLimitWithoutReadingOn() {
        val stream = EndlessStream()
        val vm = viewModel(source = TicketFileSource { stream })
        assertEquals(TicketImportError.TOO_LARGE, failure(vm))
        assertEquals("read up to the limit + 1 byte, then stopped", TicketPdfLimits.MAX_FILE_BYTES + 1L, stream.read.toLong())
        assertTrue("closed", stream.closed)
        assertTrue(readBytes.isEmpty())
    }

    @Test
    fun theStreamIsClosedAfterReading() {
        val stream = ClosingStream(PDF)
        viewModel(source = TicketFileSource { stream })
        assertTrue(stream.closed)
    }

    // ---- Reading the ticket ----

    @Test
    fun aFileTheReaderRefusesIsNotATicketOrTooLarge() {
        for (error in TicketPdfError.entries) {
            val expected = if (error == TicketPdfError.TOO_LARGE) TicketImportError.TOO_LARGE else TicketImportError.NOT_A_TICKET
            assertEquals(error.name, expected, failure(viewModel(reader = reader(TicketReadResult.Refused(error)))))
        }
    }

    @Test
    fun aReaderThatFailsAnywayIsAMessageNotACrash() {
        assertEquals(TicketImportError.NOT_A_TICKET, failure(viewModel(reader = TicketReader { throw IllegalStateException("bug") })))
        assertEquals(TicketImportError.TOO_LARGE, failure(viewModel(reader = TicketReader { throw OutOfMemoryError() })))
    }

    @Test
    fun aTicketWithoutAValidBookingNumberIsNotImported() {
        val withoutNumber = viewModel(reader = reader(TicketReadResult.Read(content(bookingNumber = null), newQr)))
        assertEquals(TicketImportError.NOT_A_TICKET, failure(withoutNumber))
        val invalid = content().copy(bookingNumber = found("test 123"))
        assertEquals(TicketImportError.NOT_A_TICKET, failure(viewModel(reader = reader(TicketReadResult.Read(invalid, newQr)))))
        assertEquals("nothing stored", FakeStore(), repository.store.value)
    }

    // ---- A new ticket ----

    @Test
    fun aNewTicketOpensAddVisitWithTheMatch() {
        repository = FakeCinexRepository(SampleTickets.store(tickets = emptyList(), visits = emptyList()))
        val before = repository.store.value
        val state = viewModel().state.value as TicketImportState.OpenNewVisit
        assertEquals(SampleTickets.HALL, state.match.hallId)
        assertEquals(SampleTickets.row7Seat10, state.match.prefill.seatId)
        assertEquals(BookingNumber("TEST123"), state.match.prefill.ticket?.bookingNumber)
        assertEquals(newQr, state.match.prefill.ticket?.qrModules)
        assertEquals("nothing stored before Save", before, repository.store.value)
    }

    @Test
    fun theFormatComesFromTheProgramOnThePhoneWithoutADownload() {
        repository = FakeCinexRepository(SampleTickets.store(tickets = emptyList(), visits = emptyList()))
        programs.state.value = ProgramState(
            isLoaded = true,
            program = buildProgram { film(SampleTickets.FILM) { showing("2026-10-17T20:15", "delphi LUX", ShowingVersion.OMU) } },
        )
        val state = viewModel().state.value as TicketImportState.OpenNewVisit
        assertEquals(ScreeningFormat.OMU, state.match.prefill.format)
        assertEquals("never downloads", 0, programs.downloads)
        assertEquals(emptyList<String>(), programs.refreshCalls)
    }

    @Test
    fun aProgramStillBeingReadIsWaitedForOnlyBriefly() {
        repository = FakeCinexRepository(SampleTickets.store(tickets = emptyList(), visits = emptyList()))
        programs.state.value = ProgramState(isLoaded = false)
        val vm = viewModel()
        assertEquals(TicketImportState.Reading, vm.state.value)
        mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(TicketImportViewModel.PROGRAM_WAIT_MS + 1)
        val state = vm.state.value as TicketImportState.OpenNewVisit
        assertNull(state.match.prefill.format)
        assertEquals(0, programs.downloads)
    }

    @Test
    fun aDatabaseFailureIsAMessage() {
        val failing = object : TicketRepository by repository {
            override suspend fun findVisitId(bookingNumber: BookingNumber): Long? = throw IllegalStateException("disk")
        }
        assertEquals(TicketImportError.CHECK_FAILED, failure(viewModel(tickets = failing)))
    }

    // ---- Imported before ----

    private fun importedBefore(
        storedQr: QrModules?,
        daysAhead: Long = 2,
        newQr: QrModules? = this.newQr,
        tickets: ((TicketRepository) -> TicketRepository)? = null,
    ): TicketImportViewModel {
        repository = FakeCinexRepository(SampleTickets.store(daysAhead = daysAhead, tickets = listOf(SampleTickets.ticket(qr = storedQr))))
        return viewModel(reader = reader(TicketReadResult.Read(content(), newQr)), tickets = tickets?.invoke(repository) ?: repository)
    }

    private val storedQr: QrModules? get() = repository.store.value.tickets.single().qrModules

    @Test
    fun aTicketImportedBeforeOpensItsVisit() {
        val vm = importedBefore(storedQr = SampleTickets.qrModules())
        assertEquals(TicketImportState.OpenImportedVisit(SampleTickets.VISIT), vm.state.value)
        assertEquals("nothing else is stored", 1, repository.store.value.tickets.size)
    }

    @Test
    fun aRemovedQrCodeIsStoredAgain() {
        val vm = importedBefore(storedQr = null)
        assertEquals(TicketImportState.OpenImportedVisit(SampleTickets.VISIT), vm.state.value)
        assertEquals(newQr, storedQr)
    }

    @Test
    fun aQrCodeOfAShowingMoreThanSevenDaysAgoIsNotStoredAgain() {
        // The stored visit's date counts, not the PDF's (which says 17 Oct, still ahead).
        importedBefore(storedQr = null, daysAhead = -8)
        assertNull(storedQr)
    }

    @Test
    fun aStoredQrCodeIsNeverReplaced() {
        importedBefore(storedQr = SampleTickets.qrModules())
        assertEquals(SampleTickets.qrModules(), storedQr)
    }

    @Test
    fun withoutANewQrCodeNothingIsStored() {
        importedBefore(storedQr = null, newQr = null)
        assertNull(storedQr)
    }

    @Test
    fun aFailedRestoreOpensTheVisitAndSaysSo() {
        val vm = importedBefore(storedQr = null) { tickets ->
            object : TicketRepository by tickets {
                override suspend fun restoreQrModules(visitId: Long, bookingNumber: BookingNumber, qrModules: QrModules): Boolean =
                    throw IllegalStateException("disk")
            }
        }
        assertEquals(TicketImportState.OpenImportedVisit(SampleTickets.VISIT, qrCodeNotStored = true), vm.state.value)
        assertNull(storedQr)
    }

    @Test
    fun nothingIsStoredWhileABackupImportReplacesTheData() {
        backupImporting.value = true
        val vm = importedBefore(storedQr = null)
        assertEquals(TicketImportState.OpenImportedVisit(SampleTickets.VISIT, qrCodeNotStored = true), vm.state.value)
        assertNull(storedQr)
    }

    @Test
    fun theRestoreAsksForTheSameTicket() {
        val asked = mutableListOf<BookingNumber>()
        importedBefore(storedQr = null) { tickets ->
            object : TicketRepository by tickets {
                override suspend fun restoreQrModules(visitId: Long, bookingNumber: BookingNumber, qrModules: QrModules): Boolean =
                    tickets.restoreQrModules(visitId, bookingNumber, qrModules).also { asked += bookingNumber }
            }
        }
        assertEquals(listOf(BookingNumber("TEST123")), asked)
        assertEquals(newQr, storedQr)
    }

    // ---- Leaving ----

    @Test
    fun leavingWhileTheFileIsOpenedIgnoresTheLateResult() {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = ClosingStream(PDF)
        val slow = TicketFileSource {
            opening.countDown()
            release.await(5, TimeUnit.SECONDS)
            stream
        }
        val store = ViewModelStore()
        val calls = AtomicInteger()
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory {
                initializer {
                    TicketImportViewModel(
                        uri, slow, TicketReader { calls.incrementAndGet(); TicketReadResult.Refused(TicketPdfError.DAMAGED) },
                        repository, repository, repository, programs, clock, backupImporting,
                        ioDispatcher = Dispatchers.IO,
                        readDispatcher = mainDispatcherRule.dispatcher,
                    )
                }
            },
        )[TicketImportViewModel::class.java]
        assertTrue(opening.await(5, TimeUnit.SECONDS))
        store.clear() // Back: the screen and its ViewModel are gone
        release.countDown()
        assertTrue("the stream opened late is closed", stream.awaitClosed())
        assertEquals(TicketImportState.Reading, vm.state.value)
        assertEquals("never read", 0, calls.get())
    }

    private companion object {
        val PDF: ByteArray = "%PDF-1.4 invented".toByteArray()
    }

    /** Endless bytes, counting how many were read. */
    private class EndlessStream : InputStream() {
        var read = 0
        var closed = false

        override fun read(): Int {
            read++
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            read += len
            return len
        }

        override fun close() {
            closed = true
        }
    }

    private class ClosingStream(bytes: ByteArray) : InputStream() {
        private val input = ByteArrayInputStream(bytes)
        private val closedLatch = CountDownLatch(1)

        val closed: Boolean get() = closedLatch.count == 0L

        fun awaitClosed(): Boolean = closedLatch.await(5, TimeUnit.SECONDS)

        override fun read(): Int = input.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)

        override fun close() {
            closedLatch.countDown()
        }
    }
}
