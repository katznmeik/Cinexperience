package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.program.FilmKey
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import io.github.katznmeik.cinexperience.core.ui.component.ScreenBackground
import io.github.katznmeik.cinexperience.core.ui.navigation.dropUnlessResumedWith
import kotlinx.serialization.Serializable
import java.time.Clock

/**
 * Program tab (spec §6.9). [cinemaSeedKey] filters it to one cinema ("Show all
 * in Program" on a cinema's page, spec §6.2); `null` keeps the remembered filters.
 */
@Serializable
data class ProgramRoute(val cinemaSeedKey: String? = null)

/**
 * The film page (spec §6.9) of the film or special [slug]; [kind] is a
 * [io.github.katznmeik.cinexperience.core.program.FilmKind] name (see [FilmKey]).
 */
@Serializable
data class FilmRoute(val kind: String, val slug: String) {
    constructor(film: FilmKey) : this(film.kind.name, film.slug)
}

fun NavController.navigateToFilm(film: FilmKey) = navigate(FilmRoute(film))

/**
 * Opens the Program tab filtered to the cinema [cinemaSeedKey] from another tab.
 * [tabOptions] are the app's tab-switch options (one back stack per tab); the
 * tab starts afresh with the filter instead of restoring its old screen.
 */
fun NavController.navigateToProgram(cinemaSeedKey: String, tabOptions: NavOptionsBuilder.() -> Unit) =
    navigate(ProgramRoute(cinemaSeedKey)) {
        tabOptions()
        restoreState = false
    }

/**
 * The Program tab.
 *
 * @param clock "now": which showings are still to come, and today's date.
 * @param onOpenFilm opens the film page ([navigateToFilm]).
 * @param onLogVisit opens Add visit pre-filled from a showing.
 */
fun NavGraphBuilder.programScreen(
    programRepository: ProgramRepository,
    cinemaRepository: CinemaRepository,
    filterStore: ProgramFilterStore,
    clock: Clock = Clock.systemDefaultZone(),
    onOpenFilm: (FilmKey) -> Unit,
    onLogVisit: (VisitPrefillArgs) -> Unit,
) {
    composable<ProgramRoute> { entry ->
        val cinema = entry.toRoute<ProgramRoute>().cinemaSeedKey
        ScreenBackground {
            ProgramEntry(
                viewModel = viewModel(factory = ProgramViewModel.factory(programRepository, cinemaRepository, filterStore, cinema, clock)),
                onOpenFilm = dropUnlessResumedWith(onOpenFilm),
                onLogVisit = dropUnlessResumedWith(onLogVisit),
            )
        }
    }
}

/** The film page; reached from the Program tab and from a cinema's "Now showing". [clock] is "now". */
fun NavGraphBuilder.filmScreen(
    programRepository: ProgramRepository,
    cinemaRepository: CinemaRepository,
    clock: Clock = Clock.systemDefaultZone(),
    onBack: () -> Unit,
    onLogVisit: (VisitPrefillArgs) -> Unit,
) {
    composable<FilmRoute> { entry ->
        val route = entry.toRoute<FilmRoute>()
        ScreenBackground {
            val film = ProgramLookup.keyOf(route.kind, route.slug)
            if (film == null) {
                FilmScreen(FilmUiState(content = FilmContent.NotFound), object : FilmActions {
                    override fun onBack() = onBack()
                })
            } else {
                FilmEntry(
                    viewModel = viewModel(factory = FilmViewModel.factory(film, programRepository, cinemaRepository, clock)),
                    onBack = dropUnlessResumed(block = onBack),
                    onLogVisit = dropUnlessResumedWith(onLogVisit),
                )
            }
        }
    }
}
