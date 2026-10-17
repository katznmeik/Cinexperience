package io.github.katznmeik.cinexperience.core.ticket.pdf

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.testing.PdfBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InflateTest {

    private fun error(block: () -> Unit): TicketPdfError? = try {
        block()
        null
    } catch (e: TicketPdfException) {
        e.error
    }

    @Test
    fun inflatesUpToTheCapAndIgnoresTrailingBytes() {
        val data = ByteArray(100_000) { (it % 7).toByte() }
        val packed = PdfBuilder.deflate(data) + byteArrayOf(0x0A, 0x0D)

        assertArrayEquals(data, Inflate.inflate(packed, 0, packed.size, maxOutput = data.size))
    }

    @Test
    fun oneByteMoreThanTheCapIsTooLarge() {
        val packed = PdfBuilder.deflate(ByteArray(100_001))

        assertEquals(TicketPdfError.STREAM_TOO_LARGE, error { Inflate.inflate(packed, 0, packed.size, maxOutput = 100_000) })
    }

    @Test
    fun cutOffOrCorruptDataIsDamaged() {
        val packed = PdfBuilder.deflate(ByteArray(10_000) { (it * 31).toByte() })

        assertEquals(TicketPdfError.DAMAGED, error { Inflate.inflate(packed, 0, packed.size / 2, maxOutput = 1_000_000) })
        assertEquals(TicketPdfError.DAMAGED, error { Inflate.inflate(packed, 0, 0, maxOutput = 1_000_000) })
        assertEquals(TicketPdfError.DAMAGED, error { Inflate.inflate(ByteArray(64) { 0x55 }, 0, 64, maxOutput = 1_000_000) })
    }
}
