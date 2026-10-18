package io.github.katznmeik.cinexperience.feature.tickets.importing

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.ticket.QrModuleGrid
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfContent
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfReader
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfResult
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Opens the ticket file the import was given (spec §6.10). Blocking: called off the
 * main thread. Throws (e.g. [SecurityException], [IOException]) when the file can't
 * be opened; the import turns that into a message. The import reads the stream only
 * up to its size limit and keeps the bytes in memory only while it reads them.
 */
fun interface TicketFileSource {
    fun open(uri: String): InputStream
}

/**
 * The app's [TicketFileSource]: a `content:` URI through the content resolver, with
 * the read permission the sending app or the file picker granted. Takes no
 * persistable permission. Refuses what [TicketUris] refuses, and any authority that
 * Android resolves to one of this app's own providers.
 */
class ContentTicketFileSource(context: Context) : TicketFileSource {
    private val appContext = context.applicationContext

    override fun open(uri: String): InputStream {
        val parsed = uri.toUri()
        if (!TicketUris.isReadable(parsed, appContext.packageName) || isOwnProvider(parsed)) {
            throw SecurityException("The import reads only files of other apps")
        }
        return appContext.contentResolver.openInputStream(parsed) ?: throw FileNotFoundException("No file")
    }

    private fun isOwnProvider(uri: Uri): Boolean {
        val authority = TicketUris.providerAuthority(uri) ?: return true
        return try {
            // The int flags: the same lookup as the ComponentInfoFlags overload, and the one Robolectric models.
            @Suppress("DEPRECATION")
            appContext.packageManager.resolveContentProvider(authority, 0)?.packageName == appContext.packageName
        } catch (e: RuntimeException) {
            true // can't tell: refused
        }
    }
}

/** What a [TicketReader] made of a file. */
sealed interface TicketReadResult {
    /**
     * A ticket: its [content] and its QR code as the model's [qrModules] (`null` if
     * none was found). The import uses [qrModules], not `content.qrCode`.
     */
    data class Read(val content: TicketPdfContent, val qrModules: QrModules?) : TicketReadResult

    /** Not readable: nothing from the file may be used. */
    data class Refused(val error: TicketPdfError) : TicketReadResult
}

/** Reads a ticket PDF's bytes ([DefaultTicketReader] in the app, a fake in tests). Never throws for any content. */
fun interface TicketReader {
    fun read(bytes: ByteArray): TicketReadResult
}

/** The app's [TicketReader]: core:ticket's own reader ([TicketPdfReader]), with Android's JPEG decoder for the QR code. */
val DefaultTicketReader: TicketReader = TicketReader { bytes -> TicketPdfReader().read(bytes).toReadResult() }

/** [TicketPdfResult] as the import's [TicketReadResult]: the checked QR grid becomes the model's [QrModules]. */
internal fun TicketPdfResult.toReadResult(): TicketReadResult = when (this) {
    is TicketPdfResult.Read -> TicketReadResult.Read(content, content.qrCode.valueOrNull?.toQrModulesOrNull())
    is TicketPdfResult.Refused -> TicketReadResult.Refused(error)
}

/** The grid as the model's [QrModules] (which accepts every grid the reader creates); `null`, never a refused ticket, otherwise. */
private fun QrModuleGrid.toQrModulesOrNull(): QrModules? = try {
    QrModules(size, toBooleanArray())
} catch (e: IllegalArgumentException) {
    null
}

internal object TicketFileBytes {
    private const val BUFFER_SIZE = 64 * 1024

    /** Reads that return nothing in a row before the stream counts as broken (a well-behaved stream blocks instead). */
    private const val MAX_EMPTY_READS = 1_000

    /**
     * All bytes of [input] if there are at most [limit]; `null` if there are more. It
     * stops after [limit] + 1 bytes: a bigger file is never read on. Doesn't close [input].
     */
    fun readAtMost(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(limit + 1, BUFFER_SIZE))
        val buffer = ByteArray(BUFFER_SIZE)
        var emptyReads = 0
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (count < 0) break
            if (count == 0 && ++emptyReads > MAX_EMPTY_READS) throw IOException("The stream returns no data")
            out.write(buffer, 0, count)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }
}
