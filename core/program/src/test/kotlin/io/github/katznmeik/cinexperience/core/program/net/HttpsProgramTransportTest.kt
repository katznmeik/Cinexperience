package io.github.katznmeik.cinexperience.core.program.net

import io.github.katznmeik.cinexperience.core.program.ProgramError
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.util.Collections
import java.util.concurrent.Executors
import javax.net.ssl.SSLHandshakeException

/**
 * [HttpsProgramTransport] itself, over [FakeHttpsConnection] (no sockets): how it
 * configures the connection before the request goes out (spec §11.1 rules 2, 3
 * and 6), what it reads of the response, and that [TransportAbort] disconnects
 * it. The policy on top (redirect targets, size and total time) is tested in
 * [ProgramDownloaderTest]; the last tests here run it over this transport.
 */
class HttpsProgramTransportTest {

    private val programUrl: URL = ProgramDownloader.PROGRAM_URI.toURL()

    private val headers = mapOf("User-Agent" to ProgramDownloader.userAgent("1.1.0"), "Accept" to "text/html")

    /** The two headers as request properties: nothing else may be set. */
    private val expectedRequestProperties = mapOf(
        "User-Agent" to listOf("Cinexperience/1.1.0 (Android; personal app)"),
        "Accept" to listOf("text/html"),
    )

    /** Hands out [connections] in order, one per opened address, and records the addresses. */
    private class Opener(private vararg val connections: URLConnection) : (URL) -> URLConnection {
        val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun invoke(url: URL): URLConnection {
            opened += url.toString()
            return connections.getOrNull(opened.size - 1) ?: throw AssertionError("Unexpected request to $url")
        }
    }

    private fun connection(status: Int, location: String? = null, contentLength: Long = -1) =
        FakeHttpsConnection(programUrl, status, location?.let { mapOf("Location" to it) }.orEmpty(), contentLength)

    private fun FakeHttpsConnection.sentOrFail(): FakeHttpsConnection.Sent = sent ?: throw AssertionError("Nothing was sent")

    private fun get(connection: URLConnection, abort: TransportAbort = TransportAbort()): TransportResponse =
        HttpsProgramTransport(open = Opener(connection)).get(ProgramDownloader.PROGRAM_URI, headers, abort)

    // --- The request --------------------------------------------------------------------------

    @Test
    fun everyOptionIsSetBeforeTheRequestIsSent() {
        val connection = connection(200).apply { body = FakeHttpsConnection.Body("page".toByteArray()) }
        val opener = Opener(connection)
        HttpsProgramTransport(open = opener).get(ProgramDownloader.PROGRAM_URI, headers, TransportAbort()).close()

        assertEquals(listOf("https://www.yorck.de/en/films"), opener.opened)
        val sent = connection.sentOrFail()
        assertFalse("The client follows redirects itself", sent.followsRedirects)
        assertFalse("Caches are used", sent.usesCaches)
        assertFalse("A request body is allowed", sent.doesOutput)
        assertFalse("User interaction is allowed", sent.allowsUserInteraction)
        assertEquals("GET", sent.method)
        assertEquals(15_000, sent.connectTimeout)
        assertEquals(30_000, sent.readTimeout)
        assertEquals(expectedRequestProperties, sent.requestProperties)
    }

    @Test
    fun nothingIsOpenedForAnotherAddress() {
        val opener = Opener()
        val transport = HttpsProgramTransport(open = opener)
        for (uri in listOf("http://www.yorck.de/en/films", "https://example.com/en/films", "https://www.yorck.de:8443/", "https://a@www.yorck.de/")) {
            assertThrows(uri, IOException::class.java) { transport.get(URI(uri), headers, TransportAbort()) }
        }
        assertEquals(emptyList<String>(), opener.opened)
    }

    @Test
    fun nothingIsOpenedWhileACookieHandlerIsInstalled() {
        val opener = Opener(connection(200))
        val previous = CookieHandler.getDefault()
        CookieHandler.setDefault(CookieManager())
        try {
            assertThrows(IOException::class.java) {
                HttpsProgramTransport(open = opener).get(ProgramDownloader.PROGRAM_URI, headers, TransportAbort())
            }
        } finally {
            CookieHandler.setDefault(previous)
        }
        assertEquals(emptyList<String>(), opener.opened)
    }

    @Test
    fun aConnectionThatIsNotHttpsIsRefusedBeforeAnythingIsSent() {
        var used = false
        val plain = object : HttpURLConnection(programUrl) {
            override fun connect() {
                used = true
            }

            override fun getResponseCode(): Int {
                used = true
                return 200
            }

            override fun disconnect() = Unit

            override fun usingProxy(): Boolean = false
        }
        assertThrows(IOException::class.java) { get(plain) }
        assertFalse(used)
    }

    // --- The response -------------------------------------------------------------------------

    @Test
    fun aPageIsHandedOverUnreadAsItStreams() {
        val body = FakeHttpsConnection.Body("<html>program</html>".toByteArray())
        val connection = connection(200, contentLength = 20).apply { this.body = body }
        val response = get(connection)

        assertEquals(200, response.status)
        assertEquals(20L, response.contentLength)
        assertNull(response.location)
        assertSame(body, response.body) // not buffered: the downloader counts the bytes as it reads
        assertEquals(0L, body.read)
        assertFalse(connection.errorStreamOpened)
        assertEquals("<html>program</html>", response.body.readBytes().toString(Charsets.UTF_8))
        assertEquals(0, connection.disconnects.get())

        response.close()
        assertTrue(body.closed)
        assertEquals(1, connection.disconnects.get())
    }

    @Test
    fun aRedirectIsReturnedAndNotFollowed() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val connection = connection(status, location = "https://example.com/en/films", contentLength = 120)
            val opener = Opener(connection)
            val response = HttpsProgramTransport(open = opener).get(ProgramDownloader.PROGRAM_URI, headers, TransportAbort())

            assertEquals(status, response.status)
            assertEquals("https://example.com/en/films", response.location)
            assertEquals(-1, response.body.read())
            response.close()
            assertEquals(1, opener.opened.size)
            assertFalse("$status: the body was opened", connection.inputStreamOpened)
            assertFalse("The client follows redirects itself", connection.sentOrFail().followsRedirects)
            assertEquals(1, connection.disconnects.get())
        }
    }

    @Test
    fun theBodyOfAnyOtherStatusIsNeverRead() {
        for (status in listOf(204, 304, 400, 403, 404, 429, 500, 503)) {
            val connection = connection(status, contentLength = 18)
            val errorBody = connection.errorBody as FakeHttpsConnection.Body?
            val response = get(connection)

            assertEquals(status, response.status)
            assertEquals(-1, response.body.read())
            response.close()
            assertFalse("$status: the body was opened", connection.inputStreamOpened)
            // An error body (400 and above) is only closed, never read.
            if (errorBody != null) {
                assertTrue(errorBody.closed)
                assertEquals(0L, errorBody.read)
            }
            assertEquals(1, connection.disconnects.get())
        }
    }

    @Test
    fun aFailedRequestIsDisconnectedAndItsErrorPassedOn() {
        val failure = SSLHandshakeException("bad certificate")
        val connection = connection(200).apply { onSend = { throw failure } }
        assertSame(failure, assertThrows(SSLHandshakeException::class.java) { get(connection) })
        assertFalse(connection.inputStreamOpened)
        assertEquals(1, connection.disconnects.get())
    }

    // --- Abort ----------------------------------------------------------------------------------

    @Test
    fun anAbortWhileConnectingDisconnects() {
        val abort = TransportAbort()
        var disconnectsWhenAborted = -1
        val connection = connection(200)
        connection.onSend = {
            abort.abort() // the downloader's watchdog, from another thread while the connect blocks
            disconnectsWhenAborted = connection.disconnects.get()
            throw SocketException("Socket closed")
        }
        assertThrows(SocketException::class.java) { get(connection, abort) }
        assertEquals(1, disconnectsWhenAborted)
    }

    @Test
    fun anAbortWhileTheBodyIsReadDisconnects() {
        val abort = TransportAbort()
        val connection = connection(200)
        val response = get(connection, abort)
        assertEquals(0, connection.disconnects.get())

        abort.abort()
        assertEquals(1, connection.disconnects.get())
        response.close()
    }

    @Test
    fun anAlreadyAbortedRequestIsNeverSent() {
        val abort = TransportAbort().apply { abort() }
        val connection = connection(200)
        assertThrows(IOException::class.java) { get(connection, abort) }
        assertNull(connection.sent)
        assertTrue(connection.disconnects.get() >= 1)
    }

    // --- The download policy over this transport ------------------------------------------------

    private fun downloader(opener: Opener, maxBytes: Long = ProgramDownloader.MAX_BYTES, totalTimeoutMs: Long = ProgramDownloader.TOTAL_TIMEOUT_MS) =
        ProgramDownloader(HttpsProgramTransport(open = opener), "1.1.0", maxBytes, totalTimeoutMs)

    private fun DownloadResult.error(): ProgramError = (this as? DownloadResult.Failure)?.error ?: throw AssertionError("Expected a failure, got $this")

    @Test
    fun theDownloaderSendsExactlyItsTwoHeaders() = runTest {
        val connection = connection(200).apply { body = FakeHttpsConnection.Body("<html>program</html>".toByteArray()) }
        val opener = Opener(connection)
        val result = downloader(opener).download { true }

        assertEquals("<html>program</html>", (result as DownloadResult.Success).page)
        assertEquals(listOf("https://www.yorck.de/en/films"), opener.opened)
        assertEquals(expectedRequestProperties, connection.sentOrFail().requestProperties)
        assertEquals(1, connection.disconnects.get())
    }

    @Test
    fun aRedirectAwayFromYorckIsRefusedWithoutASecondRequest() = runTest {
        val redirect = connection(302, location = "https://example.com/en/films")
        val opener = Opener(redirect)
        assertEquals(ProgramError.REDIRECT_REFUSED, downloader(opener).download { true }.error())
        assertEquals(listOf("https://www.yorck.de/en/films"), opener.opened)
        assertFalse(redirect.inputStreamOpened)
        assertEquals(1, redirect.disconnects.get())
    }

    @Test
    fun aRedirectOnYorckIsANewRequestWithTheSameOptions() = runTest {
        val redirect = connection(301, location = "/en/films/")
        val page = FakeHttpsConnection(URI("https://www.yorck.de/en/films/").toURL(), 200).apply {
            body = FakeHttpsConnection.Body("page".toByteArray())
        }
        val opener = Opener(redirect, page)
        assertEquals("page", (downloader(opener).download { true } as DownloadResult.Success).page)

        assertEquals(listOf("https://www.yorck.de/en/films", "https://www.yorck.de/en/films/"), opener.opened)
        for (connection in listOf(redirect, page)) {
            val sent = connection.sentOrFail()
            assertFalse("The client follows redirects itself", sent.followsRedirects)
            assertEquals(expectedRequestProperties, sent.requestProperties)
            assertEquals(1, connection.disconnects.get())
        }
        assertFalse(redirect.inputStreamOpened)
    }

    @Test
    fun theSizeLimitIsCountedWhileStreamingFromTheConnection() = runTest {
        // Unknown length, or a length that lies.
        for (declared in listOf(-1L, 10L)) {
            val body = FakeHttpsConnection.Body(ByteArray(1_000_000) { 'a'.code.toByte() })
            val connection = connection(200, contentLength = declared).apply { this.body = body }
            assertEquals(ProgramError.TOO_LARGE, downloader(Opener(connection), maxBytes = 1_000).download { true }.error())
            assertEquals(1_001L, body.read) // never more than one byte past the limit
            assertTrue(body.closed)
            assertEquals(1, connection.disconnects.get())
        }
    }

    @Test
    fun aDeclaredLengthAboveTheLimitIsRefusedWithoutReading() = runTest {
        val body = FakeHttpsConnection.Body(ByteArray(2_000))
        val connection = connection(200, contentLength = 1_001).apply { this.body = body }
        assertEquals(ProgramError.TOO_LARGE, downloader(Opener(connection), maxBytes = 1_000).download { true }.error())
        assertEquals(0L, body.read)
        assertEquals(1, connection.disconnects.get())
    }

    // Real threads: the fake connection blocks like a stalled socket until it is disconnected.

    private fun <T> onRealThreads(block: suspend () -> T): T {
        val executor = Executors.newFixedThreadPool(3)
        try {
            return runBlocking(executor.asCoroutineDispatcher()) { withTimeout(15_000) { block() } }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun theTotalTimeoutDisconnectsAStalledConnect() {
        val connection = connection(200)
        connection.onSend = { connection.stallUntilDisconnected() }
        val result = onRealThreads { downloader(Opener(connection), totalTimeoutMs = 300).download { true } }
        assertEquals(ProgramError.NETWORK, result.error())
        assertTrue("The stalled connect was not ended by disconnect()", connection.endedByDisconnect)
    }

    @Test
    fun theTotalTimeoutDisconnectsAStalledRead() {
        val connection = connection(200)
        connection.body = connection.StallingBody()
        val result = onRealThreads { downloader(Opener(connection), totalTimeoutMs = 300).download { true } }
        assertEquals(ProgramError.NETWORK, result.error())
        assertTrue("The stalled read was not ended by disconnect()", connection.endedByDisconnect)
    }
}
