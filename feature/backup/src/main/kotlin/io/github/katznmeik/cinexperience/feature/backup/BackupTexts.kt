package io.github.katznmeik.cinexperience.feature.backup

import io.github.katznmeik.cinexperience.core.data.backup.BackupCounts
import io.github.katznmeik.cinexperience.core.data.backup.LastExport
import io.github.katznmeik.cinexperience.core.data.backup.SafetyCopy
import io.github.katznmeik.cinexperience.core.ui.text.countText
import io.github.katznmeik.cinexperience.core.ui.text.dateText
import io.github.katznmeik.cinexperience.core.ui.text.dateTimeText
import java.time.ZoneId
import kotlin.math.roundToInt

/** "Last export: 3 Oct 2026 · 27 visits, 9 photos". */
internal fun lastExportText(export: LastExport, zone: ZoneId = ZoneId.systemDefault()): String =
    "Last export: ${dateText(export.exportedAt.atZone(zone).toLocalDate())} · " +
        "${countText(export.counts.visits, "visit")}, ${countText(export.counts.photos, "photo")}"

/** "13 cinemas, 36 halls, 27 visits, 9 photos" for the result messages. */
internal fun countsSummary(counts: BackupCounts): String = listOf(
    countText(counts.cinemas, "cinema"),
    countText(counts.halls, "hall"),
    countText(counts.visits, "visit"),
    countText(counts.photos, "photo"),
).joinToString(", ")

/** The preview's contents, one line per pair: "13 cinemas · 36 halls" … */
internal fun previewCountLines(counts: BackupCounts): List<String> = listOf(
    "${countText(counts.cinemas, "cinema")} · ${countText(counts.halls, "hall")}",
    "${countText(counts.visits, "visit")} · ${countText(counts.photos, "photo")}",
    "${countText(counts.overrides, "marked seat")} · ${countText(counts.referenceImages, "reference image")}",
)

/** "Before the import on 14 Oct 2026, 09:12" */
internal fun safetyCopyTitle(copy: SafetyCopy, zone: ZoneId = ZoneId.systemDefault()): String =
    "Before the import on ${dateTimeText(copy.createdAt, zone)}"

/** "13 cinemas · 27 visits · 9 photos" */
internal fun safetyCopyContents(copy: SafetyCopy): String = copy.counts?.let { counts ->
    listOf(
        countText(counts.cinemas, "cinema"),
        countText(counts.visits, "visit"),
        countText(counts.photos, "photo"),
    ).joinToString(" · ")
} ?: "Contents unknown"

/** Subtitle of the "Restore safety copy" row. */
internal fun safetyCopiesSummary(copies: List<SafetyCopy>, zone: ZoneId = ZoneId.systemDefault()): String? = when (copies.size) {
    0 -> null
    1 -> safetyCopyTitle(copies.single(), zone)
    else -> "${copies.size} copies · newest from ${dateTimeText(copies.first().createdAt, zone)}"
}

/** "45%" */
internal fun percentText(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%"
