package io.github.katznmeik.cinexperience.core.data.json

import io.github.katznmeik.cinexperience.core.model.LayoutPattern
import io.github.katznmeik.cinexperience.core.model.LayoutRow
import io.github.katznmeik.cinexperience.core.model.SeatId
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
 * `cells` uses [LayoutPattern] (`S` seat, `.` gap, `|` aisle). `seatIds` lists the
 * row's seat ids left to right. In stored layouts `seatIds` and `nextSeatId` are
 * always written; seed layouts may omit them, then ids are assigned row-major
 * from 1 (see [LayoutJson.decode]).
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
    /** Version written by this app. Older versions must stay decodable. */
    const val FORMAT_VERSION: Int = 1

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(layout: SeatLayout): String = json.encodeToString(SeatLayoutJson.serializer(), toJson(layout))

    fun decode(text: String): SeatLayout = fromJson(json.decodeFromString(SeatLayoutJson.serializer(), text))

    fun toJson(layout: SeatLayout): SeatLayoutJson = SeatLayoutJson(
        formatVersion = FORMAT_VERSION,
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
    val layout: SeatLayoutJson? = null,
)
