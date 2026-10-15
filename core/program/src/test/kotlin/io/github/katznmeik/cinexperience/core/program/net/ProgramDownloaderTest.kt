package io.github.katznmeik.cinexperience.core.program.net

import io.github.katznmeik.cinexperience.core.program.ProgramError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/** The download policy, tested with [FakeTransport]: no sockets. */
class ProgramDownloaderTest {

    private fun downloader(
        transport: FakeTransport,
        maxBytes: Long = ProgramDownloader.MAX_BYTES,
        totalTimeoutMs: Long = ProgramDownloader.TOTAL_TIMEOUT_MS,
    ) = ProgramDownloader(transport, "1.1.0", maxBytes, totalTimeoutMs)

    private suspend fun ProgramDownloader.get(isAllowed: () -> Boolean = { true }) = download(isAllowed)

    private fun DownloadResult.page(): String = (this as? DownloadResult.Success)?.page ?: throw AssertionError("Expected a page, got $this")

    private fun DownloadResult.error(): ProgramError = (this as? DownloadResult.Failure)?.error ?: throw AssertionError("Expected a failure, got $this")

    // --- The one request ------------------------------------------------------------------------

    @Test
    fun sendsExactlyTheOneFixedRequest() = runTest {
        val transport = FakeTransport().then { ok("<html>program</html>") }
        assertEquals("<html>program</html>", downloader(transport).get().page())

        val request = transport.requests.single()
        assertEquals(URI("https://www.yorck.de/en/films"), request.uri)
        assertNull(request.uri.rawQuery)
        assertEquals(
            mapOf("User-Agent" to "Cinexperience/1.1.0 (Android; personal app)", "Accept" to "text/html"),
            request.headers,
        )
        assertEquals(1, transport.closed)
    }

    @Test
    fun theUserAgentOnlyCarriesAPlainVersionName() {
        assertEquals("Cinexperience/1.0.0 (Android; personal app)", ProgramDownloader.userAgent("1.0.0"))
        assertEquals("Cinexperience/1.1.0-beta+2 (Android; personal app)", ProgramDownloader.userAgent("1.1.0-beta+2"))
        for (odd in listOf("", "1.0\r\nCookie: a=b", "1 0", "x".repeat(33), "ü")) {
            assertEquals("Cinexperience/unknown (Android; personal app)", ProgramDownloader.userAgent(odd))
        }
    }

    @Test
    fun theBodyIsDecodedAsUtf8() = runTest {
        val bytes = "Café ".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "🎬".toByteArray()
        val page = downloader(FakeTransport().then { ok(bytes) }).get().page()
        assertEquals("Café �(🎬", page)
    }

    // --- Gate -----------------------------------------------------------------------------------

    @Test
    fun nothingIsSentWithoutPermission() = runTest {
        val transport = FakeTransport().then { ok("x") }
        assertEquals(DownloadResult.NotAllowed, downloader(transport).get(isAllowed = { false }))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun theGateIsAskedAgainBeforeEveryRedirect() = runTest {
        var allowed = true
        val transport = FakeTransport().then { response(302, "/en/films/") }.then { ok("x") }
        transport.onRequest = { allowed = false } // switched off while the first request runs
        assertEquals(DownloadResult.NotAllowed, downloader(transport).get(isAllowed = { allowed }))
        assertEquals(1, transport.requests.size)
        assertEquals(1, transport.closed)
    }

    // --- Status codes ---------------------------------------------------------------------------

    @Test
    fun onlyStatus200IsAccepted() = runTest {
        for (status in listOf(-1, 100, 201, 204, 206, 300, 304, 305, 400, 403, 404, 429, 500, 503)) {
            val body = FakeTransport.CountingBody(100)
            val transport = FakeTransport().then { response(status, location = "/en/films", body = body) }
            assertEquals("$status", ProgramError.SERVER, downloader(transport).get().error())
            assertEquals("$status", 1, transport.requests.size)
            assertEquals(0L, body.read)
            assertEquals(1, transport.closed)
        }
    }

    // --- Redirects ------------------------------------------------------------------------------

    @Test
    fun redirectsWithinYorckAreFollowedUpToThree() = runTest {
        val transport = FakeTransport()
            .then { response(301, "https://www.yorck.de/en/films/") }
            .then { response(302, "/en/films?lang=en") }
            .then { response(307, "films#top") }
            .then { ok("page") }
        assertEquals("page", downloader(transport).get().page())
        assertEquals(
            listOf("https://www.yorck.de/en/films", "https://www.yorck.de/en/films/", "https://www.yorck.de/en/films?lang=en", "https://www.yorck.de/en/films"),
            transport.requests.map { it.uri.toString() },
        )
        assertEquals(4, transport.closed)
    }

    @Test
    fun aFourthRedirectIsRefused() = runTest {
        val transport = FakeTransport().then { response(308, "/en/films") }
        assertEquals(ProgramError.REDIRECT_REFUSED, downloader(transport).get().error())
        assertEquals(1 + ProgramDownloader.MAX_REDIRECTS, transport.requests.size)
    }

    @Test
    fun redirectsAwayFromYorckAreRefusedWithoutAnotherRequest() = runTest {
        val refused = listOf(
            null,
            "",
            "http://www.yorck.de/en/films",
            "https://yorck.de/en/films",
            "https://www.yorck.de.evil.example/en/films",
            "https://evil.example/www.yorck.de/en/films",
            "https://www.yorck.de@evil.example/",
            "https://user:pass@www.yorck.de/en/films",
            "https://www.yorck.de:8443/en/films",
            "https://www.yorck.de./en/films",
            "//evil.example/en/films",
            "ftp://www.yorck.de/en/films",
            "javascript:alert(1)",
            "file:///data/data/x",
            "data:text/html,hi",
            "https://www.yorck.de\\@evil.example/",
            "https://www.yorck.de/en/films\r\nSet-Cookie: a=b",
            "https://www.yorck.de/en/ films",
            "https://www.yоrck.de/en/films", // Cyrillic о
            "/" + "a".repeat(3000),
        )
        for (location in refused) {
            val transport = FakeTransport().then { response(302, location) }.then { ok("never") }
            assertEquals("$location", ProgramError.REDIRECT_REFUSED, downloader(transport).get().error())
            assertEquals("$location", 1, transport.requests.size)
        }
    }

    @Test
    fun redirectTargetsAreRebuiltFromTheirCheckedParts() {
        val from = ProgramDownloader.PROGRAM_URI
        assertEquals(URI("https://www.yorck.de/en/films/"), ProgramDownloader.resolveRedirect(from, "https://WWW.YORCK.DE:443/en/films/#x"))
        assertEquals(URI("https://www.yorck.de/"), ProgramDownloader.resolveRedirect(from, "https://www.yorck.de"))
        assertEquals(URI("https://www.yorck.de/de/programm"), ProgramDownloader.resolveRedirect(from, "../de/programm"))
        assertEquals(URI("https://www.yorck.de/en/films?a=%20b"), ProgramDownloader.resolveRedirect(from, "/en/films?a=%20b"))
        // A query-only location (java.net.URI resolves it RFC 2396 style) stays on yorck.de too.
        assertEquals("www.yorck.de", ProgramDownloader.resolveRedirect(from, "?a=1")!!.host)
    }

    @Test
    fun theTransportItselfOnlyConnectsToYorckWithoutCookieHandler() {
        HttpsProgramTransport.checkAllowed(ProgramDownloader.PROGRAM_URI, cookieHandlerInstalled = false)
        for (uri in listOf("http://www.yorck.de/en/films", "https://example.com/", "https://www.yorck.de:8443/", "https://a@www.yorck.de/")) {
            assertThrowsIo { HttpsProgramTransport.checkAllowed(URI(uri), cookieHandlerInstalled = false) }
        }
        assertThrowsIo { HttpsProgramTransport.checkAllowed(ProgramDownloader.PROGRAM_URI, cookieHandlerInstalled = true) }
    }

    private fun assertThrowsIo(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            return
        }
        throw AssertionError("Expected an IOException")
    }

    // --- Size and time limits -------------------------------------------------------------------

    @Test
    fun aDeclaredLengthAboveTheLimitIsRefusedWithoutReading() = runTest {
        val body = FakeTransport.CountingBody(2_000)
        val transport = FakeTransport().then { response(200, body = body, contentLength = 1_001) }
        assertEquals(ProgramError.TOO_LARGE, downloader(transport, maxBytes = 1_000).get().error())
        assertEquals(0L, body.read)
        assertEquals(1, transport.closed)
    }

    @Test
    fun aBodyAboveTheLimitIsAbortedWhileStreaming() = runTest {
        // Unknown length, or a length that lies.
        for (declared in listOf(-1L, 10L)) {
            val body = FakeTransport.CountingBody(1_000_000)
            val transport = FakeTransport().then { response(200, body = body, contentLength = declared) }
            assertEquals(ProgramError.TOO_LARGE, downloader(transport, maxBytes = 1_000).get().error())
            assertEquals(1_001L, body.read) // never more than one byte past the limit
            assertEquals(1, transport.closed)
        }
    }

    @Test
    fun aBodyAtTheLimitIsAccepted() = runTest {
        val transport = FakeTransport().then { response(200, body = FakeTransport.CountingBody(1_000), contentLength = -1) }
        assertEquals(1_000, downloader(transport, maxBytes = 1_000).get().page().length)
    }

    @Test
    fun theRealLimitIsTenMegabytes() {
        assertEquals(10L * 1024 * 1024, ProgramDownloader.MAX_BYTES)
        assertEquals(15_000, ProgramDownloader.CONNECT_TIMEOUT_MS)
        assertEquals(30_000, ProgramDownloader.READ_TIMEOUT_MS)
    }

    @Test
    fun theRealTotalTimeoutIsNinetySeconds() {
        assertEquals(90_000L, ProgramDownloader.TOTAL_TIMEOUT_MS)
    }

    // --- Watchdog: real threads, because the fake transport blocks like a stalled socket ---------

    private fun <T> onRealThreads(block: suspend CoroutineScope.() -> T): T {
        val executor = Executors.newFixedThreadPool(3)
        try {
            return runBlocking(executor.asCoroutineDispatcher()) { withTimeout(15_000) { block() } }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun theTotalTimeoutAbortsAStalledConnect() = onRealThreads {
        val transport = FakeTransport().then { abort -> blockUntilAborted(abort) }
        val started = System.nanoTime()
        val result = downloader(transport, totalTimeoutMs = 300).get()
        assertEquals(ProgramError.NETWORK, result.error())
        assertEquals(1, transport.aborted)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000) // not a socket timeout
    }

    @Test
    fun theTotalTimeoutAbortsAStalledRead() = onRealThreads {
        val transport = FakeTransport().then { abort -> response(200, body = FakeTransport.StallingBody(abort)) }
        assertEquals(ProgramError.NETWORK, downloader(transport, totalTimeoutMs = 300).get().error())
        assertEquals(1, transport.closed)
    }

    @Test
    fun cancellingTheDownloadAbortsTheBlockedCallAtOnce() = onRealThreads {
        val transport = FakeTransport().then { abort -> blockUntilAborted(abort) }
        val download = async { downloader(transport).get() } // 90 s timeout: only the cancel can end it
        assertTrue(transport.blocking.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        download.cancel()
        download.join()
        assertTrue(download.isCancelled)
        assertEquals(1, transport.aborted)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun aFinishedDownloadIsNotAborted() = runTest {
        var abortHandle: TransportAbort? = null
        val transport = FakeTransport().then { abort ->
            abortHandle = abort
            ok("page")
        }
        assertEquals("page", downloader(transport).get().page())
        assertFalse(abortHandle!!.isAborted)
    }

    // --- Failures never throw -------------------------------------------------------------------

    @Test
    fun networkFailuresBecomeErrors() = runTest {
        val cases = mapOf<Exception, ProgramError>(
            UnknownHostException("www.yorck.de") to ProgramError.OFFLINE,
            ConnectException("refused") to ProgramError.OFFLINE,
            SocketTimeoutException("timeout") to ProgramError.NETWORK,
            SSLHandshakeException("bad certificate") to ProgramError.NETWORK,
            IOException("reset") to ProgramError.NETWORK,
            SecurityException("no INTERNET permission") to ProgramError.NETWORK,
            IllegalStateException("odd") to ProgramError.NETWORK,
        )
        for ((exception, error) in cases) {
            val transport = FakeTransport().then { throw exception }
            assertEquals("$exception", error, downloader(transport).get().error())
        }
    }

    @Test
    fun aBrokenBodyBecomesAnError() = runTest {
        val transport = FakeTransport().then { response(200, body = FakeTransport.FailingBody()) }
        assertEquals(ProgramError.NETWORK, downloader(transport).get().error())
        assertEquals(1, transport.closed)
    }
}
