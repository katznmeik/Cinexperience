package io.github.katznmeik.cinexperience.core.data.json

import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.LayoutPattern
import io.github.katznmeik.cinexperience.core.model.LayoutRow
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON form of a [SeatLayout], stored in `hall.layout_json` and used for seed
 * layouts (and later backups). Example:
 *
 * ```json
 * {
 *   "formatVersion": 1,
 *   "nextSeatId": 9,
 *   "rows": [
 *     { "cells": "SS|SS", "seatIds": [1, 2, 3, 4], "label": "A" },
 *     { "cells": ".S|SS", "seatIds": [5, 6, 7], "numbering": "RESTART_PER_BLOCK",
 *       "blockStarts": [null, 1], "halfSeatOffset": true }
 *   ]
 * }
 * ```
 *
 * `cells` uses [LayoutPattern] (`S` seat, `W` wheelchair space, `C` companion
 * seat, `F` sofa place, `.` gap, `|` aisle). `seatIds` lists the row's seats' ids
 * left to right. In stored layouts `seatIds` and `nextSeatId` are always written;
 * seed layouts may omit them, then ids are assigned row-major from 1 (see
 * [LayoutJson.decode]).
 *
 * Format versions: 1 knows only `S` seats; 2 adds the seat kinds `W`, `C`, `F`.
 * A layout is written with the lowest version that can hold it (1 unless it has
 * a non-standard seat), so layouts without seat kinds stay readable by older app
 * versions and their JSON does not change. Reading is lenient: every known cell
 * character is accepted in every supported version (e.g. hand-written seed
 * layouts that say version 1).
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
    val blockStarts: List<Int?> = emptyList(),
    val halfSeatOffset: Boolean = false,
)

private const val NUMBERING_CONTINUOUS = "CONTINUOUS"
private const val NUMBERING_RESTART = "RESTART_PER_BLOCK"

internal object LayoutJson {
    /** Newest version this app reads and writes. Older versions must stay decodable. */
    const val FORMAT_VERSION: Int = 2

    /** The first version, without seat kinds. */
    const val FORMAT_VERSION_STANDARD_SEATS_ONLY: Int = 1

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(layout: SeatLayout): String = json.encodeToString(SeatLayoutJson.serializer(), toJson(layout))

    fun decode(text: String): SeatLayout = fromJson(json.decodeFromString(SeatLayoutJson.serializer(), text))

    /**
     * Like [decode], but returns `null` for an unreadable layout (corrupt, or a
     * newer format version) instead of throwing. Use wherever a single bad row
     * must not break a whole list or Flow.
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
                blockStarts = row.blockStartNumbers.dropLastWhile { it == null },
                halfSeatOffset = row.halfSeatOffset,
            )
        },
    )

    private fun minimumVersion(layout: SeatLayout): Int {
        val hasSeatKinds = layout.rows.any { row ->
            row.cells.any { it is LayoutCell.Seat && it.kind != SeatKind.STANDARD }
        }
        return if (hasSeatKinds) FORMAT_VERSION else FORMAT_VERSION_STANDARD_SEATS_ONLY
    }

    /**
     * Converts and validates. Rows without `seatIds` get ids after the highest id
     * seen so far (row-major), and a missing `nextSeatId` becomes highest id + 1.
     * @throws IllegalArgumentException for an unknown version or an invalid layout.
     */
    fun fromJson(dto: SeatLayoutJson): SeatLayout {
        require(dto.formatVersion in 1..FORMAT_VERSION) {
            "Unsupported layout formatVersion ${dto.formatVersion} (this app reads up to $FORMAT_VERSION)"
        }
        var maxId = dto.rows.flatMap { it.seatIds.orEmpty() }.maxOrNull() ?: 0
        val rows = dto.rows.map { row ->
            val ids = row.seatIds ?: List(LayoutPattern.seatCount(row.cells)) { ++maxId }
            LayoutRow(
                cells = LayoutPattern.decode(row.cells, ids.map(::SeatId)),
                label = row.label,
                numbering = when (row.numbering) {
                    NUMBERING_CONTINUOUS -> SeatNumbering.CONTINUOUS
                    NUMBERING_RESTART -> SeatNumbering.RESTART_PER_BLOCK
                    else -> throw IllegalArgumentException("Unknown numbering '${row.numbering}'")
                },
                blockStartNumbers = row.blockStarts,
                halfSeatOffset = row.halfSeatOffset,
            )
        }
        return SeatLayout(rows, dto.nextSeatId ?: (maxId + 1))
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
