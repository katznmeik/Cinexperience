package io.github.katznmeik.cinexperience.feature.tickets.importing

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The app's file source for the import (content resolver, no persistable permission)
 * and the bounded read: a file is read up to its limit and a bigger one not further.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TicketFilesTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val source = ContentTicketFileSource(context)
    private val attachment = Uri.parse("content://com.example.mail.attachments/ticket/1")

    @Test
    fun aContentFileOfAnotherAppIsOpened() {
        val bytes = "%PDF-1.4 invented".toByteArray()
        shadowOf(context.contentResolver).registerInputStream(attachment, ByteArrayInputStream(bytes))
        assertArrayEquals(bytes, source.open(attachment.toString()).use { it.readBytes() })
    }

    @Test
    fun otherAddressesAreRefusedWithoutBeingOpened() {
        val own = context.packageName
        for (address in listOf("file:///sdcard/ticket.pdf", "https://www.yorck.de/x.pdf", "content://$own.fileprovider/photos/a.jpg", "content://0@$own/x")) {
            val uri = Uri.parse(address)
            shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(1)))
            assertThrows(address, SecurityException::class.java) { source.open(address) }
        }
    }

    @Test
    fun theReadStopsOneByteAfterTheLimit() {
        assertArrayEquals(ByteArray(10) { 7 }, TicketFileBytes.readAtMost(ByteArrayInputStream(ByteArray(10) { 7 }), limit = 10))
        assertArrayEquals(ByteArray(0), TicketFileBytes.readAtMost(ByteArrayInputStream(ByteArray(0)), limit = 10))
        val counting = CountingStream(size = 1_000_000)
        assertNull(TicketFileBytes.readAtMost(counting, limit = 200_000))
        assertEquals(200_001, counting.read)
    }

    @Test
    fun aStreamThatNeverDeliversIsBroken() {
        val stuck = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertThrows(IOException::class.java) { TicketFileBytes.readAtMost(stuck, limit = 10) }
    }

    private class CountingStream(private val size: Int) : InputStream() {
        var read = 0

        override fun read(): Int = if (read < size) 0.also { read++ } else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= size) return -1
            val count = minOf(len, size - read)
            read += count
            return count
        }
    }
}
