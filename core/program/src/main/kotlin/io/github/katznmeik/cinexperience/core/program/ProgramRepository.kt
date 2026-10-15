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

/** Why the last download failed; [message] is user-facing. The last good program stays. */
enum class ProgramError(val message: String) {
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

    /** The downloaded program couldn't be deleted. */
    DELETE_FAILED("The downloaded program couldn't be deleted. Try again."),

    /** The download setting couldn't be saved (it applies until the app closes). */
    SETTING_NOT_SAVED("The setting couldn't be saved on the phone. Try again."),
}

/** What the program download is doing. */
sealed interface ProgramStatus {
    /** Nothing running; the last download (if any) succeeded. */
    data object Idle : ProgramStatus

    /** A download is running. */
    data object Loading : ProgramStatus

    /** The last action failed; the last good program is kept. */
    data class Failed(val error: ProgramError) : ProgramStatus {
        val message: String get() = error.message
    }
}

/**
 * Everything the Program UI shows (spec §6.9).
 *
 * @property permission whether downloading is allowed.
 * @property program the last good downloaded program, `null` if there is none.
 * @property status idle, downloading, or the last failure.
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
 * download; a failed download keeps the last good program and reports a
 * [ProgramStatus.Failed] with a user-facing message. No method throws (except
 * cancellation of the caller).
 */
interface ProgramRepository {
    val state: StateFlow<ProgramState>

    /** The owner agreed on the consent screen ("Download program"). Doesn't download by itself. */
    suspend fun allowDownloads()

    /**
     * The Settings switch. Off stops a running download and makes no requests
     * any more; on is the same as [allowDownloads].
     */
    suspend fun setDownloadsEnabled(enabled: Boolean)

    /**
     * Downloads the program if allowed and there is none, or the last one is older
     * than [STALE_AFTER_HOURS] hours (when the Program tab opens). Returns when that
     * download (or one already running) has finished.
     */
    suspend fun refreshIfStale()

    /** Downloads the program now if allowed (pull to refresh); joins a running download. */
    suspend fun refresh()

    /** Deletes the downloaded program ("Delete downloaded program"); stops a running download first. */
    suspend fun deleteProgram()

    companion object {
        /** A downloaded program older than this is downloaded again when the Program tab opens. */
        const val STALE_AFTER_HOURS: Long = 6
    }
}
