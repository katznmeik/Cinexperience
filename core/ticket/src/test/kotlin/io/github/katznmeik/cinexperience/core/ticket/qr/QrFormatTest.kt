package io.github.katznmeik.cinexperience.core.ticket.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrFormatTest {

    @Test
    fun encodesTheStandardFormatWords() {
        // ISO/IEC 18004 Table C.1, as masked in the symbol (level M = 00, L = 01, H = 10, Q = 11).
        val expected = listOf(
            0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0,
            0x77C4, 0x72F3, 0x7DAA, 0x789D, 0x662F, 0x6318, 0x6C41, 0x6976,
            0x1689, 0x13BE, 0x1CE7, 0x19D0, 0x0762, 0x0255, 0x0D0C, 0x083B,
            0x355F, 0x3068, 0x3F31, 0x3A06, 0x24B4, 0x2183, 0x2EDA, 0x2BED,
        )

        assertEquals(expected, (0 until 32).map(QrFormat::encode))
    }

    @Test
    fun theValidWordsDifferInAtLeastSevenBits() {
        val words = QrFormat.VALID_WORDS.toList()

        assertEquals(32, words.size)
        for (i in words.indices) for (j in i + 1 until words.size) {
            assertTrue(Integer.bitCount(words[i] xor words[j]) >= 7)
        }
    }

    @Test
    fun anyOtherWordIsInvalid() {
        assertTrue(QrFormat.isValid(QrFormat.MASK))
        assertFalse(QrFormat.isValid(0))
        assertFalse(QrFormat.isValid(QrFormat.MASK xor 1))
        assertEquals(32, (0 until (1 shl 15)).count(QrFormat::isValid))
    }
}
