package io.github.katznmeik.cinexperience.feature.backup

import io.github.katznmeik.cinexperience.core.data.backup.BackupCounts
import io.github.katznmeik.cinexperience.core.data.backup.LastExport
import io.github.katznmeik.cinexperience.core.ui.text.countText
import io.github.katznmeik.cinexperience.core.ui.text.dateText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

/** "3 Oct 2026, 14:05" in [zone]. */
internal fun dateTimeText(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DATE_TIME.format(instant.atZone(zone))

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

/** "45%" */
internal fun percentText(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%"
