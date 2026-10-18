package io.github.katznmeik.cinexperience.feature.tickets.importing

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import io.github.katznmeik.cinexperience.feature.tickets.ImportTicketRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which intents start the ticket import (spec §6.10) and which addresses it reads:
 * only one `content:` file of another app; everything else leads to the "can't open"
 * message. The launcher's intent and one relaunched from the recent apps import nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TicketIntentsTest {

    private val own = "io.github.katznmeik.cinexperience"
    private val mail = Uri.parse("content://com.example.mail.attachments/ticket/1")

    private fun share(stream: Uri?) = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        if (stream != null) putExtra(Intent.EXTRA_STREAM, stream)
    }

    private fun view(data: Uri) = Intent(Intent.ACTION_VIEW).setDataAndType(data, "application/pdf")

    private fun route(intent: Intent?) = TicketIntents.importRoute(intent, own)

    @Test
    fun shareAndOpenWithImportTheFile() {
        assertEquals(ImportTicketRoute(mail.toString()), route(share(mail)))
        assertEquals(ImportTicketRoute(mail.toString()), route(view(mail)))
    }

    @Test
    fun otherIntentsAreNoImport() {
        assertNull(route(null))
        assertNull(route(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertNull(route(Intent(Intent.ACTION_EDIT, mail)))
    }

    @Test
    fun anIntentRelaunchedFromTheRecentAppsIsNotImportedAgain() {
        assertNull(route(share(mail).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
    }

    @Test
    fun aShareWithoutOneFileCantBeRead() {
        val unreadable = ImportTicketRoute(null)
        assertEquals("no stream", unreadable, route(share(null)))
        val two = share(mail).apply {
            clipData = ClipData.newRawUri("tickets", mail).apply { addItem(ClipData.Item(Uri.parse("content://com.example.mail.attachments/ticket/2"))) }
        }
        assertEquals("two files", unreadable, route(two))
        assertEquals("several files", unreadable, route(Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/pdf")))
        val wrongExtra = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, mail.toString())
        assertEquals("a text instead of a URI", unreadable, route(wrongExtra))
    }

    @Test
    fun onlyContentAddressesOfOtherAppsAreRead() {
        val unreadable = ImportTicketRoute(null)
        for (address in listOf(
            "file:///sdcard/Download/ticket.pdf",
            "https://www.yorck.de/ticket.pdf",
            "http://example.com/ticket.pdf",
            "CONTENT://com.example.mail.attachments/ticket/1",
            "content:///ticket.pdf",
            "content:ticket.pdf",
            "content://$own.fileprovider/photos/visit.jpg",
            "content://$own/anything",
            "content://IO.GITHUB.KATZNMEIK.CINEXPERIENCE.fileprovider/x",
            "content://0@$own.fileprovider/photos/visit.jpg",
        )) {
            assertEquals(address, unreadable, route(share(Uri.parse(address))))
            assertEquals(address, unreadable, route(view(Uri.parse(address))))
        }
    }

    @Test
    fun theUriRuleItself() {
        assertTrue(TicketUris.isReadable(mail, own))
        assertTrue("another app whose name merely contains ours", TicketUris.isReadable(Uri.parse("content://com.other.$own/x"), own))
        assertFalse(TicketUris.isReadable(Uri.parse("content://$own.androidx-startup/x"), own))
        assertEquals("com.example.mail.attachments", TicketUris.providerAuthority(Uri.parse("content://10@com.example.mail.attachments/x")))
    }
}
