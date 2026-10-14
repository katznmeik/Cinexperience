package io.github.katznmeik.cinexperience.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.katznmeik.cinexperience.core.ui.component.CinexNavigationBar
import io.github.katznmeik.cinexperience.core.ui.component.CinexNavigationBarItem
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.di.AppContainer
import io.github.katznmeik.cinexperience.feature.backup.settingsScreen
import io.github.katznmeik.cinexperience.feature.cinemas.CINEMA_RESULT_MESSAGE_KEY
import io.github.katznmeik.cinexperience.feature.cinemas.CinemasRoute
import io.github.katznmeik.cinexperience.feature.cinemas.cinemaDetailScreen
import io.github.katznmeik.cinexperience.feature.cinemas.cinemasScreen
import io.github.katznmeik.cinexperience.feature.cinemas.closeDeletedCinema
import io.github.katznmeik.cinexperience.feature.cinemas.manageCinemasScreen
import io.github.katznmeik.cinexperience.feature.cinemas.navigateToCinemaDetail
import io.github.katznmeik.cinexperience.feature.cinemas.navigateToManageCinemas
import io.github.katznmeik.cinexperience.feature.rankings.rankingsScreen
import io.github.katznmeik.cinexperience.feature.seatmap.hallDetailScreen
import io.github.katznmeik.cinexperience.feature.seatmap.layoutEditorScreen
import io.github.katznmeik.cinexperience.feature.seatmap.navigateToHallDetail
import io.github.katznmeik.cinexperience.feature.seatmap.navigateToLayoutEditor
import io.github.katznmeik.cinexperience.feature.visits.allVisitsScreen
import io.github.katznmeik.cinexperience.feature.visits.navigateToAddVisit
import io.github.katznmeik.cinexperience.feature.visits.navigateToAllVisits
import io.github.katznmeik.cinexperience.feature.visits.navigateToEditVisit
import io.github.katznmeik.cinexperience.feature.visits.visitEditScreens

/**
 * Root composable: bottom navigation (shown on the three tab screens only, as in
 * the mockups) and the navigation graph that wires the feature modules together.
 * Feature modules never depend on each other; cross-feature navigation happens here,
 * and the repositories from the manual DI [container] are handed to the features,
 * whose ViewModel factories use them.
 * Back uses navigateUp (never pops the start destination); the feature modules also
 * wrap every navigation callback in dropUnlessResumed, so double taps during a
 * transition are ignored.
 */
@Composable
fun CinexApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentTab = TopLevelDestination.entries.firstOrNull { currentDestination.isOn(it) }
    // While a backup import replaces the data, nothing else may write: tabs are locked
    // (Settings itself blocks Back and its other rows).
    val importing by container.backupRepository.isImporting.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        containerColor = CinexTheme.colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (currentTab != null) {
                CinexNavigationBar {
                    TopLevelDestination.entries.forEach { tab ->
                        CinexNavigationBarItem(
                            selected = tab == currentTab,
                            onClick = { if (!importing) navController.navigateToTab(tab) },
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
            modifier = Modifier.padding(innerPadding),
        ) {
            // Cinemas tab
            cinemasScreen(
                cinemaRepository = container.cinemaRepository,
                onOpenCinema = navController::navigateToCinemaDetail,
                onAddVisit = { navController.navigateToAddVisit() },
            )
            cinemaDetailScreen(
                cinemaRepository = container.cinemaRepository,
                hallRepository = container.hallRepository,
                onBack = navController::navigateUp,
                onOpenHall = navController::navigateToHallDetail,
                onCinemaDeleted = navController::closeDeletedCinema,
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
                visitPhotoStore = container.visitPhotoStore,
                onClose = navController::navigateUp,
            )

            // Rankings tab
            rankingsScreen(
                rankingsRepository = container.rankingsRepository,
                onOpenHall = navController::navigateToHallDetail,
                onOpenCinema = navController::navigateToCinemaDetail,
                // A cinema deleted in a detail opened from here leaves its message for this screen.
                resultMessageKey = CINEMA_RESULT_MESSAGE_KEY,
            )

            // Settings tab
            settingsScreen(
                cinemaRepository = container.cinemaRepository,
                backupRepository = container.backupRepository,
                appVersion = container.appVersion,
                buildId = container.buildId,
                onOpenManage = navController::navigateToManageCinemas,
                onOpenAllVisits = navController::navigateToAllVisits,
            )
            manageCinemasScreen(
                cinemaRepository = container.cinemaRepository,
                hallRepository = container.hallRepository,
                onBack = navController::navigateUp,
                onOpenCinema = navController::navigateToCinemaDetail,
            )
            allVisitsScreen(
                visitRepository = container.visitRepository,
                onBack = navController::navigateUp,
                onOpenVisit = navController::navigateToEditVisit,
            )
        }
    }
}

private fun NavDestination?.isOn(tab: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(tab.route::class) } == true

private fun NavHostController.navigateToTab(tab: TopLevelDestination) {
    navigate(tab.route) {
        // Keep one back stack per tab and return to the start tab on Back.
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
