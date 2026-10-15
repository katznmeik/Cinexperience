package io.github.katznmeik.cinexperience.core.program.net

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * A [ProgramTransport] without sockets: it records every request and answers
 * with the scripted answers in order (the last one repeats). An answer may throw.
 *
 * ```
 * FakeTransport().then { response(302, location = "/en/films/") }.then { ok("<html>…") }
 * ```
 */
internal class FakeTransport : ProgramTransport {
    private val answers = ArrayDeque<FakeTransport.() -> TransportResponse>()

    class Request(val uri: URI, val headers: Map<String, String>)

    val requests = mutableListOf<Request>()

    /** Number of closed responses. */
    var closed = 0
        private set

    /** Called inside [get] with the request number (from 1) before answering. */
    var onRequest: (Int) -> Unit = {}

    fun then(answer: FakeTransport.() -> TransportResponse): FakeTransport = apply { answers += answer }

    /** Replaces the script: from now on every request gets [answer]. */
    fun always(answer: FakeTransport.() -> TransportResponse): FakeTransport = apply {
        answers.clear()
        answers += answer
    }

    override fun get(uri: URI, headers: Map<String, String>): TransportResponse {
        requests += Request(uri, headers)
        onRequest(requests.size)
        val answer = if (answers.size > 1) answers.removeFirst() else answers.first()
        return answer()
    }

    fun ok(body: String): TransportResponse = ok(body.toByteArray(Charsets.UTF_8))

    fun ok(body: ByteArray, contentLength: Long = body.size.toLong()): TransportResponse =
        response(200, body = ByteArrayInputStream(body), contentLength = contentLength)

    fun response(
        status: Int,
        location: String? = null,
        body: InputStream = ByteArrayInputStream(ByteArray(0)),
        contentLength: Long = -1,
    ): TransportResponse = TransportResponse(status, location, contentLength, body) { closed++ }

    /** A body that fails when read. */
    class FailingBody : InputStream() {
        override fun read(): Int = throw IOException("Connection reset")
    }

    /** A body of [size] bytes that counts how many were read. */
    class CountingBody(private val size: Long) : InputStream() {
        var read = 0L
            private set

        override fun read(): Int = if (read < size) 'a'.code.also { read++ } else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= size) return -1
            val n = minOf(len.toLong(), size - read).toInt()
            b.fill('a'.code.toByte(), off, off + n)
            read += n
            return n
        }
    }
}
