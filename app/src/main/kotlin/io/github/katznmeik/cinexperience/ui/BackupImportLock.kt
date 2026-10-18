package io.github.katznmeik.cinexperience.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** The lock's heading (announced when it appears). */
internal const val BACKUP_IMPORT_LOCK_TITLE = "Importing a backup…"

internal const val BACKUP_IMPORT_LOCK_TEXT = "Your data is being replaced. You can go on once it's done."

/**
 * Covers the screens while a backup import replaces the data (spec §8: nothing else
 * may write meanwhile), in every window of the app: a share opens a second one in the
 * email app's task, whose screens the Settings screen running the import can't lock.
 * It swallows touches and Back; [CinexApp] also hides the screen below from
 * accessibility services, so nothing can be activated through them.
 */
@Composable
internal fun BackupImportLock(modifier: Modifier = Modifier) {
    BackHandler {}
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CinexTheme.colors.surfaceRaised)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                BACKUP_IMPORT_LOCK_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = CinexTheme.colors.text,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Text(BACKUP_IMPORT_LOCK_TEXT, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.textMuted)
        }
    }
}

/** Dims the screen below while keeping a hint of it (the scrim is the background colour). */
private const val SCRIM_ALPHA = 0.9f
