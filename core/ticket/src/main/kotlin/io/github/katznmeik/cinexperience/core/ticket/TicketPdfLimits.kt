package io.github.katznmeik.cinexperience.core.ticket

/**
 * The reader's hard limits on the file (spec §11.2, Security & privacy rule 1). A
 * ticket PDF is untrusted: anything beyond these is refused with a [TicketPdfError],
 * so a crafted file can't make the reader use much memory or time. The limits count
 * work, not only distinct objects: an object reused many times counts each time.
 * Yorck's real ticket is far below all of them (one page, 27 objects, about 170 KB,
 * a 55 KB content stream, fewer than 2,000 glyphs, a 116 × 116 px QR code image of
 * less than 5 KB). Smaller limits of single parts live next to their code (the
 * lexer's token lengths, the CMap's entries, the ticket fields' block count).
 */
object TicketPdfLimits {
    /** The file itself ([TicketPdfError.TOO_LARGE]). */
    const val MAX_FILE_BYTES: Int = 2 * 1024 * 1024

    /** Pages in the page tree ([TicketPdfError.TOO_MANY_PAGES]). */
    const val MAX_PAGES: Int = 4

    /** Nodes visited in the page tree, each time one is reached ([TicketPdfError.TOO_MANY_PAGES]). */
    const val MAX_PAGE_TREE_NODES: Int = 64

    /** Objects in the cross-reference table ([TicketPdfError.TOO_MANY_OBJECTS]). */
    const val MAX_OBJECTS: Int = 2_000

    /** One stream after unpacking ([TicketPdfError.STREAM_TOO_LARGE]). */
    const val MAX_STREAM_BYTES: Int = 2 * 1024 * 1024

    /**
     * All unpacked streams together, and separately all content interpreted on all
     * pages together, a stream listed several times counting each time
     * ([TicketPdfError.STREAM_TOO_LARGE]).
     */
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

    /** Glyph widths (codes in `/W` arrays) read in all fonts together ([TicketPdfError.UNSUPPORTED]). */
    const val MAX_GLYPH_WIDTHS: Int = 65_536

    /** Character codes shown on all pages together, mapped to text or not ([TicketPdfError.TOO_MUCH_TEXT]). */
    const val MAX_GLYPHS: Int = 50_000

    /** Only images at most this wide and high are decoded, as QR code candidates. */
    const val MAX_IMAGE_SIDE: Int = 512

    /** Only images of at most this many bytes (the JPEG as stored) are decoded, as QR code candidates. */
    const val MAX_QR_IMAGE_BYTES: Int = 256 * 1024

    /** At most this many QR code candidate images are decoded; further ones are skipped. */
    const val MAX_QR_CANDIDATES: Int = 8
}
