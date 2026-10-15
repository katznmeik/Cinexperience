package io.github.katznmeik.cinexperience.core.program.net

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * One HTTP GET request, without following redirects. The policy (which address,
 * redirects, size and time limits, status codes) lives in [ProgramDownloader];
 * this interface keeps the sockets out of its tests. The only implementation in
 * the app is [HttpsProgramTransport].
 */
internal interface ProgramTransport {
    /**
     * Sends `GET [uri]` with exactly [headers] and returns the response; the caller
     * closes it. Never follows redirects, never sends or stores cookies.
     *
     * @throws IOException if the request fails.
     */
    fun get(uri: URI, headers: Map<String, String>): TransportResponse
}

/**
 * A response of [ProgramTransport.get].
 *
 * @property status the HTTP status code (-1 if the response was not valid HTTP).
 * @property location the `Location` header, for redirects.
 * @property contentLength the declared body length, -1 if unknown.
 * @property body the body (for status 200; empty otherwise).
 */
internal class TransportResponse(
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
