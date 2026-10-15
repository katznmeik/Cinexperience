package io.github.katznmeik.cinexperience.core.program.net

import io.github.katznmeik.cinexperience.core.program.ProgramError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** The outcome of [ProgramDownloader.download]. */
internal sealed interface DownloadResult {
    /** The page, decoded as UTF-8 (malformed bytes become U+FFFD). */
    class Success(val page: String) : DownloadResult

    class Failure(val error: ProgramError) : DownloadResult

    /** The permission was withdrawn before a request; nothing more was sent. */
    data object NotAllowed : DownloadResult
}

/**
 * The download policy (spec §11.1, Network & security rules 2, 3 and 6), on top of
 * a [ProgramTransport]:
 *
 * - The one request: `GET https://www.yorck.de/en/films`, no query, no body, no
 *   cookies; the headers `User-Agent` ([userAgent]) and `Accept` (the HTTP client
 *   adds `Host`, `Connection` and `Accept-Encoding`). Nothing from the owner's data.
 * - Before every request (also each redirect) [download]'s `isAllowed` is asked;
 *   when it says no, nothing is sent.
 * - Redirects: at most [MAX_REDIRECTS], each only to `https://www.yorck.de/…`
 *   ([resolveRedirect]); anything else is refused.
 * - Only HTTP 200 is accepted. At most [MAX_BYTES] (10 MB) are read, counted while
 *   streaming (a larger declared length is refused before reading).
 * - Time: a watchdog aborts the transport ([TransportAbort]) when the download's
 *   coroutine is cancelled (switched off, deleted) and after [TOTAL_TIMEOUT_MS]
 *   for the whole download, redirects included. A blocked connect or read ends at
 *   once, so the real worst case is [TOTAL_TIMEOUT_MS] (plus a DNS lookup that
 *   is already running, which no client can interrupt). Each connect and read
 *   also has its own timeout in the transport.
 * - It never throws to its caller (only coroutine cancellation is passed on):
 *   every failure is a [DownloadResult.Failure].
 *
 * Blocking; the caller runs it on an IO dispatcher, whose other threads run the
 * watchdog.
 */
internal class ProgramDownloader(
    private val transport: ProgramTransport,
    appVersion: String,
    private val maxBytes: Long = MAX_BYTES,
    private val totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
) {
    val userAgent: String = userAgent(appVersion)

    suspend fun download(isAllowed: () -> Boolean): DownloadResult = coroutineScope {
        val abort = TransportAbort()
        val finished = AtomicBoolean(false)
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                delay(totalTimeoutMs)
            } finally {
                // Timed out, or the download was cancelled: end a blocked connect or read.
                if (!finished.get()) abort.abort()
            }
        }
        try {
            fetch(isAllowed, abort)
        } finally {
            finished.set(true)
            watchdog.cancel()
        }
    }

    private suspend fun fetch(isAllowed: () -> Boolean, abort: TransportAbort): DownloadResult {
        var uri = PROGRAM_URI
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!isAllowed()) return DownloadResult.NotAllowed
            if (abort.isAborted) return DownloadResult.Failure(ProgramError.NETWORK) // timed out
            val response = try {
                transport.get(uri, requestHeaders(), abort)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return failure(e, abort)
            }
            val next: URI = response.useSafely {
                when (it.status) {
                    HTTP_OK -> return readBody(it, abort)
                    in REDIRECT_CODES -> {
                        if (redirects >= MAX_REDIRECTS) return DownloadResult.Failure(ProgramError.REDIRECT_REFUSED)
                        resolveRedirect(uri, it.location) ?: return DownloadResult.Failure(ProgramError.REDIRECT_REFUSED)
                    }
                    else -> return DownloadResult.Failure(ProgramError.SERVER)
                }
            }
            redirects++
            uri = next
        }
    }

    private fun requestHeaders(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html",
    )

    private suspend fun readBody(response: TransportResponse, abort: TransportAbort): DownloadResult {
        if (response.contentLength > maxBytes) return DownloadResult.Failure(ProgramError.TOO_LARGE)
        return try {
            val known = response.contentLength.takeIf { it in 1..maxBytes }
            val initial = (known ?: minOf(INITIAL_BUFFER.toLong(), maxBytes)).toInt()
            val out = ByteArrayOutputStream(initial)
            val buffer = ByteArray(CHUNK_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                if (abort.isAborted) return DownloadResult.Failure(ProgramError.NETWORK)
                // Never read more than one byte past the limit.
                val wanted = minOf(buffer.size.toLong(), maxBytes + 1 - total).toInt()
                val read = response.body.read(buffer, 0, wanted)
                if (read < 0) break
                total += read
                if (total > maxBytes) return DownloadResult.Failure(ProgramError.TOO_LARGE)
                out.write(buffer, 0, read)
            }
            DownloadResult.Success(String(out.toByteArray(), Charsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e, abort)
        }
    }

    /** A failed call: cancellation if the watchdog aborted it for that reason, else the error. */
    private suspend fun failure(e: Exception, abort: TransportAbort): DownloadResult {
        if (abort.isAborted) {
            currentCoroutineContext().ensureActive() // cancelled: pass it on
            return DownloadResult.Failure(ProgramError.NETWORK) // timed out
        }
        return DownloadResult.Failure(classify(e))
    }

    /** Closes the response whatever happens; a failing close doesn't matter after the body was read. */
    private inline fun <T> TransportResponse.useSafely(block: (TransportResponse) -> T): T = try {
        block(this)
    } finally {
        try {
            close()
        } catch (e: Exception) {
            // Ignored: the result is already decided.
        }
    }

    companion object {
        /** The one address the app requests (spec §11.1 rule 2). */
        const val PROGRAM_URL: String = "https://www.yorck.de/en/films"
        val PROGRAM_URI: URI = URI(PROGRAM_URL)

        const val HOST: String = "www.yorck.de"
        const val MAX_REDIRECTS: Int = 3
        const val MAX_BYTES: Long = 10L * 1024 * 1024
        const val CONNECT_TIMEOUT_MS: Int = 15_000
        const val READ_TIMEOUT_MS: Int = 30_000
        const val TOTAL_TIMEOUT_MS: Long = 90_000

        private const val HTTP_OK = 200
        private const val HTTPS_PORT = 443
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val CHUNK_SIZE = 64 * 1024
        private const val INITIAL_BUFFER = 4 * 1024 * 1024
        private const val MAX_LOCATION_LENGTH = 2_048
        private val VERSION_NAME = Regex("[0-9A-Za-z.+-]{1,32}")

        /** `Cinexperience/<versionName> (Android; personal app)`; an odd version name becomes `unknown`. */
        fun userAgent(versionName: String): String {
            val version = versionName.takeIf(VERSION_NAME::matches) ?: "unknown"
            return "Cinexperience/$version (Android; personal app)"
        }

        /** True for `https://www.yorck.de/…`: no user info, the default port, an absolute (or empty) path. */
        fun isAllowedAddress(uri: URI): Boolean {
            val path = uri.rawPath.orEmpty()
            return !uri.isOpaque &&
                uri.scheme?.lowercase(Locale.ROOT) == "https" &&
                uri.rawUserInfo == null &&
                uri.host?.lowercase(Locale.ROOT) == HOST &&
                (uri.port == -1 || uri.port == HTTPS_PORT) &&
                (path.isEmpty() || path.startsWith("/"))
        }

        /**
         * The address a redirect from [from] to [location] leads to, if it is
         * `https://www.yorck.de/…` (relative locations are resolved against [from]);
         * `null` (refused) for any other scheme, host, port or user info, and for
         * anything that isn't a valid address. The result is rebuilt from its checked
         * path and query, without fragment.
         */
        fun resolveRedirect(from: URI, location: String?): URI? {
            if (location.isNullOrEmpty() || location.length > MAX_LOCATION_LENGTH) return null
            if (location.any { it.isISOControl() || it.isWhitespace() }) return null
            return try {
                val target = from.resolve(URI(location))
                if (!isAllowedAddress(target)) return null
                val path = target.rawPath.orEmpty().ifEmpty { "/" }
                val query = target.rawQuery?.let { "?$it" }.orEmpty()
                URI("https://$HOST$path$query").takeIf(::isAllowedAddress)
            } catch (e: URISyntaxException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /** No connection at all → [ProgramError.OFFLINE]; anything else → [ProgramError.NETWORK]. */
        fun classify(e: Exception): ProgramError = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is PortUnreachableException ->
                ProgramError.OFFLINE
            else -> ProgramError.NETWORK
        }
    }
}
