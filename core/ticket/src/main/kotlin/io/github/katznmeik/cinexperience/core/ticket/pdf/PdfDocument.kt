package io.github.katznmeik.cinexperience.core.ticket.pdf

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits

/** A page and the resources it uses (its own or inherited from the page tree). */
internal class PdfPage(val dictionary: PdfDictionary, val resources: PdfDictionary?)

/**
 * A PDF file of the shape Yorck's ticket system writes (spec §11.2): a classic
 * cross-reference table at `startxref`, one trailer, no encryption, no incremental
 * updates, no object or cross-reference streams.
 *
 * Objects are found only through the cross-reference table, never by scanning the
 * file: each must start at its offset with its own `n g obj`, so text inside
 * a stream (an image can contain anything) is never taken for an object. A stream's
 * data is located by its `/Length` (direct or indirect) and must be followed by
 * `endstream`. Objects are parsed on demand and cached; resolving an object that is
 * being parsed (e.g. a stream whose `/Length` refers to itself) or a reference chain
 * that returns to an object is a [TicketPdfError.REFERENCE_CYCLE]; objects needed
 * to parse objects (lengths of lengths…) more than a few levels deep are
 * [TicketPdfError.NESTING_TOO_DEEP].
 *
 * Nothing here follows links, actions, annotations or external files: the reader only
 * looks at the trailer, the catalogue's `/Pages`, the page tree and what
 * [io.github.katznmeik.cinexperience.core.ticket.text] asks for.
 */
internal class PdfDocument private constructor(
    private val data: ByteArray,
    private val entries: Map<Int, XrefEntry>,
    private val trailer: PdfDictionary,
) {
    private class XrefEntry(val offset: Int, val generation: Int)

    private val cache = HashMap<Int, PdfObject>()
    private val loading = HashSet<Int>()
    private val decoded = HashMap<Int, ByteArray>()
    private var unpackedBytes = 0L

    /** [obj] with references followed. A missing object is [PdfNull] (ISO 32000-1, 7.3.10). */
    fun resolve(obj: PdfObject?): PdfObject? {
        var current = obj
        var seen: HashSet<Int>? = null
        while (current is PdfReference) {
            val set = seen ?: HashSet<Int>().also { seen = it }
            if (!set.add(current.number)) refuse(TicketPdfError.REFERENCE_CYCLE, "Reference chain loops")
            current = load(current)
        }
        return current
    }

    fun dictionary(obj: PdfObject?): PdfDictionary? = when (val resolved = resolve(obj)) {
        is PdfDictionary -> resolved
        is PdfStream -> resolved.dictionary
        else -> null
    }

    fun name(obj: PdfObject?): String? = (resolve(obj) as? PdfName)?.name

    fun int(obj: PdfObject?): Int? = (resolve(obj) as? PdfNumber)?.intOrNull()

    fun number(obj: PdfObject?): Double? = (resolve(obj) as? PdfNumber)?.value

    /** The stream's data exactly as stored (e.g. a JPEG for DCTDecode). */
    fun rawData(stream: PdfStream): ByteArray = data.copyOfRange(stream.dataStart, stream.dataStart + stream.dataLength)

    /** The filter names of [stream], e.g. `[FlateDecode]` or `[DCTDecode]`; empty if none. */
    fun filters(stream: PdfStream): List<String> = when (val filter = resolve(stream.dictionary["Filter"])) {
        null, PdfNull -> emptyList()
        is PdfName -> listOf(filter.name)
        is PdfArray -> filter.items.map { name(it) ?: damaged("Filter is not a name") }
        else -> damaged("Filter is not a name")
    }

    /**
     * The stream's data unpacked: unfiltered or FlateDecode only (without predictors);
     * anything else, and data in an external file (`/F`), is [TicketPdfError.UNSUPPORTED].
     * Each stream may unpack to at most [TicketPdfLimits.MAX_STREAM_BYTES], all together
     * to [TicketPdfLimits.MAX_TOTAL_STREAM_BYTES].
     */
    fun decodedData(stream: PdfStream): ByteArray {
        decoded[stream.objectNumber]?.let { return it }
        val streamDictionary = stream.dictionary
        if (EXTERNAL_KEYS.any(streamDictionary::containsKey)) refuse(TicketPdfError.UNSUPPORTED, "Stream data in an external file")
        val filters = filters(stream)
        val result = when {
            filters.isEmpty() -> rawData(stream)
            filters == listOf(FLATE) -> {
                val parameters = when (val decodeParms = resolve(streamDictionary["DecodeParms"])) {
                    is PdfArray -> dictionary(decodeParms.items.singleOrNull())
                    else -> dictionary(decodeParms)
                }
                val predictor = int(parameters?.get("Predictor")) ?: 1
                if (predictor != 1) refuse(TicketPdfError.UNSUPPORTED, "Flate predictor")
                val budget = TicketPdfLimits.MAX_TOTAL_STREAM_BYTES - unpackedBytes
                if (budget <= 0) refuse(TicketPdfError.STREAM_TOO_LARGE, "Streams unpack to too much data")
                Inflate.inflate(data, stream.dataStart, stream.dataLength, minOf(TicketPdfLimits.MAX_STREAM_BYTES.toLong(), budget).toInt())
            }
            else -> refuse(TicketPdfError.UNSUPPORTED, "Stream filter")
        }
        if (result.size > TicketPdfLimits.MAX_STREAM_BYTES) refuse(TicketPdfError.STREAM_TOO_LARGE, "Stream too large")
        unpackedBytes += result.size
        if (unpackedBytes > TicketPdfLimits.MAX_TOTAL_STREAM_BYTES) refuse(TicketPdfError.STREAM_TOO_LARGE, "Streams unpack to too much data")
        decoded[stream.objectNumber] = result
        return result
    }

    /**
     * The pages in order. More than [TicketPdfLimits.MAX_PAGES] is
     * [TicketPdfError.TOO_MANY_PAGES]; a node met twice is a [TicketPdfError.REFERENCE_CYCLE];
     * a tree deeper than [TicketPdfLimits.MAX_PAGE_TREE_DEPTH] is [TicketPdfError.NESTING_TOO_DEEP].
     * `/Resources` is inherited from the ancestors, as in ISO 32000-1, 7.7.3.4.
     */
    fun pages(): List<PdfPage> {
        val catalog = dictionary(trailer["Root"]) ?: damaged("No catalogue")
        val root = catalog["Pages"] ?: damaged("No page tree")
        val count = int(dictionary(root)?.get("Count"))
        if (count != null && count > TicketPdfLimits.MAX_PAGES) refuse(TicketPdfError.TOO_MANY_PAGES, "Page count $count")
        val pages = ArrayList<PdfPage>()
        val visited = HashSet<Int>()
        visitPageNode(root, inheritedResources = null, depth = 0, visited, pages)
        if (pages.isEmpty()) damaged("No pages")
        return pages
    }

    private fun visitPageNode(node: PdfObject, inheritedResources: PdfObject?, depth: Int, visited: MutableSet<Int>, pages: MutableList<PdfPage>) {
        if (depth > TicketPdfLimits.MAX_PAGE_TREE_DEPTH) refuse(TicketPdfError.NESTING_TOO_DEEP, "Page tree too deep")
        if (node is PdfReference && !visited.add(node.number)) refuse(TicketPdfError.REFERENCE_CYCLE, "Page tree node met twice")
        val dictionary = resolve(node) as? PdfDictionary ?: damaged("Page tree node is not a dictionary")
        val resources = dictionary["Resources"] ?: inheritedResources
        val kids = dictionary["Kids"]
        when (name(dictionary["Type"]) ?: if (kids != null) "Pages" else "Page") {
            "Pages" -> {
                val array = resolve(kids) as? PdfArray ?: damaged("Page tree node without kids")
                for (kid in array.items) visitPageNode(kid, resources, depth + 1, visited, pages)
            }
            "Page" -> {
                if (pages.size >= TicketPdfLimits.MAX_PAGES) refuse(TicketPdfError.TOO_MANY_PAGES, "More than ${TicketPdfLimits.MAX_PAGES} pages")
                pages += PdfPage(dictionary, dictionary(resources))
            }
            else -> damaged("Page tree node of an unknown type")
        }
    }

    /** The content streams of [page]: one stream or an array of streams. */
    fun contentStreams(page: PdfPage): List<PdfStream> = when (val contents = resolve(page.dictionary["Contents"])) {
        null, PdfNull -> emptyList()
        is PdfStream -> listOf(contents)
        is PdfArray -> {
            if (contents.items.size > TicketPdfLimits.MAX_CONTENT_STREAMS_PER_PAGE) refuse(TicketPdfError.UNSUPPORTED, "Too many content streams")
            contents.items.map { resolve(it) as? PdfStream ?: damaged("Content is not a stream") }
        }
        else -> damaged("Content is not a stream")
    }

    private fun load(reference: PdfReference): PdfObject {
        val entry = entries[reference.number]
        if (entry == null || entry.generation != reference.generation) return PdfNull
        cache[reference.number]?.let { return it }
        if (reference.number in loading) refuse(TicketPdfError.REFERENCE_CYCLE, "Object refers to itself while being read")
        // Only a stream's indirect /Length is read while an object is parsed: one level in a real file.
        if (loading.size >= MAX_LOADING_DEPTH) refuse(TicketPdfError.NESTING_TOO_DEEP, "Objects needed to read objects nested too deep")
        loading += reference.number
        try {
            val obj = parseObjectAt(reference.number, entry)
            cache[reference.number] = obj
            return obj
        } finally {
            loading.remove(reference.number)
        }
    }

    private fun parseObjectAt(number: Int, entry: XrefEntry): PdfObject {
        val parser = PdfObjectParser(PdfLexer(data, entry.offset), allowReferences = true)
        val head = listOf(parser.next(), parser.next(), parser.next())
        val expected = listOf(PdfNumber(number.toDouble(), true), PdfNumber(entry.generation.toDouble(), true), PdfKeyword("obj"))
        if (head != expected) damaged("Object not at its cross-reference offset")
        val obj = parser.next() ?: damaged("Object cut off")
        if (obj is PdfKeyword) damaged("Object without a value")
        return when (val after = parser.next()) {
            PdfKeyword("endobj") -> obj
            PdfKeyword("stream") -> {
                val dictionary = obj as? PdfDictionary ?: damaged("Stream without a dictionary")
                stream(number, dictionary, parser.lexerWithoutPending())
            }
            else -> damaged(if (after == null) "Object cut off" else "Object without endobj")
        }
    }

    private fun stream(number: Int, dictionary: PdfDictionary, lexer: PdfLexer): PdfStream {
        val start = lexer.streamDataStart()
        val length = (resolve(dictionary["Length"]) as? PdfNumber)?.intOrNull() ?: damaged("Stream without a length")
        if (length < 0 || length > data.size - start) damaged("Stream length beyond the end of the file")
        lexer.seek(start + length)
        if (lexer.next() != PdfToken.Keyword("endstream")) damaged("Stream length doesn't end at endstream")
        if (lexer.next() != PdfToken.Keyword("endobj")) damaged("Stream object without endobj")
        return PdfStream(dictionary, number, start, length)
    }

    companion object {
        private const val FLATE = "FlateDecode"

        /** Objects parsed inside each other (a stream's length object inside the stream). */
        private const val MAX_LOADING_DEPTH = 8
        private val EXTERNAL_KEYS = listOf("F", "FFilter", "FDecodeParms")
        private val HEADER = "%PDF-1.".toByteArray(Charsets.ISO_8859_1)
        private val STARTXREF = "startxref".toByteArray(Charsets.ISO_8859_1)
        private val EOF_MARKER = "%%EOF".toByteArray(Charsets.ISO_8859_1)

        /** `startxref` and `%%EOF` must be within this many bytes of the end. */
        private const val TAIL = 1024

        /**
         * Opens [data]: checks the size, the header, the cross-reference table and the
         * trailer (no encryption, no `/Prev`, no `/XRefStm`), without parsing any object yet.
         */
        fun open(data: ByteArray): PdfDocument {
            if (data.size > TicketPdfLimits.MAX_FILE_BYTES) refuse(TicketPdfError.TOO_LARGE, "File too large")
            if (!startsWith(data, 0, HEADER) || data.size <= HEADER.size || data[HEADER.size] !in '0'.code.toByte()..'7'.code.toByte()) {
                refuse(TicketPdfError.NOT_A_PDF, "No PDF 1.x header")
            }
            val tailStart = maxOf(0, data.size - TAIL)
            val startxref = lastIndexOf(data, STARTXREF, tailStart) ?: damaged("No startxref")
            if (lastIndexOf(data, EOF_MARKER, startxref) == null) damaged("No %%EOF after startxref")
            val xrefOffset = (PdfLexer(data, startxref + STARTXREF.size).next() as? PdfToken.Number)
                ?.takeIf { it.isInteger && it.value >= 0 && it.value < data.size }?.value?.toInt()
                ?: damaged("Bad startxref offset")

            val lexer = PdfLexer(data, xrefOffset)
            val first = lexer.next()
            if (first != PdfToken.Keyword("xref")) {
                if (first is PdfToken.Number) refuseCrossReferenceStream(data, xrefOffset)
                damaged("No cross-reference table at startxref")
            }
            val entries = readEntries(lexer, data.size)
            val trailer = PdfObjectParser(lexer, allowReferences = true).next() as? PdfDictionary ?: damaged("No trailer dictionary")
            if (trailer.containsKey("Encrypt")) refuse(TicketPdfError.ENCRYPTED, "Encrypted")
            if (trailer.containsKey("Prev") || trailer.containsKey("XRefStm")) refuse(TicketPdfError.UNSUPPORTED, "Incremental update or hybrid file")
            val size = (trailer["Size"] as? PdfNumber)?.intOrNull() ?: damaged("Trailer without /Size")
            if (size > TicketPdfLimits.MAX_OBJECTS + 1) refuse(TicketPdfError.TOO_MANY_OBJECTS, "Trailer /Size $size")
            if (trailer["Root"] !is PdfReference) damaged("Trailer without /Root")
            return PdfDocument(data, entries, trailer)
        }

        /** The subsections up to `trailer` (which is read too). */
        private fun readEntries(lexer: PdfLexer, fileSize: Int): Map<Int, XrefEntry> {
            val entries = HashMap<Int, XrefEntry>()
            var total = 0
            while (true) {
                val token = lexer.next() ?: damaged("Cross-reference table cut off")
                if (token == PdfToken.Keyword("trailer")) break
                val start = (token as? PdfToken.Number)?.integer() ?: damaged("Bad cross-reference subsection")
                val count = (lexer.next() as? PdfToken.Number)?.integer() ?: damaged("Bad cross-reference subsection")
                total += count
                if (total > TicketPdfLimits.MAX_OBJECTS + 1) refuse(TicketPdfError.TOO_MANY_OBJECTS, "More than ${TicketPdfLimits.MAX_OBJECTS} objects")
                for (i in 0 until count) {
                    val offset = (lexer.next() as? PdfToken.Number)?.integer() ?: damaged("Bad cross-reference entry")
                    val generation = (lexer.next() as? PdfToken.Number)?.integer() ?: damaged("Bad cross-reference entry")
                    val type = (lexer.next() as? PdfToken.Keyword)?.text
                    val number = start.toLong() + i
                    if (number > Int.MAX_VALUE || generation > 65_535) damaged("Cross-reference entry out of range")
                    when (type) {
                        "n" -> {
                            if (number == 0L || offset >= fileSize) damaged("Cross-reference offset outside the file")
                            if (entries.put(number.toInt(), XrefEntry(offset, generation)) != null) damaged("Object listed twice")
                        }
                        "f" -> Unit
                        else -> damaged("Bad cross-reference entry type")
                    }
                }
            }
            return entries
        }

        /** A PDF 1.5 cross-reference stream: encrypted or not, outside the reader's scope. */
        private fun refuseCrossReferenceStream(data: ByteArray, offset: Int): Nothing {
            val parser = PdfObjectParser(PdfLexer(data, offset), allowReferences = true)
            val head = runCatching { listOf(parser.next(), parser.next(), parser.next(), parser.next()) }.getOrNull()
            val dictionary = head?.getOrNull(3) as? PdfDictionary
            if (head?.getOrNull(2) == PdfKeyword("obj") && dictionary != null) {
                if (dictionary.containsKey("Encrypt")) refuse(TicketPdfError.ENCRYPTED, "Encrypted")
                if ((dictionary["Type"] as? PdfName)?.name == "XRef") refuse(TicketPdfError.UNSUPPORTED, "Cross-reference stream")
            }
            damaged("No cross-reference table at startxref")
        }

        private fun PdfToken.Number.integer(): Int? =
            if (isInteger && value >= 0 && value <= Int.MAX_VALUE) value.toInt() else null

        private fun startsWith(data: ByteArray, at: Int, prefix: ByteArray): Boolean =
            at + prefix.size <= data.size && prefix.indices.all { data[at + it] == prefix[it] }

        private fun lastIndexOf(data: ByteArray, needle: ByteArray, from: Int): Int? {
            var i = data.size - needle.size
            while (i >= from) {
                if (startsWith(data, i, needle)) return i
                i--
            }
            return null
        }
    }
}
