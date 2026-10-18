package io.github.katznmeik.cinexperience.feature.tickets.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.ShowingTime
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/** Why a ticket wasn't imported; [message] is user-facing. Nothing is stored in any case. */
enum class TicketImportError(val message: String) {
    /** No file, an address the import doesn't read, or the file couldn't be opened or read. */
    OPEN_FAILED("Couldn't open the file. Share or pick the ticket PDF again."),

    /** Not a Yorck ticket the reader knows, damaged, or without a valid booking number. */
    NOT_A_TICKET("This file isn't a Yorck ticket the app can read."),

    /** More than [TicketPdfLimits.MAX_FILE_BYTES]. */
    TOO_LARGE("This file is too large to be a Yorck ticket."),

    /** The visits couldn't be checked for this ticket (a database failure). */
    CHECK_FAILED("Couldn't check your visits for this ticket. Please try again."),
}

/** What the import screen shows, and where it goes next. */
sealed interface TicketImportState {
    /** "Reading ticket…": the file is read and matched. */
    data object Reading : TicketImportState

    data class Failed(val error: TicketImportError) : TicketImportState

    /** A new ticket: open Add visit with [match], replacing the import screen. */
    data class OpenNewVisit(val match: TicketMatch) : TicketImportState

    /**
     * Imported before (spec §6.10): open visit [visitId], replacing the import screen,
     * with "This ticket was imported before".
     */
    data class OpenImportedVisit(val visitId: Long) : TicketImportState
}

/**
 * Imports the ticket PDF at [uri] (spec §6.10, §11.2): reads the file through
 * [fileSource] off the main thread (at most [TicketPdfLimits.MAX_FILE_BYTES]; a
 * bigger file is not read on), reads it with [reader] on [readDispatcher], and
 * decides where it goes. The bytes live only in memory while they are read, and
 * nothing is stored here, except the QR code of a ticket imported before.
 *
 * - No valid booking number: not imported ([TicketImportError.NOT_A_TICKET]); it
 *   identifies the ticket and is what the QR code holds. Every other field may be missing.
 * - Imported before ([TicketRepository.findVisitId], checked first): that visit opens.
 *   If its ticket has no QR code (e.g. restored from a backup), the new one is valid
 *   and the stored visit's showing (its date and start time, not the PDF's) is less than
 *   7 days ago, the QR code is stored again ([TicketRepository.storeQrModules]).
 * - Otherwise Add visit opens with the [TicketMatching] result. The cached program is
 *   only read (`state`), never downloaded.
 *
 * Every failure is a [TicketImportState.Failed] message, never a crash. Leaving the
 * screen (Back) cancels the import; a read that finishes later is ignored.
 */
class TicketImportViewModel(
    private val uri: String?,
    private val fileSource: TicketFileSource,
    private val reader: TicketReader,
    private val cinemaRepository: CinemaRepository,
    private val visitRepository: VisitRepository,
    private val ticketRepository: TicketRepository,
    private val programRepository: ProgramRepository,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<TicketImportState>(TicketImportState.Reading)
    val state: StateFlow<TicketImportState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val result = import()
            // A stream closed by Back can end the read with an error: that late result is ignored.
            ensureActive()
            _state.value = result
        }
    }

    private suspend fun import(): TicketImportState {
        val bytes = when (val file = readFile()) {
            FileRead.Unreadable -> return TicketImportState.Failed(TicketImportError.OPEN_FAILED)
            FileRead.TooLarge -> return TicketImportState.Failed(TicketImportError.TOO_LARGE)
            is FileRead.Bytes -> file.bytes
        }
        val ticket = when (val result = parse(bytes)) {
            is TicketReadResult.Refused -> return TicketImportState.Failed(
                if (result.error == TicketPdfError.TOO_LARGE) TicketImportError.TOO_LARGE else TicketImportError.NOT_A_TICKET,
            )
            is TicketReadResult.Read -> result
        }
        val bookingNumber = ticket.content.bookingNumber.valueOrNull?.let(BookingNumber::ofOrNull)
            ?: return TicketImportState.Failed(TicketImportError.NOT_A_TICKET)
        return try {
            val visitId = ticketRepository.findVisitId(bookingNumber)
            if (visitId != null) {
                ticket.qrModules?.let { restoreQrCode(visitId, it) }
                TicketImportState.OpenImportedVisit(visitId)
            } else {
                val cinemas = cinemaRepository.observeCinemaDetails().first()
                TicketImportState.OpenNewVisit(TicketMatching.match(ticket.content, bookingNumber, ticket.qrModules, cinemas, cachedProgram()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TicketImportState.Failed(TicketImportError.CHECK_FAILED)
        }
    }

    private sealed interface FileRead {
        data class Bytes(val bytes: ByteArray) : FileRead
        data object TooLarge : FileRead
        data object Unreadable : FileRead
    }

    /** The file's bytes, without throwing; a stream left blocked by a cancelled import is closed. */
    private suspend fun readFile(): FileRead {
        val address = uri ?: return FileRead.Unreadable
        return try {
            coroutineScope {
                // A stream opened after Back is dropped by withContext: it is closed here.
                val opened = AtomicReference<InputStream?>()
                val stream = try {
                    withContext(ioDispatcher) { fileSource.open(address).also(opened::set) }
                } catch (e: CancellationException) {
                    opened.get()?.closeQuietly()
                    throw e
                }
                // Back while a slow provider blocks the read: closing the stream lets it end.
                val closer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable + ioDispatcher) { stream.closeQuietly() }
                    }
                }
                try {
                    withContext(ioDispatcher) {
                        stream.use { TicketFileBytes.readAtMost(it, TicketPdfLimits.MAX_FILE_BYTES) }
                    }?.let(FileRead::Bytes) ?: FileRead.TooLarge
                } finally {
                    closer.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            FileRead.Unreadable
        } catch (e: IOException) {
            FileRead.Unreadable
        } catch (e: OutOfMemoryError) {
            FileRead.Unreadable
        } catch (e: Exception) {
            FileRead.Unreadable
        }
    }

    /** The reader never throws for any content; anything unforeseen is "not a ticket", never a crash. */
    private suspend fun parse(bytes: ByteArray): TicketReadResult = try {
        withContext(readDispatcher) { reader.read(bytes) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        TicketReadResult.Refused(TicketPdfError.TOO_LARGE)
    } catch (e: Exception) {
        TicketReadResult.Refused(TicketPdfError.DAMAGED)
    }

    /**
     * Stores the new [qrModules] for visit [visitId] if its ticket has none and the stored
     * visit's QR codes are still kept. A failure leaves it out: the visit opens anyway
     * and its ticket screen says that no QR code is stored.
     */
    private suspend fun restoreQrCode(visitId: Long, qrModules: QrModules) {
        try {
            val ticket = ticketRepository.getTicket(visitId) ?: return
            if (ticket.qrModules != null) return
            val visit = visitRepository.getVisit(visitId) ?: return
            if (ShowingTime.qrModulesExpired(visit.date, visit.startTime, clock.instant())) return
            ticketRepository.storeQrModules(visitId, qrModules)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left out; see above.
        }
    }

    /** The program already on the phone (never downloaded here), once the repository has read it; `null` without one. */
    private suspend fun cachedProgram(): Program? =
        withTimeoutOrNull(PROGRAM_WAIT_MS) { programRepository.state.first { it.isLoaded } }?.program

    companion object {
        /** How long the import waits for the stored program to be read at app start (usually far less). */
        const val PROGRAM_WAIT_MS: Long = 3_000

        fun factory(
            uri: String?,
            fileSource: TicketFileSource,
            reader: TicketReader,
            cinemaRepository: CinemaRepository,
            visitRepository: VisitRepository,
            ticketRepository: TicketRepository,
            programRepository: ProgramRepository,
            clock: Clock,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TicketImportViewModel(
                    uri, fileSource, reader, cinemaRepository, visitRepository, ticketRepository, programRepository, clock,
                )
            }
        }
    }
}

private fun InputStream.closeQuietly() {
    try {
        close()
    } catch (e: IOException) {
        // Closed already, or the provider failed: nothing left to do.
    } catch (e: RuntimeException) {
        // Likewise.
    }
}
