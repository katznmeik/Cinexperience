package io.github.katznmeik.cinexperience.core.ticket.pdf

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits

/**
 * Counts the values all parsers of one document build ([TicketPdfLimits.MAX_PARSED_VALUES],
 * [TicketPdfError.TOO_MANY_OBJECTS] beyond): in its objects, which the object cache
 * keeps, in its content streams and in its CMaps, kept or dropped at once.
 */
internal class ParseBudget {
    private var used = 0

    fun take() {
        if (++used > TicketPdfLimits.MAX_PARSED_VALUES) {
            refuse(TicketPdfError.TOO_MANY_OBJECTS, "More than ${TicketPdfLimits.MAX_PARSED_VALUES} values parsed")
        }
    }
}
