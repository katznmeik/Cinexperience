package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

@Composable
internal fun PhotoField(photo: ImageBitmap?, hasPhoto: Boolean, busy: Boolean, actions: VisitEditActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box {
            val tileShape = RoundedCornerShape(14.dp)
            if (hasPhoto) {
                // A picture without text: a fixed size is fine.
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(tileShape)
                        .background(CinexTheme.colors.surface)
                        .semantics { contentDescription = "Visit photo" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (photo != null) {
                        Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(CinexIcons.Image, contentDescription = null, tint = CinexTheme.colors.textMuted)
                    }
                }
            } else {
                // At least 96 dp; wider and taller when "Add photo" needs it (large text).
                DashedTile(
                    modifier = Modifier
                        .sizeIn(minWidth = 96.dp, minHeight = 96.dp)
                        .clip(tileShape)
                        .clickable(enabled = !busy, role = Role.Button) { menuOpen = true },
                )
            }
            PhotoSourceMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onCamera = {
                    menuOpen = false
                    actions.onTakePhoto()
                },
                onGallery = {
                    menuOpen = false
                    actions.onChoosePhoto()
                },
            )
        }
        if (hasPhoto) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PhotoButton("Replace", "Replace photo", enabled = !busy, onClick = { menuOpen = true })
                PhotoButton("Remove", "Remove photo", enabled = !busy, onClick = actions::onRemovePhoto)
            }
        } else if (busy) {
            Text("Adding photo…", style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
        }
    }
}

/** "Replace" / "Remove" next to the photo, read out with their object ("Remove photo"). */
@Composable
private fun PhotoButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = CinexTheme.colors.text,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun DashedTile(modifier: Modifier) {
    val outline = CinexTheme.colors.outline
    Column(
        modifier = modifier
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(CinexIcons.Camera, contentDescription = null, tint = CinexTheme.colors.textMuted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(6.dp))
        Text("Add photo", style = MaterialTheme.typography.labelSmall, color = CinexTheme.colors.textMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PhotoSourceMenu(expanded: Boolean, onDismiss: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = CinexTheme.colors.surfaceRaised) {
        DropdownMenuItem(
            text = { Text("Camera", style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text) },
            leadingIcon = { Icon(CinexIcons.Camera, contentDescription = null, tint = CinexTheme.colors.text) },
            onClick = onCamera,
        )
        DropdownMenuItem(
            text = { Text("Gallery", style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text) },
            leadingIcon = { Icon(CinexIcons.Image, contentDescription = null, tint = CinexTheme.colors.text) },
            onClick = onGallery,
        )
    }
}
