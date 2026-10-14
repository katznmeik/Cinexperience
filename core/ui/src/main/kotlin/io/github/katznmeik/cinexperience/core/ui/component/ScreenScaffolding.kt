package io.github.katznmeik.cinexperience.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Uppercase Archivo screen title, as in the mockups ("CINEMAS", "SETTINGS"). */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = CinexTheme.textStyles.screenTitle,
        color = CinexTheme.colors.text,
        modifier = modifier.semantics { heading() },
    )
}

/** Uppercase, muted section header above a group of rows ("BACKUP", "DATA"). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = CinexTheme.textStyles.sectionHeader,
        color = CinexTheme.colors.textMuted,
        modifier = modifier
            .padding(horizontal = 4.dp)
            .semantics { heading() },
    )
}

/**
 * Basic screen layout: optional back button, uppercase title, optional muted
 * subtitle, then scrolling [content] with 16 dp side margins.
 */
@Composable
fun CinexScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (onBack != null) {
            Box(Modifier.padding(start = 4.dp, top = 8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = CinexIcons.Back,
                        contentDescription = "Back",
                        tint = CinexTheme.colors.text,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(
                start = 20.dp,
                end = 20.dp,
                top = if (onBack != null) 0.dp else 28.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ScreenTitle(title)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinexTheme.colors.textMuted,
                )
            }
        }
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            content = content,
        )
    }
}

/**
 * Opaque background of one navigation destination: every screen draws the app
 * background itself, so nothing of another screen shows through it during a
 * screen transition (spec §7). Wrap a destination's content in it.
 */
@Composable
fun ScreenBackground(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(CinexTheme.colors.background),
    ) { content() }
}
