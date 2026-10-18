package io.github.katznmeik.cinexperience.core.data.json

import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * JSON form of a ticket's seats (spec §4.7), stored in `ticket.seats`: one object
 * per seat in the booking's order, both fields always written, e.g.
 *
 * ```json
 * [{"row":"7","seat":"10"},{"row":"7","seat":"11"},{"row":null,"seat":"12"}]
 * ```
 *
 * `row` is `null` for a seat without a row name. Reading is strict (unknown or
 * missing fields are errors) and every seat is checked by [TicketSeat] and the
 * list by [Ticket]'s rules (0 to [Ticket.MAX_SEATS] seats, none twice; `[]` when
 * the seats couldn't be read from the ticket). The database holds only what
 * [encode] wrote; the backup has its own DTOs.
 */
internal object TicketSeatsJson {

    @Serializable
    private data class SeatJson(val row: String?, val seat: String)

    private val json = Json {
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    private val serializer = ListSerializer(SeatJson.serializer())

    /** Longest text [decode] looks at: 20 seats of the longest labels take far less. */
    const val MAX_JSON_LENGTH: Int = 8_192

    fun encode(seats: List<TicketSeat>): String = json.encodeToString(serializer, seats.map { SeatJson(it.row, it.seat) })

    /** @throws IllegalArgumentException (also a SerializationException) for anything [encode] wouldn't write. */
    fun decode(text: String): List<TicketSeat> {
        require(text.length <= MAX_JSON_LENGTH) { "Ticket seats JSON is too long" }
        val seats = json.decodeFromString(serializer, text).map { TicketSeat(it.row, it.seat) }
        require(seats.size <= Ticket.MAX_SEATS && seats.toSet().size == seats.size) { "Invalid ticket seats" }
        return seats
    }

    /** [decode], or `null` for a text it refuses. */
    fun decodeOrNull(text: String): List<TicketSeat>? = try {
        decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }
}
