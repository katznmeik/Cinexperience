package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.component.CinexScreen
import io.github.katznmeik.cinexperience.core.ui.component.NavigationRow
import io.github.katznmeik.cinexperience.core.ui.component.PrimaryActionButton
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

@Composable
internal fun CinemasScreen(
    onOpenCinema: () -> Unit,
    onAddVisit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        CinexScreen(
            title = "Cinemas",
            subtitle = "All Yorck cinemas with their score and number of visits will appear here.",
        ) {
            RowGroup {
                NavigationRow(
                    title = "Cinema detail",
                    subtitle = "Placeholder link",
                    onClick = onOpenCinema,
                )
            }
        }
        PrimaryActionButton(
            text = "Add visit",
            icon = CinexIcons.Add,
            onClick = onAddVisit,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        )
    }
}

@Composable
internal fun CinemaDetailScreen(
    onBack: () -> Unit,
    onOpenHall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CinexScreen(
        title = "Cinema",
        subtitle = "Address, cinema scores and the list of halls will appear here.",
        onBack = onBack,
        modifier = modifier,
    ) {
        RowGroup {
            NavigationRow(title = "Hall detail", subtitle = "Placeholder link", onClick = onOpenHall)
        }
    }
}

@Preview(widthDp = 412, heightDp = 915, showBackground = true, backgroundColor = 0xFF0E0D0C)
@Composable
private fun CinemasScreenPreview() {
    CinexTheme { CinemasScreen(onOpenCinema = {}, onAddVisit = {}) }
}
