package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.model.labels
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
        assertInvalid("""{"formatVersion":4,"rows":[{"cells":"S"}]}""")
        assertInvalid("""{"formatVersion":2,"rows":[{"cells":"SX"}]}""")
        assertInvalid("""{"formatVersion":0,"rows":[{"cells":"S"}]}""")
        assertInvalid("""{"formatVersion":1,"rows":[{"cells":"SX"}]}""")
        assertInvalid("""{"formatVersion":1,"rows":[{"cells":"SS","seatIds":[1]}]}""")
        assertInvalid("""{"formatVersion":1,"rows":[{"cells":"S","numbering":"BACKWARDS"}]}""")
        assertInvalid("""{"formatVersion":1,"rows":[{"cells":"SS"},{"cells":"S"}]}""")
        assertInvalid("""{"formatVersion":1,"nextSeatId":1,"rows":[{"cells":"S","seatIds":[1]}]}""")
        assertInvalid("""{"formatVersion":1,"rows":[]}""")
    }

    // ---- Version 3: numbering direction and fixed seat labels ----

    private val kantRow = layoutOf("SSSSSSW", "SSSSSSS")
        .updateRow(0) { it.copy(label = "8", numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
        .withFixedLabel(0, 6, "R")

    @Test
    fun directionAndSeatLabelsUseVersion3AndRoundTrip() {
        val json = LayoutJson.encode(kantRow)
        assertTrue(json, json.contains("\"formatVersion\":3"))
        assertTrue(json, json.contains("\"direction\":\"RIGHT_TO_LEFT\""))
        assertTrue(json, json.contains("\"seatLabels\":[null,null,null,null,null,null,\"R\"]"))
        // Defaults are not written: the second row has neither field.
        assertTrue(json, json.contains("{\"cells\":\"SSSSSSS\",\"seatIds\":[8,9,10,11,12,13,14]}"))
        assertEquals(kantRow, LayoutJson.decode(json))
        assertEquals(listOf("6", "5", "4", "3", "2", "1", "R"), kantRow.rows[0].seatIds.map { LayoutJson.decode(json).labels().labelOf(it)!!.seat })
    }

    @Test
    fun theLowestVersionThatHoldsTheLayoutIsWritten() {
        val plain = layoutOf("SS|SS")
        assertEquals(1, LayoutJson.minimumVersion(plain))
        assertEquals(2, LayoutJson.minimumVersion(plain.withSeatKind(0, 0, SeatKind.WHEELCHAIR)))
        val rightToLeft = plain.updateRow(0) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
        assertEquals(3, LayoutJson.minimumVersion(rightToLeft))
        assertEquals(3, LayoutJson.minimumVersion(plain.withFixedLabel(0, 1, "R")))
        // Back to the defaults: back to version 1, byte for byte.
        val reverted = rightToLeft.updateRow(0) { it.copy(numberingDirection = NumberingDirection.LEFT_TO_RIGHT) }
        assertEquals(LayoutJson.encode(plain), LayoutJson.encode(reverted))
        assertEquals(LayoutJson.encode(plain), LayoutJson.encode(plain.withFixedLabel(0, 1, "R").withFixedLabel(0, 1, null)))
    }

    @Test
    fun storedLayoutsOfOlderVersionsReadUnchangedExceptForWheelchairNumbers() {
        // A version 2 layout as the app stored it before: wheelchair spaces now carry numbers.
        val v2 = """{"formatVersion":2,"nextSeatId":12,"rows":[{"cells":"W.W|SS|W","seatIds":[1,2,3,4,5]},""" +
            """{"cells":"SSS||SSS","seatIds":[6,7,8,9,10,11],"numbering":"RESTART_PER_BLOCK"}]}"""
        val layout = LayoutJson.decode(v2)
        assertEquals(NumberingDirection.LEFT_TO_RIGHT, layout.rows[0].numberingDirection)
        assertEquals(listOf<String?>(null, null, null, null, null), layout.rows[0].fixedLabels)
        val labels = layout.labels()
        assertEquals(listOf("1", "2", "3", "4", "5"), layout.rows[0].seatIds.map { labels.labelOf(it)!!.seat })
        assertEquals(listOf("1", "2", "3", "1", "2", "3"), layout.rows[1].seatIds.map { labels.labelOf(it)!!.seat })
        // Writing it again gives the same JSON (still version 2).
        assertEquals(v2, LayoutJson.encode(layout))
    }

    @Test
    fun seedStyleLayoutsMayUseTheNewFields() {
        val layout = LayoutJson.decode(
            """{"formatVersion":3,"rows":[{"label":"1","cells":"WSSSSSS","seatLabels":["R",null,null,null,null,null,null]},""" +
                """{"label":"2","cells":"SS||SSS","direction":"RIGHT_TO_LEFT","blockStarts":[null,4]}]}""",
        )
        val labels = layout.labels()
        assertEquals(listOf("R", "1", "2", "3", "4", "5", "6"), layout.rows[0].seatIds.map { labels.labelOf(it)!!.seat })
        assertEquals(listOf("8", "7", "6", "5", "4"), layout.rows[1].seatIds.map { labels.labelOf(it)!!.seat })
        assertEquals(LayoutCell.Seat(SeatId(1), SeatKind.WHEELCHAIR, "R"), layout.cell(0, 0))
        // Reading is lenient about the version, as for seat kinds.
        val lenient = LayoutJson.decode("""{"formatVersion":1,"rows":[{"cells":"SS","direction":"RIGHT_TO_LEFT"}]}""")
        assertEquals(NumberingDirection.RIGHT_TO_LEFT, lenient.rows[0].numberingDirection)
    }

    @Test
    fun invalidDirectionsAndSeatLabelsAreRejected() {
        assertInvalid("""{"formatVersion":3,"rows":[{"cells":"SS","direction":"BACKWARDS"}]}""")
        // One label per seat of any kind, gaps and aisles don't count.
        assertInvalid("""{"formatVersion":3,"rows":[{"cells":"S.W","seatLabels":["R"]}]}""")
        assertInvalid("""{"formatVersion":3,"rows":[{"cells":"S.W","seatLabels":["R",null,null]}]}""")
        assertInvalid("""{"formatVersion":3,"rows":[{"cells":"SW","seatLabels":[null,""]}]}""")
        assertInvalid("""{"formatVersion":3,"rows":[{"cells":"SW","seatLabels":[null," "]}]}""")
        assertInvalid("""{"formatVersion":3,"rows":[{"cells":"SW","seatLabels":[null,"ABCDE"]}]}""")
        assertInvalid("""{"formatVersion":3,"rows":[{"cells":"SW","seatLabels":[null," R"]}]}""")
        // Valid: 4 characters; only nulls (written back without the field, as version 2).
        val fourCharacters = LayoutJson.decode("""{"formatVersion":3,"rows":[{"cells":"SW","seatLabels":[null,"R12a"]}]}""")
        assertEquals("R12a", (fourCharacters.cell(0, 1) as LayoutCell.Seat).fixedLabel)
        val onlyNulls = LayoutJson.decode("""{"formatVersion":3,"rows":[{"cells":"SW","seatLabels":[null,null]}]}""")
        assertEquals(2, LayoutJson.minimumVersion(onlyNulls))
        assertTrue(!LayoutJson.encode(onlyNulls).contains("seatLabels"))
    }
}
