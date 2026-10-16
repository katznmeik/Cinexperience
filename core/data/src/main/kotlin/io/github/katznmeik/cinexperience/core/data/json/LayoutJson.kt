package io.github.katznmeik.cinexperience.core.data.json

import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.LayoutPattern
import io.github.katznmeik.cinexperience.core.model.LayoutRow
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON form of a [SeatLayout], stored in `hall.layout_json`, copied verbatim into
 * backups and used for seed layouts. Example:
 *
 * ```json
 * {
 *   "formatVersion": 3,
 *   "nextSeatId": 15,
 *   "rows": [
 *     { "cells": "SS|SS", "seatIds": [1, 2, 3, 4], "label": "A" },
 *     { "cells": ".S|SS", "seatIds": [5, 6, 7], "numbering": "RESTART_PER_BLOCK",
 *       "blockStarts": [null, 1], "halfSeatOffset": true },
 *     { "cells": "SSSSSSW", "seatIds": [8, 9, 10, 11, 12, 13, 14], "label": "8",
 *       "direction": "RIGHT_TO_LEFT", "seatLabels": [null, null, null, null, null, null, "R"] }
 *   ]
 * }
 * ```
 *
 * Per row (grid rows from the screen backwards; only `cells` is required):
 * - `cells` uses [LayoutPattern] (`S` seat, `W` wheelchair space, `C` companion
 *   seat, `F` sofa place, `.` gap, `|` aisle).
 * - `seatIds` lists the row's seats' ids left to right. In stored layouts
 *   `seatIds` and `nextSeatId` are always written; seed layouts may omit them,
 *   then ids are assigned row-major from 1 (see [LayoutJson.decode]).
 * - `label`: the row label; omitted = automatic (the seat row's position).
 * - `numbering`: `CONTINUOUS` (default) or `RESTART_PER_BLOCK`.
 * - `direction`: `LEFT_TO_RIGHT` (default, omitted) or `RIGHT_TO_LEFT`, as drawn
 *   with the screen at the top.
 * - `blockStarts`: start number per block, `null` = automatic. Index = block
 *   counted from the left (blocks are split by `|`), whatever the direction;
 *   value = number of that block's first numbered seat in numbering order (its
 *   rightmost seat in a right-to-left row). Trailing `null`s are not written.
 * - `seatLabels`: fixed labels of the row's seats, aligned with `seatIds` (one
 *   entry per seat of any kind, left to right): `null` = numbered, a text of 1–4
 *   characters (e.g. `"R"`) replaces the number and doesn't use one up. Omitted
 *   when no seat of the row has one.
 * - `halfSeatOffset`: the row is drawn half a seat to the right.
 *
 * Every seat place is numbered, wheelchair spaces included (see
 * [io.github.katznmeik.cinexperience.core.model.labels]). That holds for format 1
 * and 2 layouts too: the stored JSON is the same, only its reading changed.
 * Older builds didn't count wheelchair spaces (they were "Wheelchair 1, 2, …" of
 * their row), so an older build reading a layout with a `W` numbers it the old
 * way, and the seats after it one lower. Accepted before v1.0: the format version
 * only guards fields an older build can't read.
 *
 * Format versions: 1 knows only `S` seats; 2 adds the seat kinds `W`, `C`, `F`;
 * 3 adds `direction` and `seatLabels`. A layout is written with the lowest
 * version that can hold it (3 only with a right-to-left row or a fixed label, 2
 * with a non-standard seat, else 1), so older app versions keep reading every
 * layout that holds nothing new to them, refuse the others as "newer" instead
 * of silently ignoring the new fields, and the JSON of existing layouts doesn't
 * change. Reading is lenient: every known cell character and field is accepted
 * in every supported version (e.g. hand-written seed layouts that say version 1).
 */
@Serializable
internal data class SeatLayoutJson(
    val formatVersion: Int,
    val nextSeatId: Int? = null,
    val rows: List<RowJson>,
)

@Serializable
internal data class RowJson(
    val cells: String,
    val seatIds: List<Int>? = null,
    val label: String? = null,
    val numbering: String = NUMBERING_CONTINUOUS,
    val direction: String = DIRECTION_LEFT_TO_RIGHT,
    val blockStarts: List<Int?> = emptyList(),
    val seatLabels: List<String?>? = null,
    val halfSeatOffset: Boolean = false,
)

/**
 * A layout beyond this app's limits: JSON longer than [LayoutJson.MAX_JSON_LENGTH],
 * more than [SeatLayout.MAX_ROWS] rows or [SeatLayout.MAX_COLUMNS] columns, or
 * seat ids that reach [SeatLayout.MAX_SEAT_ID]. Only damaged or crafted data has
 * one; the backup validator reports it as "too large" rather than as "can't be
 * read by this app version".
 */
internal class LayoutTooLargeException(message: String) : IllegalArgumentException(message)

private const val NUMBERING_CONTINUOUS = "CONTINUOUS"
private const val NUMBERING_RESTART = "RESTART_PER_BLOCK"
private const val DIRECTION_LEFT_TO_RIGHT = "LEFT_TO_RIGHT"
private const val DIRECTION_RIGHT_TO_LEFT = "RIGHT_TO_LEFT"

internal object LayoutJson {
    /** Newest version this app reads and writes. Older versions must stay decodable. */
    const val FORMAT_VERSION: Int = 3

    /** The first version, without seat kinds. */
    const val FORMAT_VERSION_STANDARD_SEATS_ONLY: Int = 1

    /** Version 2 added the seat kinds `W`, `C` and `F`. */
    const val FORMAT_VERSION_SEAT_KINDS: Int = 2

    /** Version 3 added the row's `direction` and the seats' fixed `seatLabels`. */
    const val FORMAT_VERSION_NUMBERING: Int = 3

    /**
     * Longest layout JSON (in chars) that [decode] reads, the repositories store
     * and the backup validator accepts (also for the layouts in seed records). The
     * largest seed layout, stored with its `seatIds` (delphi Filmpalast Saal 1),
     * has about 6,100 chars; a [SeatLayout.MAX_ROWS] × [SeatLayout.MAX_COLUMNS]
     * grid with nine-digit seat ids (they are below [SeatLayout.MAX_SEAT_ID]),
     * fixed labels on every seat and every row setting has under 100,000. At most
     * 3 bytes per char, its row stays far below Android's 2 MB limit per database
     * row.
     */
    const val MAX_JSON_LENGTH: Int = 256 * 1024

    /**
     * Whether [json], which decodes to [layout], is much longer than this app's
     * own encoding of [layout]: more than twice as long plus 1,024 chars. The app
     * always stores its own encoding, so only a crafted backup has such a layout
     * (padded, e.g. with whitespace); the backup validator refuses it, so a
     * layout's JSON can't be made far larger than its content. The slack allows
     * JSON that was merely written differently (spacing, field order).
     */
    fun isPadded(json: String, layout: SeatLayout): Boolean =
        json.length > PADDING_FACTOR * encode(layout).length.toLong() + PADDING_ALLOWANCE

    private const val PADDING_FACTOR = 2
    private const val PADDING_ALLOWANCE = 1024

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(layout: SeatLayout): String = json.encodeToString(SeatLayoutJson.serializer(), toJson(layout))

    /**
     * Reads a layout. Text longer than [MAX_JSON_LENGTH] is refused before it is
     * parsed.
     * @throws LayoutTooLargeException for a layout beyond the limits (see [fromJson]).
     * @throws IllegalArgumentException (incl. SerializationException) for any other unreadable layout.
     */
    fun decode(text: String): SeatLayout {
        if (text.length > MAX_JSON_LENGTH) {
            throw LayoutTooLargeException("Layout JSON has ${text.length} chars, more than $MAX_JSON_LENGTH")
        }
        return fromJson(json.decodeFromString(SeatLayoutJson.serializer(), text))
    }

    /**
     * Like [decode], but returns `null` for an unreadable layout (corrupt, too
     * large, or a newer format version) instead of throwing. Use wherever a single
     * bad row must not break a whole list or Flow.
     */
    fun decodeOrNull(text: String): SeatLayout? =
        try {
            decode(text)
        } catch (e: IllegalArgumentException) { // includes SerializationException
            null
        }

    fun toJson(layout: SeatLayout): SeatLayoutJson = SeatLayoutJson(
        formatVersion = minimumVersion(layout),
        nextSeatId = layout.nextSeatId,
        rows = layout.rows.map { row ->
            RowJson(
                cells = LayoutPattern.encode(row.cells),
                seatIds = row.seatIds.map { it.value },
                label = row.label,
                numbering = when (row.numbering) {
                    SeatNumbering.CONTINUOUS -> NUMBERING_CONTINUOUS
                    SeatNumbering.RESTART_PER_BLOCK -> NUMBERING_RESTART
                },
                direction = when (row.numberingDirection) {
                    NumberingDirection.LEFT_TO_RIGHT -> DIRECTION_LEFT_TO_RIGHT
                    NumberingDirection.RIGHT_TO_LEFT -> DIRECTION_RIGHT_TO_LEFT
                },
                blockStarts = row.blockStartNumbers.dropLastWhile { it == null },
                seatLabels = row.fixedLabels.takeIf { labels -> labels.any { it != null } },
                halfSeatOffset = row.halfSeatOffset,
            )
        },
    )

    /** The lowest format version that holds [layout] (see the class doc). */
    internal fun minimumVersion(layout: SeatLayout): Int {
        val seats = layout.rows.asSequence().flatMap { it.cells }.filterIsInstance<LayoutCell.Seat>()
        val usesNumbering = layout.rows.any { it.numberingDirection != NumberingDirection.LEFT_TO_RIGHT } ||
            seats.any { it.fixedLabel != null }
        return when {
            usesNumbering -> FORMAT_VERSION_NUMBERING
            seats.any { it.kind != SeatKind.STANDARD } -> FORMAT_VERSION_SEAT_KINDS
            else -> FORMAT_VERSION_STANDARD_SEATS_ONLY
        }
    }

    /**
     * Converts and validates. Rows without `seatIds` get ids after the highest id
     * seen so far (row-major), and a missing `nextSeatId` becomes highest id + 1.
     * The size is checked before any cell is built.
     * @throws LayoutTooLargeException for more than [SeatLayout.MAX_ROWS] rows or
     *   [SeatLayout.MAX_COLUMNS] cells in a row, or seat ids that reach
     *   [SeatLayout.MAX_SEAT_ID] (a `nextSeatId` above it).
     * @throws IllegalArgumentException for an unknown version or an invalid layout.
     */
    fun fromJson(dto: SeatLayoutJson): SeatLayout {
        require(dto.formatVersion in 1..FORMAT_VERSION) {
            "Unsupported layout formatVersion ${dto.formatVersion} (this app reads up to $FORMAT_VERSION)"
        }
        checkLimits(dto)
        // Explicit ids are below MAX_SEAT_ID (checked), so assigning at most
        // MAX_ROWS × MAX_COLUMNS more can't overflow.
        var maxId = dto.rows.flatMap { it.seatIds.orEmpty() }.maxOrNull() ?: 0
        val rowIds = dto.rows.map { row -> row.seatIds ?: List(LayoutPattern.seatCount(row.cells)) { ++maxId } }
        val nextSeatId = dto.nextSeatId ?: (maxId + 1)
        if (nextSeatId > SeatLayout.MAX_SEAT_ID) {
            throw LayoutTooLargeException("Seat ids reach $nextSeatId, above ${SeatLayout.MAX_SEAT_ID}")
        }
        val rows = dto.rows.mapIndexed { index, row ->
            LayoutRow(
                cells = withFixedLabels(LayoutPattern.decode(row.cells, rowIds[index].map(::SeatId)), row.seatLabels, index),
                label = row.label,
                numbering = when (row.numbering) {
                    NUMBERING_CONTINUOUS -> SeatNumbering.CONTINUOUS
                    NUMBERING_RESTART -> SeatNumbering.RESTART_PER_BLOCK
                    else -> throw IllegalArgumentException("Unknown numbering '${row.numbering}'")
                },
                numberingDirection = when (row.direction) {
                    DIRECTION_LEFT_TO_RIGHT -> NumberingDirection.LEFT_TO_RIGHT
                    DIRECTION_RIGHT_TO_LEFT -> NumberingDirection.RIGHT_TO_LEFT
                    else -> throw IllegalArgumentException("Unknown direction '${row.direction}'")
                },
                blockStartNumbers = row.blockStarts,
                halfSeatOffset = row.halfSeatOffset,
            )
        }
        return SeatLayout(rows, nextSeatId)
    }

    /** Refuses a layout beyond the model's size and seat id limits, before its cells are read. */
    private fun checkLimits(dto: SeatLayoutJson) {
        if (dto.rows.size > SeatLayout.MAX_ROWS) {
            throw LayoutTooLargeException("${dto.rows.size} rows, more than ${SeatLayout.MAX_ROWS}")
        }
        dto.rows.forEachIndexed { index, row ->
            if (row.cells.length > SeatLayout.MAX_COLUMNS) {
                throw LayoutTooLargeException("Row ${index + 1} has ${row.cells.length} cells, more than ${SeatLayout.MAX_COLUMNS}")
            }
            if (row.seatIds.orEmpty().any { it >= SeatLayout.MAX_SEAT_ID }) {
                throw LayoutTooLargeException("Row ${index + 1} has a seat id of at least ${SeatLayout.MAX_SEAT_ID}")
            }
        }
        dto.nextSeatId?.let { if (it > SeatLayout.MAX_SEAT_ID) throw LayoutTooLargeException("nextSeatId $it is above ${SeatLayout.MAX_SEAT_ID}") }
    }

    /** [cells] with the row's `seatLabels` (one per seat, left to right) on their seats. */
    private fun withFixedLabels(cells: List<LayoutCell>, seatLabels: List<String?>?, row: Int): List<LayoutCell> {
        if (seatLabels == null) return cells
        val seatCount = cells.count { it is LayoutCell.Seat }
        require(seatLabels.size == seatCount) {
            "Row ${row + 1} has $seatCount seats but ${seatLabels.size} seatLabels"
        }
        seatLabels.forEach { label ->
            require(label == null || LayoutCell.Seat.isValidFixedLabel(label)) {
                "Row ${row + 1} has an invalid seat label '$label' (1–${LayoutCell.Seat.MAX_FIXED_LABEL_LENGTH} " +
                    "characters, no surrounding spaces, no control or format characters)"
            }
        }
        val labels = seatLabels.iterator()
        return cells.map { cell -> if (cell is LayoutCell.Seat) cell.copy(fixedLabel = labels.next()) else cell }
    }
}

/** Seed asset format (spec §9), see `assets/seed/yorck.json`. */
@Serializable
internal data class SeedJson(
    val seedVersion: Int,
    val cinemas: List<SeedCinemaJson> = emptyList(),
)

@Serializable
internal data class SeedCinemaJson(
    val seedKey: String,
    val name: String,
    val address: String? = null,
    val halls: List<SeedHallJson> = emptyList(),
)

@Serializable
internal data class SeedHallJson(
    val seedKey: String,
    val name: String,
    val screenWidth: Float? = null,
    val screenDistance: Float? = null,
    /**
     * Hall.screenOffset in seat widths (0.5 steps, ScreenOffsetRules); missing or
     * 0 = centred. Yorck's plans don't show it, so seed halls usually omit it.
     */
    val screenOffset: Float? = null,
    val layout: SeatLayoutJson? = null,
)
