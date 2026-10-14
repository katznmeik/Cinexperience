package io.github.katznmeik.cinexperience.core.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/**
 * The light, rounded floating action button from the mockups (e.g. "Add visit").
 * Material's extended FAB hides its text from screen readers (so a collapsing FAB
 * is not read twice), so the icon carries the [text] as its description.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        containerColor = CinexTheme.colors.text,
        contentColor = CinexTheme.colors.onPrimary,
        icon = { Icon(icon, contentDescription = text, modifier = Modifier.size(20.dp)) },
        text = { Text(text, style = MaterialTheme.typography.labelLarge) },
    )
}
