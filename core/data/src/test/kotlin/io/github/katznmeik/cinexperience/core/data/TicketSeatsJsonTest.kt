package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.TicketSeatsJson
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The `ticket.seats` column (spec §4.7): a fixed JSON form, read strictly. */
class TicketSeatsJsonTest {

    private val seats = listOf(TicketSeat("7", "10"), TicketSeat("7", "11"), TicketSeat(null, "R"))

    @Test
    fun theFormIsOneObjectPerSeatInTheBookingsOrder() {
        val json = TicketSeatsJson.encode(seats)
        assertEquals("""[{"row":"7","seat":"10"},{"row":"7","seat":"11"},{"row":null,"seat":"R"}]""", json)
        assertEquals(seats, TicketSeatsJson.decode(json))
        // No seats: they couldn't be read from the ticket.
        assertEquals("[]", TicketSeatsJson.encode(emptyList()))
        assertEquals(emptyList<TicketSeat>(), TicketSeatsJson.decode("[]"))
    }

    @Test
    fun anythingElseIsRefused() {
        val bad = listOf(
            "",
            "null",
            "{}",
            """[{"row":"7"}]""", // no seat field
            """[{"seat":"10"}]""", // no row field
            """[{"row":"7","seat":"10","kind":"SOFA"}]""",
            """[{"row":"7","seat":"10"},{"row":"7","seat":"10"}]""", // twice
            """[{"row":"-","seat":"10"}]""",
            """[{"row":" 7","seat":"10"}]""",
            """[{"row":"7","seat":"ABCDE"}]""",
            """[{"row":7,"seat":10}]""",
            "[" + List(21) { """{"row":"1","seat":"${it + 1}"}""" }.joinToString(",") + "]",
            """[{"row":"7","seat":"10"}""",
            "[" + " ".repeat(TicketSeatsJson.MAX_JSON_LENGTH) + """{"row":"7","seat":"10"}]""",
        )
        for (text in bad) {
            assertThrows(text.take(60), IllegalArgumentException::class.java) { TicketSeatsJson.decode(text) }
            assertNull(text.take(60), TicketSeatsJson.decodeOrNull(text))
        }
    }
}
