package io.github.katznmeik.cinexperience.core.model

import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The QR module grid (spec §4.7) and its text form: sizes of QR versions 1–4 only, and a strict decoder. */
class QrModulesTest {

    /** An invented pattern (not a real code): dark where row × column is odd. */
    private fun pattern(size: Int) = QrModules.of(size) { row, column -> (row * column) % 2 == 1 }

    @Test
    fun onlyTheSizesOfVersions1To4() {
        assertEquals(listOf(21, 25, 29, 33), QrModules.SIZES)
        for ((version, size) in QrModules.SIZES.withIndex()) {
            assertEquals(version + 1, pattern(size).version)
            assertEquals(size, QrModules(size, BooleanArray(size * size)).size)
        }
        for (size in listOf(0, 1, 17, 20, 22, 37, 177)) {
            assertFailsWith<IllegalArgumentException> { QrModules(size, BooleanArray(size * size)) }
            assertFailsWith<IllegalArgumentException> { QrModules.of(size) { _, _ -> false } }
        }
        assertFailsWith<IllegalArgumentException> { QrModules(21, BooleanArray(440)) }
        assertFailsWith<IllegalArgumentException> { QrModules(21, BooleanArray(442)) }
    }

    @Test
    fun modulesAreReadRowByRowAndCopied() {
        val array = BooleanArray(21 * 21).also { it[1] = true; it[21] = true }
        val modules = QrModules(21, array)
        assertTrue(modules.isDark(0, 1))
        assertTrue(modules.isDark(1, 0))
        assertFalse(modules.isDark(0, 0))
        array[0] = true // the grid keeps its own copy
        assertFalse(modules.isDark(0, 0))
        assertFailsWith<IllegalArgumentException> { modules.isDark(21, 0) }
        assertFailsWith<IllegalArgumentException> { modules.isDark(0, -1) }
    }

    @Test
    fun gridsCompareByTheirModules() {
        assertEquals(pattern(21), pattern(21))
        assertEquals(pattern(21).hashCode(), pattern(21).hashCode())
        assertNotEquals(pattern(21), pattern(25))
        assertNotEquals(pattern(21), QrModules.of(21) { row, column -> row == 0 && column == 0 || (row * column) % 2 == 1 })
        assertEquals("QrModules(21 × 21)", pattern(21).toString())
    }

    @Test
    fun theTextFormRoundTripsEverySize() {
        val random = Random(7)
        for (size in QrModules.SIZES) {
            for (modules in listOf(pattern(size), QrModules.of(size) { _, _ -> random.nextBoolean() }, QrModules.of(size) { _, _ -> true })) {
                val text = modules.encode()
                assertTrue(text.startsWith("$size:"), text)
                assertEquals(modules, QrModules.decode(text))
            }
        }
        assertEquals(3 + 76, pattern(21).encode().length) // 441 bits = 56 bytes
        assertEquals(QrModules.MAX_ENCODED_LENGTH, pattern(33).encode().length)
    }

    @Test
    fun theTextFormIsTheDocumentedOne() {
        // Only the first module (top left) dark: the highest bit of the first byte.
        val first = QrModules.of(21) { row, column -> row == 0 && column == 0 }
        assertEquals("21:" + Base64.getEncoder().encodeToString(ByteArray(56).also { it[0] = 0x80.toByte() }), first.encode())
        // Only the last module dark: module 441 is bit 441 of 448, the highest bit of the last byte (7 bits unused).
        val last = QrModules.of(21) { row, column -> row == 20 && column == 20 }
        assertEquals("21:" + Base64.getEncoder().encodeToString(ByteArray(56).also { it[55] = 0x80.toByte() }), last.encode())
        // The second module of the second row: index 22, the 7th highest bit of the third byte.
        val inner = QrModules.of(21) { row, column -> row == 1 && column == 1 }
        assertEquals("21:" + Base64.getEncoder().encodeToString(ByteArray(56).also { it[2] = 0x02 }), inner.encode())
    }

    @Test
    fun theDecoderRefusesEverythingElse() {
        val good = pattern(21).encode()
        val base64 = good.substringAfter(':')
        val bytes = Base64.getDecoder().decode(base64)
        val unusedBitSet = bytes.copyOf().also { it[55] = (it[55].toInt() or 0x01).toByte() }
        val bad = listOf(
            "",
            ":",
            base64, // no size
            ":$base64",
            "25:$base64", // the wrong size for these bits
            "021:$base64",
            "+21:$base64",
            " 21:$base64",
            "21 :$base64",
            "21:$base64 ",
            "21:${base64.dropLast(1)}", // padding missing
            "21:${base64.trimEnd('=')}",
            "21:$base64=",
            "21:$base64" + "AAAA",
            "21:${base64.replace('A', '-')}", // the URL alphabet
            "21:${base64.substring(0, 10)}\n${base64.substring(10)}",
            "21:${Base64.getEncoder().encodeToString(unusedBitSet)}",
            "21:${Base64.getEncoder().encodeToString(bytes.copyOf(55))}",
            "21:${Base64.getEncoder().encodeToString(bytes.copyOf(57))}",
            "21:" + base64.dropLast(2) + "B=", // a stray bit in the last character (not canonical)
            "21:" + "A".repeat(10_000),
            "x".repeat(100_000),
            "17:AAAA",
            "37:" + Base64.getEncoder().encodeToString(ByteArray((37 * 37 + 7) / 8)),
        )
        for (text in bad) {
            assertFailsWith<IllegalArgumentException>(text.take(40)) { QrModules.decode(text) }
            assertNull(QrModules.decodeOrNull(text), text.take(40))
        }
        assertEquals(pattern(21), QrModules.decodeOrNull(good))
    }
}
