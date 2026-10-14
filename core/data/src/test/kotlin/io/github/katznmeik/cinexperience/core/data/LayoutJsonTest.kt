package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.Assert.assertThrows

class LayoutJsonTest {

    private fun assertInvalid(json: String) {
        assertThrows(IllegalArgumentException::class.java, ThrowingRunnable { LayoutJson.decode(json) })
    }

    @Test
    fun roundTripKeepsIdsSettingsAndNextSeatId() {
        val layout = layoutOf("SS|SS", ".S|SS", "|||||")
            .insertRow(0)
            .withCellType(1, 0, CellType.GAP)
            .updateRow(1) {
                it.copy(
                    label = "A",
                    numbering = SeatNumbering.RESTART_PER_BLOCK,
                    blockStartNumbers = listOf(null, 7),
                    halfSeatOffset = true,
                )
            }
        val json = LayoutJson.encode(layout)
        assertTrue(json, json.contains("\"formatVersion\":1"))
        assertEquals(layout, LayoutJson.decode(json))
    }

    @Test
    fun trailingAutomaticBlockStartsAreNotStored() {
        val layout = layoutOf("S|S").updateRow(0) { it.copy(blockStartNumbers = listOf(3, null)) }
        val decoded = LayoutJson.decode(LayoutJson.encode(layout))
        assertEquals(listOf(3), decoded.rows[0].blockStartNumbers)
    }

    @Test
    fun seedStyleLayoutWithoutIdsGetsRowMajorIds() {
        val layout = LayoutJson.decode(
            """{"formatVersion":1,"rows":[{"cells":"SS|S"},{"cells":".SS.","halfSeatOffset":true}]}""",
        )
        assertEquals(listOf(SeatId(1), SeatId(2), SeatId(3)), layout.rows[0].seatIds)
        assertEquals(listOf(SeatId(4), SeatId(5)), layout.rows[1].seatIds)
        assertEquals(6, layout.nextSeatId)
        assertTrue(layout.rows[1].halfSeatOffset)
    }

    @Test
    fun rowsWithoutIdsDoNotCollideWithExplicitIds() {
        val layout = LayoutJson.decode(
            """{"formatVersion":1,"rows":[{"cells":"SS"},{"cells":"SS","seatIds":[1,2]}]}""",
        )
        assertEquals(listOf(SeatId(3), SeatId(4)), layout.rows[0].seatIds)
        assertEquals(5, layout.nextSeatId)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val layout = LayoutJson.decode("""{"formatVersion":1,"future":true,"rows":[{"cells":"S","extra":1}]}""")
        assertEquals(1, layout.seatCount)
    }

    @Test
    fun seatKindsUseVersion2AndRoundTrip() {
        val layout = layoutOf("SWCF|S.", "SSSS|SS").withSeatKind(1, 5, SeatKind.SOFA)
        val json = LayoutJson.encode(layout)
        assertTrue(json, json.contains("\"formatVersion\":2"))
        assertTrue(json, json.contains("\"cells\":\"SWCF|S.\""))
        assertEquals(layout, LayoutJson.decode(json))
        // Without seat kinds the JSON stays version 1, byte for byte as before.
        val plain = layout.withSeatKind(0, 1, SeatKind.STANDARD).withSeatKind(0, 2, SeatKind.STANDARD)
            .withSeatKind(0, 3, SeatKind.STANDARD).withSeatKind(1, 5, SeatKind.STANDARD)
        assertTrue(LayoutJson.encode(plain).contains("\"formatVersion\":1"))
    }

    @Test
    fun version1LayoutsAndSeedsWithKindsStillDecode() {
        val v1 = LayoutJson.decode("""{"formatVersion":1,"nextSeatId":3,"rows":[{"cells":"SS","seatIds":[1,2]}]}""")
        assertEquals(LayoutCell.Seat(SeatId(1)), v1.cell(0, 0))
        // Reading is lenient: a hand-written seed layout may use the kind characters with version 1.
        val seed = LayoutJson.decode("""{"formatVersion":1,"rows":[{"cells":"WC|F"}]}""")
        assertEquals(LayoutCell.Seat(SeatId(1), SeatKind.WHEELCHAIR), seed.cell(0, 0))
        assertEquals(LayoutCell.Seat(SeatId(2), SeatKind.COMPANION), seed.cell(0, 1))
        assertEquals(LayoutCell.Seat(SeatId(3), SeatKind.SOFA), seed.cell(0, 3))
    }

    @Test
    fun invalidLayoutsAreRejected() {
        assertInvalid("""{"formatVersion":3,"rows":[{"cells":"S"}]}""")
        assertInvalid("""{"formatVersion":2,"rows":[{"cells":"SX"}]}""")
        assertInvalid("""{"formatVersion":0,"rows":[{"cells":"S"}]}""")
        assertInvalid("""{"formatVersion":1,"rows":[{"cells":"SX"}]}""")
        assertInvalid("""{"formatVersion":1,"rows":[{"cells":"SS","seatIds":[1]}]}""")
        assertInvalid("""{"formatVersion":1,"rows":[{"cells":"S","numbering":"BACKWARDS"}]}""")
        assertInvalid("""{"formatVersion":1,"rows":[{"cells":"SS"},{"cells":"S"}]}""")
        assertInvalid("""{"formatVersion":1,"nextSeatId":1,"rows":[{"cells":"S","seatIds":[1]}]}""")
        assertInvalid("""{"formatVersion":1,"rows":[]}""")
    }
}
