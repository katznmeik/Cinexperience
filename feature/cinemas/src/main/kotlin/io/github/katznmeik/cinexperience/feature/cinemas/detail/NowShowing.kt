package io.github.katznmeik.cinexperience.feature.cinemas.detail

import io.github.katznmeik.cinexperience.core.program.DownloadPermission
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.ProgramState
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.program.YorckCinemas
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** One showing in a cinema's "Now showing" (spec §6.2). Tapping it opens the film page. */
data class NowShowingItem(
    val showingId: String,
    val filmKind: FilmKind,
    val filmSlug: String,
    val filmTitle: String,
    val start: LocalDateTime,
    val versions: List<ShowingVersion>,
    val events: List<String>,
)

data class NowShowingDay(val date: LocalDate, val showings: List<NowShowingItem>)

/** The "Now showing" section of a cinema's page (spec §6.2). */
sealed interface NowShowing {
    /** Not shown: the program state isn't read yet, or Yorck's program never has this cinema (e.g. a user-added one). */
    data object Hidden : NowShowing

    /** No downloaded program: a hint pointing to the Program tab, worded by [reason]. */
    data class Hint(val reason: HintReason) : NowShowing

    /** The cinema's upcoming showings of the next [NOW_SHOWING_DAYS] days, by day (may be empty). */
    data class Showings(val days: List<NowShowingDay>, val today: LocalDate) : NowShowing

    enum class HintReason { NOT_ASKED, OFF, NOT_DOWNLOADED }

    companion object {
        /** Today and the 6 days after it. */
        const val NOW_SHOWING_DAYS = 7

        /**
         * The section for the cinema [seedKey] from the already downloaded program in
         * [state] (it never downloads), at [now] on Berlin date [today]
         * ([io.github.katznmeik.cinexperience.core.program.Program.nowShowing]).
         */
        fun of(state: ProgramState, seedKey: String?, now: Instant, today: LocalDate): NowShowing {
            if (!state.isLoaded || seedKey == null || seedKey !in YorckCinemas.seedKeys) return Hidden
            val program = state.program
            if (program == null) {
                return Hint(
                    when (state.permission) {
                        DownloadPermission.NOT_ASKED -> HintReason.NOT_ASKED
                        DownloadPermission.OFF -> HintReason.OFF
                        DownloadPermission.ALLOWED -> HintReason.NOT_DOWNLOADED
                    },
                )
            }
            val days = program.nowShowing(seedKey, now, NOW_SHOWING_DAYS)
                .map { (date, showings) ->
                    NowShowingDay(
                        date,
                        showings.map { showing ->
                            val film = program.filmOf(showing)
                            NowShowingItem(showing.id, film.kind, film.slug, film.title, showing.start, showing.versions, showing.eventLabels)
                        },
                    )
                }
                .sortedBy { it.date }
            return Showings(days, today)
        }
    }
}

/** The hint without a program (spec §6.2: it points to the Program tab). */
internal fun NowShowing.HintReason.text(): String = when (this) {
    NowShowing.HintReason.NOT_ASKED -> "See this cinema's showtimes here after downloading Yorck's program in the Program tab."
    NowShowing.HintReason.OFF -> "Downloading the program is switched off. Turn it on in the Program tab or in Settings to see showtimes here."
    NowShowing.HintReason.NOT_DOWNLOADED -> "No program downloaded yet. Open the Program tab to download it."
}
