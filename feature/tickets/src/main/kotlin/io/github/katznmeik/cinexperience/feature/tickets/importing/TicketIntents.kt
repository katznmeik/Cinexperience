package io.github.katznmeik.cinexperience.feature.tickets.importing

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import io.github.katznmeik.cinexperience.feature.tickets.ImportTicketRoute
import java.util.Locale

/**
 * Which addresses the ticket import reads (spec §6.10, §11.2): only `content:` URIs
 * of other apps' providers, e.g. the email app's attachment. Everything else is
 * refused like an unreadable file: `file:` and `http(s):` addresses, a URI without
 * an authority, and this app's own providers (an authority starting with the
 * application id, also behind a `user@` prefix), so another app can't make the
 * import read the app's private files.
 */
object TicketUris {
    fun isReadable(uri: Uri, ownPackage: String): Boolean {
        // Exactly "content", as ContentResolver compares it.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = providerAuthority(uri) ?: return false
        return !authority.lowercase(Locale.ROOT).startsWith(ownPackage.lowercase(Locale.ROOT))
    }

    /** The provider's authority: without a `user@` prefix (other profiles); `null` if there is none. */
    internal fun providerAuthority(uri: Uri): String? = uri.authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() }
}

/**
 * The ticket import an intent of `MainActivity` asks for (spec §6.10): "Share"
 * ([Intent.ACTION_SEND] with one [Intent.EXTRA_STREAM]) or "Open with"
 * ([Intent.ACTION_VIEW] with the file as data). The file's name and type don't
 * matter (Yorck names every ticket `ticket.pdf`); the reader decides.
 */
object TicketIntents {
    /**
     * `null` if [intent] is no ticket share or open (e.g. the launcher's), or if it was
     * launched again from the recent apps ([Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY]:
     * an intent is imported once). [ImportTicketRoute] with `uri = null` for one that
     * can't be read: no stream, several streams, or an address [TicketUris] refuses.
     */
    fun importRoute(intent: Intent?, ownPackage: String): ImportTicketRoute? {
        intent ?: return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> sharedStream(intent)
            Intent.ACTION_SEND_MULTIPLE -> null // several files: not one ticket
            Intent.ACTION_VIEW -> intent.data
            else -> return null
        }
        return ImportTicketRoute(uri?.takeIf { TicketUris.isReadable(it, ownPackage) }?.toString())
    }

    /** The one shared file; `null` without one, with several, or with an extra that can't be read. */
    private fun sharedStream(intent: Intent): Uri? = try {
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 1) {
            null
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        }
    } catch (e: RuntimeException) {
        // A malformed extra from the sending app (e.g. BadParcelableException): unreadable.
        null
    }
}
