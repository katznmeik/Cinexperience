package io.github.katznmeik.cinexperience.core.ticket.testing

import io.github.katznmeik.cinexperience.core.ticket.testing.PdfBuilder.Companion.latin1

/** One ticket block of an invented ticket; `null` leaves a label out. All values are invented. */
internal data class TicketSpec(
    val anchor: String = "Ticket",
    val title: List<String> = listOf("The Quiet Projectionist"),
    val titleBold: Boolean = true,
    val cinema: String? = "delphi LUX",
    val date: String? = "24.10.2026",
    val time: String? = "20:15 Uhr",
    val hall: String? = "4",
    val row: String? = "7",
    val seat: String? = "10",
    val category: String? = "1 x Normal",
    val categoryLabel: String = "Kat.",
    /** Where the category line's baseline is, from the top of the block's page area. */
    val categoryTop: Double = 311.0,
    val bookedBy: String? = "Erika Mustermann",
    val bookedByLabel: String = "Gebucht von",
    /** How far the "Gebucht von" line is indented from the block's column (8 pt on Yorck's ticket). */
    val bookedByIndent: Double = 8.0,
    /** Where the "Gebucht von" line's baseline is, from the top of the block's page area. */
    val bookedByTop: Double = 353.0,
    /** The booker's line in bold, as on Yorck's ticket. */
    val bookedByBold: Boolean = true,
    /** The name on its own line, with the label on the line below it. */
    val bookedByNameFirst: Boolean = false,
    /** The label at its own indent, apart from the name (two segments on one line); `null`: label and name as one text. */
    val bookedByLabelIndent: Double? = null,
    /** A label without a value on its own line right above the booker's line(s). */
    val labelAboveBooker: String? = null,
    /** Values on the line below their labels instead of next to them. */
    val valuesBelow: Boolean = false,
    /** Labels with a colon ("Kino: delphi LUX"). */
    val colons: Boolean = false,
)

/** One page of an invented ticket. */
internal data class PageSpec(
    val tickets: List<TicketSpec> = listOf(TicketSpec()),
    /** The second ticket next to the first (same line) instead of below it. */
    val sideBySide: Boolean = false,
    val bookingNumber: String? = "TEST123",
    /** The booking number next to its label instead of on the line below (as Yorck prints it). */
    val bookingNumberBeside: Boolean = false,
    val leftColumn: Boolean = true,
    val conditions: Boolean = true,
    val qrImage: Boolean = true,
    val logo: Boolean = true,
)

/**
 * An invented ticket with the structure of Yorck's (spec §11.2; positions, font sizes
 * and drawing order as on the real ticket, every text invented): A3, Qt 5.5 content,
 * Type 0 / Identity-H Arial fonts with ToUnicode CMaps (no font programs), a Flate
 * content stream, a 2000 px "logo" image that is never decoded and a 116 px JPEG of
 * [TestQrCodes.grid] at 4 px per module with a 4-module quiet zone.
 */
internal object InventedTicketPdf {
    val QR_GRID: BooleanArray = TestQrCodes.grid(21)
    val QR_JPEG: ByteArray by lazy { TestQrCodes.jpeg(TestQrCodes.render(QR_GRID, 21, modulePixels = 4.0, quietModules = 4)) }

    const val QR_PIXELS = 116

    /** Never decoded (larger than 512 px); not even a complete JPEG. */
    private val LOGO = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    fun build(
        pages: List<PageSpec> = listOf(PageSpec()),
        cmapStyle: CMapStyle = CMapStyle.QT,
        qrJpeg: ByteArray = QR_JPEG,
        qrPixels: Int = QR_PIXELS,
        compress: Boolean = true,
        /** Rewrites each page's content stream before it is stored. */
        mutateContent: (String) -> String = { it },
        /** Extra entries for each page dictionary. */
        pageExtra: String = "",
        /** More QR code candidates: copies of the QR image as objects of their own, all drawn. */
        extraQrImages: Int = 0,
        /** Extra catalogue entries (e.g. actions the reader must ignore). */
        catalogExtra: String = "",
        /** Unused `null` objects, to reach the object limit. */
        extraObjects: Int = 0,
    ): ByteArray {
        val regular = TestFont("F11", "ArialMT", style = cmapStyle)
        val bold = TestFont("F12", "Arial-BoldMT", style = cmapStyle)
        val builder = PdfBuilder()
        val info = builder.add("<< /Title (Ticket) /Creator (Test) /Producer (Qt 5.5.1) /CreationDate (D:20261017120000) >>")
        val catalog = builder.reserve()
        val pagesNode = builder.reserve()
        val graphicsState = builder.add("<< /Type /ExtGState /SA true /SM 0.02 /ca 1.0 /CA 1.0 /AIS false /SMask /None>>")
        val patternSpace = builder.add("[/Pattern /DeviceRGB]")
        val logo = builder.addStream("/Type /XObject /Subtype /Image /Width 2000 /Height 2000 /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /DCTDecode", LOGO)
        val qr = builder.addStream("/Type /XObject /Subtype /Image /Width $qrPixels /Height $qrPixels /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /DCTDecode", qrJpeg)
        val link = builder.add("<< /Type /Annot /Subtype /Link /Rect [56  721  156  737 ] /Border [0 0 0] /A << /Type /Action /S /URI /URI (https://example.invalid/) >> >>")
        val extras = (1..extraQrImages).map {
            builder.addStream("/Type /XObject /Subtype /Image /Width $qrPixels /Height $qrPixels /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /DCTDecode", qrJpeg)
        }
        val pageNumbers = pages.map { builder.reserve() }
        val resources = builder.reserve()
        val draws = extras.indices.joinToString("") { "q 10 0 0 10 0 0 cm /Ix$it Do Q\n" }
        val contents = pages.map { spec -> mutateContent(content(spec, regular, bold) + draws) }
        val fontRegular = regular.addTo(builder)
        val fontBold = bold.addTo(builder)
        val extraNames = extras.mapIndexed { i, number -> "/Ix$i $number 0 R" }.joinToString(" ")
        builder.set(
            resources,
            "<< /ColorSpace << /PCSp $patternSpace 0 R /CSp /DeviceRGB /CSpg /DeviceGray >> /ExtGState << /GSa $graphicsState 0 R >> " +
                "/Pattern << >> /Font << /F11 $fontRegular 0 R /F12 $fontBold 0 R >> /XObject << /Im7 $logo 0 R /Im9 $qr 0 R $extraNames >> >>",
        )
        pageNumbers.forEachIndexed { index, page ->
            val stream = if (compress) builder.addFlateStream("", contents[index].latin1()) else builder.addStream("", contents[index].latin1())
            builder.set(
                page,
                "<< /Type /Page /Parent $pagesNode 0 R /Contents $stream 0 R /Resources $resources 0 R /Annots [$link 0 R] /MediaBox [0 0 842 1191] $pageExtra>>",
            )
        }
        builder.set(catalog, "<< /Type /Catalog /Pages $pagesNode 0 R $catalogExtra>>")
        repeat(extraObjects) { builder.add("null") }
        builder.set(pagesNode, "<< /Type /Pages /Kids [${pageNumbers.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} /ProcSet [/PDF /Text /ImageB /ImageC] >>")
        return builder.build(catalog, info = info)
    }

    private fun content(page: PageSpec, regular: TestFont, bold: TestFont): String {
        val out = QtContent()
        if (page.logo) out.image("Im7", 56.0, 32.0, 150.0, 2000)
        if (page.qrImage) out.image("Im9", 56.0, 196.0, 150.0, QR_PIXELS)
        page.tickets.forEachIndexed { index, ticket ->
            val (dx, dy) = if (page.sideBySide) index * 285.0 to 0.0 else 0.0 to index * 330.0
            block(out, ticket, regular, bold, 275.0 + dx, dy)
        }
        if (page.leftColumn) leftColumn(out, page, regular, bold)
        if (page.conditions && page.tickets.size <= 1) conditions(out, regular, bold)
        return out.toString()
    }

    private fun block(out: QtContent, ticket: TicketSpec, regular: TestFont, bold: TestFont, x: Double, dy: Double) {
        out.text(regular, 35.0, x, 64.0 + dy, ticket.anchor)
        ticket.title.forEachIndexed { i, line -> out.text(if (ticket.titleBold) bold else regular, 38.0, x, 117.0 + dy + i * 44.0, line) }
        val shift = dy + 44.0 * maxOf(0, ticket.title.size - 1)
        fun cell(label: String, labelX: Double, top: Double, value: String?, gap: Double = 4.0) {
            if (value == null) return
            val shown = if (ticket.colons) "$label:" else label
            val end = out.text(regular, 14.0, labelX, top + shift, shown)
            if (ticket.valuesBelow) {
                out.text(regular, 20.0, labelX, top + shift + 22.0, value)
            } else {
                val tabEnd = out.text(regular, 16.0, end, top + shift, " ")
                out.text(regular, 20.0, tabEnd + gap, top + shift, value)
            }
        }
        cell("Kino", x, 170.0, ticket.cinema)
        cell("Datum", x, 203.0, ticket.date)
        cell("Zeit", x + 223.0, 203.0, ticket.time)
        cell("Saal", x, 257.0, ticket.hall)
        cell("Reihe", x + 226.0, 257.0, ticket.row)
        cell("Platz", x + 460.0, 257.0, ticket.seat)
        cell(ticket.categoryLabel, x, ticket.categoryTop, ticket.category, gap = 22.0)
        ticket.bookedBy?.let { name -> booker(out, ticket, name, if (ticket.bookedByBold) bold else regular, regular, x, ticket.bookedByTop + shift) }
    }

    private fun booker(out: QtContent, ticket: TicketSpec, name: String, font: TestFont, regular: TestFont, blockX: Double, top: Double) {
        val x = blockX + ticket.bookedByIndent
        ticket.labelAboveBooker?.let { out.text(regular, 12.0, x, top - 19.0, it) }
        when {
            ticket.bookedByNameFirst -> {
                out.text(font, 15.0, x, top, name)
                out.text(font, 15.0, x, top + 19.0, ticket.bookedByLabel)
            }
            ticket.bookedByLabelIndent != null -> {
                out.text(font, 15.0, blockX + ticket.bookedByLabelIndent, top, ticket.bookedByLabel)
                out.text(font, 15.0, x, top, name)
            }
            else -> out.text(font, 15.0, x, top, "${ticket.bookedByLabel} $name")
        }
    }

    private fun leftColumn(out: QtContent, page: PageSpec, regular: TestFont, bold: TestFont) {
        page.bookingNumber?.let { number ->
            if (page.bookingNumberBeside) {
                out.text(regular, 12.0, 56.0, 379.0, "Buchungsnummer $number")
            } else {
                out.text(regular, 12.0, 56.0, 379.0, "Buchungsnummer")
                out.text(bold, 14.0, 56.0, 398.0, number)
            }
        }
        out.text(regular, 12.0, 56.0, 448.0, "Fragen zur Buchung:")
        out.text(bold, 14.0, 56.0, 467.0, "Kasse vor Ort")
        out.text(regular, 12.0, 56.0, 501.0, "Kinoadresse")
        out.text(bold, 14.0, 56.0, 520.0, "delphi LUX")
        out.text(bold, 14.0, 56.0, 536.0, "Teststra\u00dfe 1")
        out.text(bold, 14.0, 56.0, 552.0, "12345 Berlin")
        out.text(regular, 12.0, 56.0, 584.0, "Transaktionsnummer")
        out.text(bold, 14.0, 56.0, 603.0, "1234567")
        out.text(regular, 12.0, 56.0, 635.0, "Ausstellungsdatum")
        out.text(bold, 14.0, 56.0, 654.0, "17.10.2026")
        out.text(regular, 12.0, 56.0, 686.0, "Gesamtsumme")
        out.text(bold, 14.0, 56.0, 705.0, "9,50 \u20ac")
        out.text(bold, 9.0, 56.0, 731.0, "1 x Normal \u00e1 9,50 \u20ac")
        out.text(regular, 12.0, 56.0, 759.0, "Inkl. 7% MwSt.")
        out.text(regular, 12.0, 56.0, 773.0, "Kein Umtausch")
        out.text(bold, 9.0, 56.0, 827.0, "Test Kino GmbH")
        out.text(bold, 9.0, 56.0, 837.0, "Teststra\u00dfe 1, 12345 Berlin")
        out.text(regular, 12.0, 56.0, 893.0, "Ticketnummer(n)")
        out.text(bold, 9.0, 56.0, 907.0, "98765432101 (TN:1234567)")
    }

    /** Invented conditions text; some lines start with ticket labels, which must not count. */
    private fun conditions(out: QtContent, regular: TestFont, bold: TestFont) {
        val lines = listOf(
            true to "Hinweise zu Ihrem Ticket und zum Besuch",
            false to "Platz und Reihe stehen auf dem Ticket. Bitte",
            false to "bringen Sie das Ticket ausgedruckt oder auf",
            false to "dem Telefon mit. Einlass ab 30 Minuten vor",
            false to "Zeit der Vorstellung, Werbung etwa 15 Minuten.",
            false to "Datum und Uhrzeit sind verbindlich.",
            true to "Kein Umtausch",
            false to "Saal und Kino k\u00f6nnen sich in Ausnahmen \u00e4ndern.",
            false to "Reihe 1 ist f\u00fcr Gruppen reserviert.",
            false to "Buchungsnummer bitte bei Fragen angeben.",
            false to "Kino",
            false to "delphi LUX und alle anderen Kinos.",
        )
        lines.forEachIndexed { i, (heading, text) ->
            out.text(if (heading) bold else regular, 16.0, 275.0, 410.0 + i * 18.0, text)
        }
    }
}
