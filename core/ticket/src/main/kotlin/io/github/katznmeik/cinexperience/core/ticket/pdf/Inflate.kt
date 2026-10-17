package io.github.katznmeik.cinexperience.core.ticket.pdf

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** FlateDecode (zlib) with a hard cap on the output, counted while inflating. */
internal object Inflate {
    private const val CHUNK = 64 * 1024

    /**
     * Inflates `data[offset, offset + length)`. More than [maxOutput] bytes is
     * [TicketPdfError.STREAM_TOO_LARGE]; corrupt or cut-off data is
     * [TicketPdfError.DAMAGED]. Bytes after the end of the zlib data are ignored.
     */
    fun inflate(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data, offset, length)
            val out = ByteArrayOutputStream(minOf(maxOutput, maxOf(CHUNK, length)) + 1)
            val buffer = ByteArray(CHUNK)
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    damaged("Corrupt compressed stream")
                }
                if (count == 0) {
                    // Not finished, yet no output: the data ends early or needs a preset dictionary.
                    damaged("Compressed stream cut off")
                }
                if (out.size().toLong() + count > maxOutput) {
                    refuse(TicketPdfError.STREAM_TOO_LARGE, "Stream unpacks to more than $maxOutput bytes")
                }
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
