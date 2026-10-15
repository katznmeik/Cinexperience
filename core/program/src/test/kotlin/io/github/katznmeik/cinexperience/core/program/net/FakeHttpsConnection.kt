package io.github.katznmeik.cinexperience.core.program.net

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * An [HttpsURLConnection] without sockets, for [HttpsProgramTransportTest]. It
 * answers with [status], [responseHeaders] and [body], and records:
 *
 * - [sent]: its configuration at the moment the request would go out (the first
 *   call that needs the response, like a real connection's implicit connect);
 *   afterwards the options can't be changed any more ("Already connected");
 * - which streams were opened, and how often it was disconnected.
 *
 * It starts from the worst defaults a platform could have (redirects followed,
 * caches, output and user interaction on, `POST`, no timeouts), so a test only
 * passes if the transport sets every option itself.
 */
internal class FakeHttpsConnection(
    address: URL,
    private val status: Int,
    private val responseHeaders: Map<String, String> = emptyMap(),
    private val contentLength: Long = -1,
) : HttpsURLConnection(address) {

    /** The connection's configuration when the request was sent. */
    data class Sent(
        val method: String,
        val followsRedirects: Boolean,
        val usesCaches: Boolean,
        val doesOutput: Boolean,
        val allowsUserInteraction: Boolean,
        val connectTimeout: Int,
        val readTimeout: Int,
        val requestProperties: Map<String, List<String>>,
    )

    /** `null` while nothing was sent. */
    @Volatile
    var sent: Sent? = null
        private set

    /** What [getInputStream] returns (status 200 and redirects). */
    var body: InputStream = Body(ByteArray(0))

    /** What [getErrorStream] returns: as on Android, a body only for status 400 and above. */
    var errorBody: InputStream? = if (status >= 400) Body("<html>Error</html>".toByteArray()) else null

    /** Runs when the request is sent, before any answer: e.g. fail like a socket or stall. */
    var onSend: () -> Unit = {}

    val disconnects = AtomicInteger()

    @Volatile
    var inputStreamOpened = false
        private set

    @Volatile
    var errorStreamOpened = false
        private set

    /** True if a stalled connect or read ([stallUntilDisconnected]) was ended by [disconnect]. */
    @Volatile
    var endedByDisconnect = false
        private set

    private val disconnected = CountDownLatch(1)

    init {
        instanceFollowRedirects = true
        useCaches = true
        doOutput = true
        allowUserInteraction = true
        requestMethod = "POST"
        connectTimeout = 0
        readTimeout = 0
    }

    private fun send() {
        if (sent != null) return
        sent = Sent(
            method = requestMethod,
            followsRedirects = instanceFollowRedirects,
            usesCaches = useCaches,
            doesOutput = doOutput,
            allowsUserInteraction = allowUserInteraction,
            connectTimeout = connectTimeout,
            readTimeout = readTimeout,
            requestProperties = requestProperties, // throws once connected
        )
        connected = true
        onSend()
    }

    /** Blocks like a stalled socket until [disconnect] (at most 10 s), then fails like a closed one. */
    fun stallUntilDisconnected(): Nothing {
        if (!disconnected.await(10, TimeUnit.SECONDS)) throw SocketTimeoutException("Never disconnected")
        endedByDisconnect = true
        throw SocketException("Socket closed")
    }

    override fun connect() = send()

    override fun getResponseCode(): Int {
        send()
        return status
    }

    override fun getHeaderField(name: String?): String? {
        send()
        return responseHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    override fun getContentLengthLong(): Long {
        send()
        return contentLength
    }

    override fun getInputStream(): InputStream {
        send()
        inputStreamOpened = true
        return body
    }

    override fun getErrorStream(): InputStream? {
        errorStreamOpened = true
        return errorBody
    }

    override fun disconnect() {
        disconnects.incrementAndGet()
        disconnected.countDown()
    }

    override fun usingProxy(): Boolean = false

    override fun getCipherSuite(): String = "TLS_AES_128_GCM_SHA256"

    override fun getLocalCertificates(): Array<Certificate>? = null

    override fun getServerCertificates(): Array<Certificate> = emptyArray()

    /** A body that counts the bytes read and records whether it was closed. */
    class Body(content: ByteArray) : InputStream() {
        private val input = ByteArrayInputStream(content)

        @Volatile
        var read = 0L
            private set

        @Volatile
        var closed = false
            private set

        override fun read(): Int = input.read().also { if (it >= 0) read++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also { if (it > 0) read += it }

        override fun close() {
            closed = true
        }
    }

    /** A body that stalls on the first read until the connection is disconnected. */
    inner class StallingBody : InputStream() {
        override fun read(): Int = stallUntilDisconnected()
    }
}
