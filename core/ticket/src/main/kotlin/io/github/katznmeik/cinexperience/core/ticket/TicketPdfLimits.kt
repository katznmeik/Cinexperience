package io.github.katznmeik.cinexperience.core.ticket

/**
 * The reader's hard limits (spec §11.2, Security & privacy rule 1). A ticket PDF is
 * untrusted: anything beyond these is refused with a [TicketPdfError], so a crafted
 * file can't make the reader use much memory or time. Yorck's real ticket is far
 * below all of them (one page, 27 objects, about 170 KB, a 55 KB content stream,
 * fewer than 2,000 glyphs, a 116 × 116 px QR code image).
 */
object TicketPdfLimits {
    /** The file itself ([TicketPdfError.TOO_LARGE]). */
    const val MAX_FILE_BYTES: Int = 2 * 1024 * 1024

    /** Pages in the page tree ([TicketPdfError.TOO_MANY_PAGES]). */
    const val MAX_PAGES: Int = 4

    /** Objects in the cross-reference table ([TicketPdfError.TOO_MANY_OBJECTS]). */
    const val MAX_OBJECTS: Int = 2_000

    /** One stream after unpacking ([TicketPdfError.STREAM_TOO_LARGE]). */
    const val MAX_STREAM_BYTES: Int = 2 * 1024 * 1024

    /** All unpacked streams together ([TicketPdfError.STREAM_TOO_LARGE]). */
    const val MAX_TOTAL_STREAM_BYTES: Int = 8 * 1024 * 1024

    /** Arrays and dictionaries inside each other ([TicketPdfError.NESTING_TOO_DEEP]). */
    const val MAX_NESTING: Int = 32

    /** Levels of the page tree ([TicketPdfError.NESTING_TOO_DEEP]). */
    const val MAX_PAGE_TREE_DEPTH: Int = 8

    /** Saved graphics states (`q` inside `q`) in a content stream ([TicketPdfError.NESTING_TOO_DEEP]). */
    const val MAX_GRAPHICS_STATE_DEPTH: Int = 64

    /** Entries of one array or dictionary ([TicketPdfError.UNSUPPORTED]). */
    const val MAX_CONTAINER_ITEMS: Int = 10_000

    /** Content streams of one page ([TicketPdfError.UNSUPPORTED]). */
    const val MAX_CONTENT_STREAMS_PER_PAGE: Int = 32

    /** Glyphs drawn on all pages together ([TicketPdfError.TOO_MUCH_TEXT]). */
    const val MAX_GLYPHS: Int = 50_000

    /** Only images at most this wide and high are decoded, as QR code candidates. */
    const val MAX_IMAGE_SIDE: Int = 512

    /** At most this many QR code candidate images are decoded; further ones are skipped. */
    const val MAX_QR_CANDIDATES: Int = 8
}
