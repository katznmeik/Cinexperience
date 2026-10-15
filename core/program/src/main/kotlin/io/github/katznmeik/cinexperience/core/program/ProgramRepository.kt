package io.github.katznmeik.cinexperience.core.program

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** Whether the app may download Yorck's program (spec §6.7, §6.9, §11.1 rule 1). */
enum class DownloadPermission {
    /** The default: the owner hasn't agreed yet ("Not now" keeps it). Nothing is downloaded. */
    NOT_ASKED,

    /** The owner agreed ("Download program", or the Settings switch turned on). */
    ALLOWED,

    /** The Settings switch is off: the app makes no network requests at all. */
    OFF,
}

/** Why something failed; [message] is user-facing. */
enum class ProgramError(val message: String) {
    // Download failures (ProgramStatus.Failed); the last good program stays.

    /** No connection (no network, or yorck.de can't be reached). */
    OFFLINE("No internet connection. Check the connection and try again."),

    /** The connection broke, timed out or couldn't be secured. */
    NETWORK("The program couldn't be downloaded from yorck.de. Try again later."),

    /** yorck.de answered with an error instead of the program page. */
    SERVER("yorck.de didn't send the program. Try again later."),

    /** yorck.de redirected to another address, which the app doesn't follow. */
    REDIRECT_REFUSED("yorck.de sent the app to another address, which it doesn't open. Try again later."),

    /** The page or the program was larger than the app accepts. */
    TOO_LARGE("yorck.de sent much more data than expected, so the program wasn't loaded."),

    /** The page couldn't be read (e.g. Yorck changed it). */
    UNREADABLE("The program page on yorck.de couldn't be read. Yorck may have changed it; an app update may be needed."),

    /** The new program couldn't be saved on the phone (it is shown until the app closes). */
    STORAGE("The downloaded program couldn't be saved on the phone."),

    // Results of ProgramActionResult.Failure.

    /** The downloaded program couldn't be deleted. */
    DELETE_FAILED("The downloaded program couldn't be deleted. Try again."),

    /** The download setting couldn't be saved (it applies until the app closes). */
    SETTING_NOT_SAVED("The setting couldn't be saved on the phone. Try again."),
}

/** What the program download is doing. Only downloads change it. */
sealed interface ProgramStatus {
    /** Nothing running; the last download (if any) succeeded, or its error was dismissed. */
    data object Idle : ProgramStatus

    /** A download is running. */
    data object Loading : ProgramStatus

    /** The last download failed; the last good program is kept. */
    data class Failed(val error: ProgramError) : ProgramStatus {
        val message: String get() = error.message
    }
}

/** The result of a setting change or of deleting the program. */
sealed interface ProgramActionResult {
    data object Success : ProgramActionResult

    /** [message] is user-facing, e.g. for a snackbar. */
    data class Failure(val error: ProgramError) : ProgramActionResult {
        val message: String get() = error.message
    }
}

/**
 * Everything the Program UI shows (spec §6.9). How the UI reads it:
 *
 * - **Still starting:** `!isLoaded` (show nothing yet, not the consent screen).
 * - **Consent screen** ("Download program" / "Not now"): `isLoaded && permission == NOT_ASKED`.
 * - **Switched off** message: `permission == OFF`.
 * - **First download:** `program == null && status == Loading`.
 * - **Refreshing** (pull to refresh) with a program: `program != null && status == Loading`.
 * - **Error:** `status is Failed`, shown with the last good [program] if there is one;
 *   `dismissError()` hides it.
 * - **"As of":** [fetchedAt].
 *
 * @property permission whether downloading is allowed.
 * @property program the last good downloaded program, `null` if there is none.
 * @property status idle, downloading, or the last download failure.
 * @property isLoaded false until the stored permission and the cached program have
 *   been read at startup; until then [permission] and [program] are not meaningful.
 */
data class ProgramState(
    val permission: DownloadPermission = DownloadPermission.NOT_ASKED,
    val program: Program? = null,
    val status: ProgramStatus = ProgramStatus.Idle,
    val isLoaded: Boolean = false,
) {
    /** When [program] was downloaded ("as of"), `null` without a program. */
    val fetchedAt: Instant? get() = program?.fetchedAt
}

/**
 * Yorck's program (spec §6.9, §11.1): the download permission, the cached
 * program and the download itself.
 *
 * Rules: nothing is downloaded unless [ProgramState.permission] is
 * [DownloadPermission.ALLOWED]; downloads happen only when [refreshIfStale] or
 * [refresh] are called (no background work); concurrent calls share one
 * download; a failed download keeps the last good program and sets
 * [ProgramStatus.Failed] with a user-facing message. Setting changes and
 * deletion run to completion even if the caller is cancelled, and report their
 * own [ProgramActionResult]. No method throws (except cancellation of the caller).
 *
 * The app has exactly one instance (`AppContainer`): two would download and
 * write the same cache file independently.
 */
interface ProgramRepository {
    val state: StateFlow<ProgramState>

    /** The owner agreed on the consent screen ("Download program"). Doesn't download by itself. */
    suspend fun allowDownloads(): ProgramActionResult

    /**
     * The Settings switch. The setting is stored first; off then stops a running
     * download at once (no waiting for timeouts) and makes no requests any more.
     * On is the same as [allowDownloads].
     */
    suspend fun setDownloadsEnabled(enabled: Boolean): ProgramActionResult

    /**
     * Downloads the program if allowed and there is none, or the last one is older
     * than [STALE_AFTER_HOURS] hours or from the future (when the Program tab
     * opens). Returns when that download (or one already running) has finished.
     * When nothing needs downloading, an old download error is cleared.
     */
    suspend fun refreshIfStale()

    /** Downloads the program now if allowed (pull to refresh); joins a running download. */
    suspend fun refresh()

    /** Deletes the downloaded program ("Delete downloaded program"); stops a running download first. */
    suspend fun deleteProgram(): ProgramActionResult

    /** Hides a download error ([ProgramStatus.Failed] becomes [ProgramStatus.Idle]). */
    fun dismissError()

    companion object {
        /** A downloaded program older than this is downloaded again when the Program tab opens. */
        const val STALE_AFTER_HOURS: Long = 6
    }
}
