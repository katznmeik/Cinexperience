package io.github.katznmeik.cinexperience.core.ticket.testing

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater

/**
 * Writes small PDF files object by object, in the shape of Yorck's tickets (Qt 5.5's
 * PDF engine): a `%PDF-1.4` header, numbered objects, streams with an indirect
 * `/Length`, a classic cross-reference table with 20-byte entries, one trailer.
 * Test code only; every value in it is invented.
 */
internal class PdfBuilder {
    private val bodies = ArrayList<ByteArray?>()

    /** A new object number, to be [set] later (for objects that refer to each other). */
    fun reserve(): Int {
        bodies += null
        return bodies.size
    }

    fun set(number: Int, body: String) {
        bodies[number - 1] = body.toByteArray(Charsets.ISO_8859_1)
    }

    fun add(body: String): Int = reserve().also { set(it, body) }

    /**
     * A stream object with [dictionary] entries (without `<<`, `>>` and `/Length`) and
     * [data] as stored; the length goes into its own object, as Qt writes it, unless
     * [directLength].
     */
    fun addStream(dictionary: String, data: ByteArray, directLength: Boolean = false): Int {
        val number = reserve()
        val length = if (directLength) "${data.size}" else "${add("${data.size}")} 0 R"
        setRaw(number, "<< $dictionary /Length $length >>\nstream\n".latin1() + data + "\nendstream".latin1())
        return number
    }

    /** A FlateDecode stream of [data]. */
    fun addFlateStream(dictionary: String, data: ByteArray): Int = addStream("$dictionary /Filter /FlateDecode", deflate(data))

    fun setRaw(number: Int, body: ByteArray) {
        bodies[number - 1] = body
    }

    /**
     * The file. [trailer] holds extra trailer entries; [beforeXref] is written between
     * the last object and the table (e.g. junk); [mutateTable] may rewrite the table.
     */
    fun build(
        root: Int,
        info: Int? = null,
        trailer: String = "",
        header: String = "%PDF-1.4",
        mutateTable: (String) -> String = { it },
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("$header\n".latin1())
        val offsets = IntArray(bodies.size)
        bodies.forEachIndexed { index, body ->
            offsets[index] = out.size()
            out.write("${index + 1} 0 obj\n".latin1())
            out.write(body ?: error("Object ${index + 1} was reserved but never set"))
            out.write("\nendobj\n".latin1())
        }
        val xref = out.size()
        val table = buildString {
            append("xref\n0 ${bodies.size + 1}\n")
            append("0000000000 65535 f \n")
            offsets.forEach { append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        }
        out.write(mutateTable(table).latin1())
        val infoEntry = info?.let { "/Info $it 0 R\n" }.orEmpty()
        out.write("trailer\n<<\n/Size ${bodies.size + 1}\n$infoEntry/Root $root 0 R\n$trailer>>\nstartxref\n$xref\n%%EOF\n".latin1())
        return out.toByteArray()
    }

    companion object {
        fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater()
            try {
                deflater.setInput(data)
                deflater.finish()
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        fun String.latin1(): ByteArray = toByteArray(Charsets.ISO_8859_1)

        /** A one-page document whose page has [content] (uncompressed) and [resources]. */
        fun onePage(content: String, resources: String = "<< >>", configure: PdfBuilder.() -> Unit = {}): ByteArray {
            val builder = PdfBuilder()
            val catalog = builder.reserve()
            val pages = builder.reserve()
            val page = builder.reserve()
            builder.configure()
            val contents = builder.addStream("", content.latin1())
            builder.set(catalog, "<< /Type /Catalog /Pages $pages 0 R >>")
            builder.set(pages, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
            builder.set(page, "<< /Type /Page /Parent $pages 0 R /Contents $contents 0 R /Resources $resources /MediaBox [0 0 842 1191] >>")
            return builder.build(catalog)
        }
    }
}
