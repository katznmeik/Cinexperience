package io.github.katznmeik.cinexperience.core.program.net

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.URI
import java.net.URL
import java.net.URLConnection
import javax.net.ssl.HttpsURLConnection

/**
 * The app's only network code (spec §11.1, Network & security rules 2–5): one
 * `GET` over Android's built-in HTTPS client (`HttpsURLConnection`), no
 * third-party library.
 *
 * - Only `https://www.yorck.de/…` ([checkAllowed], checked again here although
 *   [ProgramDownloader] only asks for such addresses).
 * - No redirects followed (`instanceFollowRedirects = false`); the downloader
 *   decides about each one.
 * - Headers: the caller's (`User-Agent`, `Accept`) plus what the HTTP client
 *   always adds: `Host`, `Connection` and `Accept-Encoding: gzip` (it unzips
 *   transparently, so the downloader's size limit counts unzipped bytes).
 * - No cookies: the app never installs a `CookieHandler`, and this refuses to
 *   connect if one is installed, so no cookie can be sent or stored. No caches.
 * - Connect and read timeouts; [TransportAbort] disconnects from another thread,
 *   which ends a blocked connect or read at once (only a running DNS lookup
 *   can't be interrupted).
 * - TLS uses the system's certificate authorities only and cleartext is blocked,
 *   by the app's network security config (`app/src/main/res/xml/network_security_config.xml`).
 *
 * @param open opens the connection for the checked address without connecting
 *   (`URL.openConnection()`). Only tests pass another one: a fake
 *   `HttpsURLConnection` without sockets that records how it was configured.
 */
internal class HttpsProgramTransport(
    private val connectTimeoutMs: Int = ProgramDownloader.CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = ProgramDownloader.READ_TIMEOUT_MS,
    private val open: (URL) -> URLConnection = { url -> url.openConnection() },
) : ProgramTransport {

    override fun get(uri: URI, headers: Map<String, String>, abort: TransportAbort): TransportResponse {
        checkAllowed(uri, CookieHandler.getDefault() != null)
        val connection = open(uri.toURL()) as? HttpsURLConnection
            ?: throw IOException("Not an HTTPS connection")
        abort.onAbort { connection.disconnect() }
        try {
            if (abort.isAborted) throw IOException("Aborted")
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.allowUserInteraction = false
            connection.doOutput = false
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            val length = connection.contentLengthLong
            val body = if (status == HTTP_OK) {
                connection.inputStream
            } else {
                connection.errorStream?.close()
                ByteArrayInputStream(ByteArray(0))
            }
            return TransportResponse(status, location, length, body) { connection.disconnect() }
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }

    companion object {
        private const val HTTP_OK = 200

        /**
         * Throws unless [uri] is `https://www.yorck.de/…` (no user info, default port)
         * and no cookie handler is installed. Runs before any connection is opened.
         */
        fun checkAllowed(uri: URI, cookieHandlerInstalled: Boolean) {
            if (!ProgramDownloader.isAllowedAddress(uri)) throw IOException("Address not allowed")
            if (cookieHandlerInstalled) throw IOException("A cookie handler is installed; refusing to connect")
        }
    }
}
