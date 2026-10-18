package io.github.katznmeik.cinexperience.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.katznmeik.cinexperience.core.program.FilmKey
import io.github.katznmeik.cinexperience.core.ui.component.CinexNavigationBar
import io.github.katznmeik.cinexperience.core.ui.component.CinexNavigationBarItem
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.di.AppContainer
import io.github.katznmeik.cinexperience.feature.backup.settingsScreen
import io.github.katznmeik.cinexperience.feature.cinemas.CinemasRoute
import io.github.katznmeik.cinexperience.feature.cinemas.cinemaDetailScreen
import io.github.katznmeik.cinexperience.feature.cinemas.cinemasScreen
import io.github.katznmeik.cinexperience.feature.cinemas.closeDeletedCinema
import io.github.katznmeik.cinexperience.feature.cinemas.manageCinemasScreen
import io.github.katznmeik.cinexperience.feature.cinemas.navigateToCinemaDetail
import io.github.katznmeik.cinexperience.feature.cinemas.navigateToManageCinemas
import io.github.katznmeik.cinexperience.feature.program.filmScreen
import io.github.katznmeik.cinexperience.feature.program.navigateToFilm
import io.github.katznmeik.cinexperience.feature.program.navigateToProgram
import io.github.katznmeik.cinexperience.feature.program.programScreen
import io.github.katznmeik.cinexperience.feature.rankings.rankingsScreen
import io.github.katznmeik.cinexperience.feature.seatmap.hallDetailScreen
import io.github.katznmeik.cinexperience.feature.seatmap.layoutEditorScreen
import io.github.katznmeik.cinexperience.feature.seatmap.navigateToHallDetail
import io.github.katznmeik.cinexperience.feature.seatmap.navigateToLayoutEditor
import io.github.katznmeik.cinexperience.feature.tickets.ImportTicketRoute
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketUris
import io.github.katznmeik.cinexperience.feature.tickets.navigateToImportTicket
import io.github.katznmeik.cinexperience.feature.tickets.navigateToTicket
import io.github.katznmeik.cinexperience.feature.tickets.ticketImportScreen
import io.github.katznmeik.cinexperience.feature.tickets.ticketScreen
import io.github.katznmeik.cinexperience.feature.visits.AddVisitRoute
import io.github.katznmeik.cinexperience.feature.visits.allVisitsScreen
import io.github.katznmeik.cinexperience.feature.visits.navigateToAddVisit
import io.github.katznmeik.cinexperience.feature.visits.navigateToAllVisits
import io.github.katznmeik.cinexperience.feature.visits.navigateToEditVisit
import io.github.katznmeik.cinexperience.feature.visits.navigateToImportedVisit
import io.github.katznmeik.cinexperience.feature.visits.visitEditScreens

/**
 * Root composable: bottom navigation (shown on the four tab screens only, as in
 * the mockups) and the navigation graph that wires the feature modules together.
 * Feature modules never depend on each other; cross-feature navigation happens here,
 * and the repositories from the manual DI [container] are handed to the features,
 * whose ViewModel factories use them.
 * Back uses navigateUp (never pops the start destination); the feature modules also
 * wrap every navigation callback in dropUnlessResumed, so double taps during a
 * transition are ignored.
 * Screen transitions (also for predictive back) come from [CinexTransitions].
 *
 * [pendingTicketImport] is a shared or opened ticket PDF (spec §6.10): its import
 * opens once nothing blocks writes (a running backup import is waited for), and
 * [onPendingTicketImportOpened] then marks it as handled. The import replaces itself
 * with Add visit or with the visit that has the ticket already.
 *
 * While a backup import runs, in this window of the app or another one, the screens
 * are covered by [BackupImportLock], except the Settings screen here that runs it.
 */
@Composable
fun CinexApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    pendingTicketImport: ImportTicketRoute? = null,
    onPendingTicketImportOpened: () -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentTab = TopLevelDestination.entries.firstOrNull { currentDestination.isOn(it) }
    val tabSwitch = remember { TabSwitchTracker() }
    // While the bar slides away it keeps showing the tab it was on.
    var shownTab by remember { mutableStateOf(TopLevelDestination.Cinemas) }
    if (currentTab != null) shownTab = currentTab
    // While a backup import replaces the data, nothing else may write: tabs are locked
    // (Settings itself blocks Back and its other rows).
    val importing by container.backupRepository.isImporting.collectAsStateWithLifecycle()
    // The import blocks every write in the whole app, also in another window of it (a share opens
    // one in the email app's task): the screens are covered meanwhile, except the Settings screen
    // here that runs the import, which locks itself.
    var runsBackupImport by rememberSaveable { mutableStateOf(false) }
    val lockedByImport = importing && !runsBackupImport
    val ticketImportOpened by rememberUpdatedState(onPendingTicketImportOpened)
    // A shared ticket never interrupts a backup import: it waits until the import has ended
    // (and until the navigation graph is set up, at the first frame).
    val navigationReady = backStackEntry != null
    LaunchedEffect(pendingTicketImport, importing, navigationReady) {
        val route = pendingTicketImport ?: return@LaunchedEffect
        if (importing || !navigationReady) return@LaunchedEffect
        navController.navigate(route)
        ticketImportOpened()
    }

    Scaffold(
        modifier = modifier,
        containerColor = CinexTheme.colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            // The bar comes and goes with the screen transition instead of popping in:
            // it slides while its height (and so the content's bottom padding) animates.
            AnimatedVisibility(
                visible = currentTab != null,
                enter = expandVertically(barSpec(), expandFrom = Alignment.Top) + slideInVertically(barSpec()) { it },
                exit = shrinkVertically(barSpec(), shrinkTowards = Alignment.Top) + slideOutVertically(barSpec()) { it },
            ) {
                CinexNavigationBar {
                    TopLevelDestination.entries.forEach { tab ->
                        CinexNavigationBarItem(
                            selected = tab == shownTab,
                            // While the bar slides away (a detail is opening) its items are ignored, or a
                            // quick tap would switch tabs and park the new detail in the old tab's stack.
                            onClick = { if (!importing && currentTab != null) navController.navigateToTab(tab, tabSwitch) },
                            icon = tab.icon,
                            label = tab.label,
                            modifier = Modifier.testTag(tab.testTag),
                            enabled = !importing,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = CinemasRoute,
            // Under the lock nothing below may be reached, not even by an accessibility service.
            modifier = Modifier.padding(innerPadding).then(if (lockedByImport) Modifier.clearAndSetSemantics {} else Modifier),
            // One consistent set of transitions (spec §7), see CinexTransitions.
            enterTransition = CinexTransitions.enter(tabSwitch),
            exitTransition = CinexTransitions.exit(tabSwitch),
            popEnterTransition = CinexTransitions.popEnter,
            popExitTransition = CinexTransitions.popExit,
            predictivePopEnterTransition = CinexTransitions.predictivePopEnter,
            predictivePopExitTransition = CinexTransitions.predictivePopExit,
        ) {
            // Cinemas tab
            cinemasScreen(
                cinemaRepository = container.cinemaRepository,
                visitRepository = container.visitRepository,
                ticketRepository = container.ticketRepository,
                clock = container.clock,
                onOpenCinema = navController::navigateToCinemaDetail,
                onAddVisit = { navController.navigateToAddVisit() },
                // The Next visit card (spec §6.1): the card opens the visit, "Show ticket" its ticket.
                onOpenVisit = navController::navigateToEditVisit,
                onShowTicket = navController::navigateToTicket,
            )
            cinemaDetailScreen(
                cinemaRepository = container.cinemaRepository,
                hallRepository = container.hallRepository,
                programRepository = container.programRepository,
                clock = container.clock,
                onBack = navController::navigateUp,
                onOpenHall = navController::navigateToHallDetail,
                onCinemaDeleted = navController::closeDeletedCinema,
                onOpenFilm = { kind, slug -> navController.navigateToFilm(FilmKey(kind, slug)) },
                // "Show all in Program" switches to the Program tab, filtered to the cinema.
                onShowAllInProgram = { seedKey ->
                    if (!importing) {
                        navController.navigateToProgram(seedKey, navController.tabOptions())
                        tabSwitch.entryId = navController.currentBackStackEntry?.id
                    }
                },
                // The hints open the Program tab as it is, with its remembered filter.
                onOpenProgram = { if (!importing) navController.navigateToTab(TopLevelDestination.Program, tabSwitch) },
            )
            hallDetailScreen(
                hallRepository = container.hallRepository,
                seatOverrideRepository = container.seatOverrideRepository,
                onBack = navController::navigateUp,
                onEditLayout = navController::navigateToLayoutEditor,
                onAddVisit = navController::navigateToAddVisit,
                onOpenVisit = navController::navigateToEditVisit,
            )
            layoutEditorScreen(
                hallRepository = container.hallRepository,
                referenceImageStore = container.referenceImageStore,
                onClose = navController::navigateUp,
            )
            visitEditScreens(
                cinemaRepository = container.cinemaRepository,
                visitRepository = container.visitRepository,
                ticketRepository = container.ticketRepository,
                visitPhotoStore = container.visitPhotoStore,
                clock = container.clock,
                onClose = navController::navigateUp,
                onShowTicket = navController::navigateToTicket,
                // The imported ticket's booking number belongs to another visit (imported meanwhile).
                onOpenImportedVisit = { visitId ->
                    navController.navigateToImportedVisit(visitId) { popUpTo<AddVisitRoute> { inclusive = true } }
                },
            )
            // The ticket with its QR code (v1.4, spec §6.11).
            ticketScreen(
                visitRepository = container.visitRepository,
                hallRepository = container.hallRepository,
                ticketRepository = container.ticketRepository,
                clock = container.clock,
                onBack = navController::navigateUp,
            )
            // The ticket import (v1.4, spec §6.10): Share, Open with, Settings → Import ticket.
            // It replaces itself with Add visit, or with the visit that has the ticket already.
            ticketImportScreen(
                cinemaRepository = container.cinemaRepository,
                visitRepository = container.visitRepository,
                ticketRepository = container.ticketRepository,
                programRepository = container.programRepository,
                fileSource = container.ticketFileSource,
                reader = container.ticketReader,
                clock = container.clock,
                isBackupImporting = container.backupRepository.isImporting,
                onBack = navController::navigateUp,
                onOpenNewVisit = { hallId, prefill ->
                    navController.navigateToAddVisit(hallId, prefill) { popUpTo<ImportTicketRoute> { inclusive = true } }
                },
                onOpenImportedVisit = { visitId, qrCodeNotStored ->
                    navController.navigateToImportedVisit(visitId, qrCodeNotStored) { popUpTo<ImportTicketRoute> { inclusive = true } }
                },
            )

            // Program tab (v1.1)
            programScreen(
                programRepository = container.programRepository,
                cinemaRepository = container.cinemaRepository,
                filterStore = container.programFilterStore,
                clock = container.clock,
                onOpenFilm = navController::navigateToFilm,
                onLogVisit = navController::navigateToAddVisit,
            )
            filmScreen(
                programRepository = container.programRepository,
                cinemaRepository = container.cinemaRepository,
                clock = container.clock,
                onBack = navController::navigateUp,
                onLogVisit = navController::navigateToAddVisit,
            )

            // Rankings tab
            rankingsScreen(
                rankingsRepository = container.rankingsRepository,
                onOpenHall = navController::navigateToHallDetail,
                onOpenCinema = navController::navigateToCinemaDetail,
            )

            // Settings tab
            settingsScreen(
                cinemaRepository = container.cinemaRepository,
                backupRepository = container.backupRepository,
                programRepository = container.programRepository,
                reminderSettingsStore = container.reminderSettingsStore,
                appVersion = container.appVersion,
                buildId = container.buildId,
                onOpenManage = navController::navigateToManageCinemas,
                onOpenAllVisits = navController::navigateToAllVisits,
                // Settings blocks its rows during a backup import; checked again for the picker's late result.
                // An address too long to read opens the import without it: "Couldn't open the file".
                onImportTicket = { uri ->
                    if (!importing) navController.navigateToImportTicket(uri.takeIf { it.length <= TicketUris.MAX_LENGTH })
                },
                onRunsBackupImportChanged = { runsBackupImport = it },
            )
            manageCinemasScreen(
                cinemaRepository = container.cinemaRepository,
                hallRepository = container.hallRepository,
                onBack = navController::navigateUp,
                onOpenCinema = navController::navigateToCinemaDetail,
            )
            allVisitsScreen(
                visitRepository = container.visitRepository,
                clock = container.clock,
                onBack = navController::navigateUp,
                onOpenVisit = navController::navigateToEditVisit,
            )
        }
        // The lock is a dialog of its own, above everything this window shows.
        if (lockedByImport) BackupImportLock()
    }
}

private fun <T> barSpec() = tween<T>(CinexTransitions.DURATION_MS, easing = FastOutSlowInEasing)

private fun NavDestination?.isOn(tab: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(tab.route::class) } == true

/** Options of a switch between tabs: one back stack per tab, Back returns to the start tab. */
private fun NavHostController.tabOptions(): NavOptionsBuilder.() -> Unit = {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun NavHostController.navigateToTab(tab: TopLevelDestination, tabSwitch: TabSwitchTracker) {
    navigate(tab.route, tabOptions())
    tabSwitch.entryId = currentBackStackEntry?.id
}
