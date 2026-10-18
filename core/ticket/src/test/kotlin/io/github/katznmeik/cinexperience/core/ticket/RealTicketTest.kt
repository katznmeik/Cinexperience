package io.github.katznmeik.cinexperience.core.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Optional local check against a real ticket, which is never committed (spec §11.2
 * rule 5: it holds a booking number, a name and a QR code; `.gitignore` ignores
 * `tickets/` and `*.ticket.pdf`, so keep real tickets there). Skipped unless
 * `CINEX_TICKET_PDF` names the file:
 *
 * ```
 * CINEX_TICKET_PDF=/path/to/ticket.pdf ./gradlew :core:ticket:testDebugUnitTest \
 *     --tests '*RealTicketTest*' --rerun
 * ```
 *
 * It reads the file with the app's reader (Android's JPEG decoder, on Robolectric)
 * and asserts and prints only structural facts, never a value from the ticket.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RealTicketTest {

    @Test
    fun readsEveryFieldOfARealTicket() {
        val path = System.getenv("CINEX_TICKET_PDF")
        assumeTrue("CINEX_TICKET_PDF is not set", !path.isNullOrBlank())
        val file = File(path!!)
        assumeTrue("The file named by CINEX_TICKET_PDF doesn't exist", file.isFile)

        val result = TicketPdfReader().read(file.readBytes())

        // Only the kind of result, never its values.
        val content = (result as? TicketPdfResult.Read)?.content
        assertTrue("Refused: ${(result as? TicketPdfResult.Refused)?.error}", content != null)
        val fields = mapOf(
            "booking number" to content!!.bookingNumber,
            "film title" to content.filmTitle,
            "cinema" to content.cinema,
            "date" to content.date,
            "start time" to content.startTime,
            "hall" to content.hall,
            "seats" to content.seats,
            "QR code" to content.qrCode,
        )
        println(fields.entries.joinToString("\n") { (name, field) -> "$name: ${if (field is TicketPdfField.NotFound) field.reason else "found"}" })
        val missing = fields.filterValues { it !is TicketPdfField.Found }.mapValues { (it.value as TicketPdfField.NotFound).reason }
        assertEquals("Fields not found", emptyMap<String, NotFoundReason>(), missing)

        assertTrue("Booking number doesn't match the pattern", TicketPdfRules.isValidBookingNumber(content.bookingNumber.valueOrNull!!))
        assertEquals("QR code size", 21, content.qrCode.valueOrNull!!.size)
        val seats = content.seats.valueOrNull!!
        assertTrue("No seat", seats.isNotEmpty())
        println("seats on the ticket: ${seats.size}")
    }
}
