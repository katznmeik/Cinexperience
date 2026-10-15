package io.github.katznmeik.cinexperience.core.program.net

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * One HTTP GET request, without following redirects. The policy (which address,
 * redirects, size and time limits, status codes) lives in [ProgramDownloader];
 * this interface keeps the sockets out of its tests. The only implementation in
 * the app is [HttpsProgramTransport]; it is public only so tests in other
 * modules can pass a transport that never reaches the network
 * (`DefaultProgramRepository.create(…, transport = …)`).
 */
interface ProgramTransport {
    /**
     * Sends `GET [uri]` with exactly [headers] and returns the response; the caller
     * closes it. Never follows redirects, never sends or stores cookies. [abort]
     * may be triggered from another thread at any time, also while connecting or
     * while the caller reads the body; the blocked call then fails promptly.
     *
     * @throws IOException if the request fails or is aborted.
     */
    fun get(uri: URI, headers: Map<String, String>, abort: TransportAbort): TransportResponse
}

/**
 * A response of [ProgramTransport.get].
 *
 * @property status the HTTP status code (-1 if the response was not valid HTTP).
 * @property location the `Location` header, for redirects.
 * @property contentLength the declared body length, -1 if unknown.
 * @property body the body (for status 200; empty otherwise).
 */
class TransportResponse(
    val status: Int,
    val location: String?,
    val contentLength: Long,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            body.close()
        } finally {
            onClose()
        }
    }
}

/**
 * Aborts a running request from another thread: the transport registers how
 * ([onAbort], e.g. `connection.disconnect()`), the downloader's watchdog calls
 * [abort] when the download is cancelled or takes too long. Thread-safe; an
 * action registered after [abort] runs at once.
 */
class TransportAbort {
    private val lock = Any()
    private var action: (() -> Unit)? = null

    @Volatile
    var isAborted: Boolean = false
        private set

    fun onAbort(action: () -> Unit) {
        val runNow = synchronized(lock) {
            if (!isAborted) this.action = action
            isAborted
        }
        if (runNow) runSafely(action)
    }

    fun abort() {
        val toRun = synchronized(lock) {
            if (isAborted) return
            isAborted = true
            action
        }
        toRun?.let(::runSafely)
    }

    private fun runSafely(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            // Aborting is best effort; the request fails or finishes either way.
        }
    }
}
