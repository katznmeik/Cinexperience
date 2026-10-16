package io.github.katznmeik.cinexperience.core.data.seed

import androidx.room.withTransaction
import io.github.katznmeik.cinexperience.core.data.db.CinemaEntity
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.db.MetadataEntity
import io.github.katznmeik.cinexperience.core.data.db.SeedSnapshotEntity
import io.github.katznmeik.cinexperience.core.data.db.seatIdMark
import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.json.SeedJson
import io.github.katznmeik.cinexperience.core.model.ScreenOffsetRules
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import java.util.Locale

/**
 * Loads the preloaded cinemas and halls (spec §9) from the seed asset.
 *
 * Runs when the asset's `seedVersion` is newer than the version recorded in the
 * database (a fresh database counts as version 0), inside one transaction:
 *
 * - **Matching.** Items are matched by their stable `seedKey`. If there is no
 *   match, a *user-added* item with the same name (trimmed, case-insensitive; for
 *   halls within the same cinema) is adopted instead of a duplicate being
 *   inserted: it gets the seed key but stays user-added (`isPreloaded` false), so
 *   the user can still delete it. Otherwise the item is inserted (unless the user
 *   deleted it, see below); a new hall of an existing cinema goes into that
 *   cinema. A cinema is inserted as preloaded; an inserted hall is preloaded
 *   exactly when its cinema is, so a hall added to a cinema the user created
 *   stays deletable like that cinema, and deleting that cinema doesn't take
 *   preloaded halls with it.
 * - **Deleted stays deleted.** An item whose seed key has `seed_snapshot` rows
 *   was seeded before (every run records rows for every seed cinema and hall it
 *   matches or inserts). If it is missing now, the user deleted it (only
 *   user-added items, and halls of a user-added cinema, can be deleted; data
 *   from pre-release builds, seed v1/v2, can still hold preloaded halls inside
 *   an adopted cinema, e.g. Blauer Stern typed in by hand before seed v3: such
 *   halls go with their cinema and then stay deleted too), so it is not
 *   inserted again, and neither is any hall of a deleted cinema, not even one
 *   the seed has just added. The run leaves such an item's rows as they are:
 *   they are the record of the deletion, so they are kept (the table has no
 *   foreign key, and only an import replaces them). A missing item without
 *   rows is inserted: on a fresh database there are none, and an import
 *   replaces all rows with the backup's own, so the backup's state decides (a
 *   backup from before the seed had an item has neither the item nor its rows).
 *   A cinema the user creates again under the deleted one's name is adopted
 *   again as above; its halls that were seeded before stay deleted.
 * - **Never overwrite, never delete.** Names and notes are never touched. The
 *   seed only fills fields that are empty (`null`): a cinema's address, a hall's
 *   layout, screen width and screen distance (and screen offset, see below) —
 *   and only when the seed's value is
 *   *new*, i.e. differs from the value the seed supplied last time (kept in
 *   `seed_snapshot`). So a value the user cleared stays cleared until the seed
 *   ships a different one (e.g. a corrected address or a newly transcribed layout).
 *   The one exception is an untouched seed layout (see below).
 * - **Screen offset.** `screen_offset` is not nullable; its "empty" value is 0
 *   (centred), the default every hall has until someone moves the screen. So
 *   the same rule applies with 0 in place of null: a hall's non-zero offset is
 *   never touched (except to keep it over a narrower replaced layout, see
 *   below); an offset of 0 is filled with the seed's non-zero offset when
 *   that value is new (snapshot field `screenOffset`; a missing or 0 seed offset
 *   is recorded as null). Trade-off: a user who centred a screen on purpose
 *   looks the same as one who never moved it, so a *changed* seed offset fills
 *   it, exactly like a cleared address gets a changed seed address; an
 *   unchanged seed offset never does. A separate "set by the user" flag would
 *   need another column for a case that Yorck's plans (which don't show the
 *   offset, spec §9) make unlikely.
 * - **Untouched seed layouts are updated** (the one exception, spec §9). When the
 *   seed ships a layout that differs from the one the seeder last *wrote* into a
 *   hall (snapshot field `appliedLayout`, see below), the hall's stored layout is
 *   still exactly that written layout, and the hall has no visits (with or
 *   without a seat) and no seat overrides (colours or favourite), the stored
 *   layout is replaced by the new one. "Exactly" compares the structure, not the
 *   raw seat ids ([SeatLayout.hasSameStructureAs]): a seed layout that filled an
 *   existing hall was shifted above that hall's id mark. Otherwise the stored
 *   layout is kept, and the user can redraw it. A replacement that was blocked
 *   (e.g. by a favourite seat) happens with a later seed version once nothing
 *   blocks it any more: the written layout stays the reference until the seeder
 *   writes another one.
 * - **`appliedLayout`** (per hall) is the layout the seeder wrote into the hall
 *   when it inserted the hall, filled its empty layout or replaced an untouched
 *   one, in canonical form (ids from 1, [SeatLayout.withCanonicalSeatIds]); only
 *   those writes change it. It is recorded as `null` when the seeder has written
 *   no layout: a hall inserted without one, and a user-added hall adopted while
 *   it already had a layout, which is the user's own drawing and so is never
 *   replaced, even if it equals a seed layout. A hall without the field (the
 *   field is newer; installs seeded before it have none) falls back to the
 *   previous seed's layout (snapshot field `layout`), as before the field
 *   existed, and that value is then recorded as its `appliedLayout`.
 * - **Screen geometry with a replaced layout.** The screen width and distance are
 *   replaced together with the layout, each only while it still holds the value
 *   the previous seed supplied (snapshot fields `screenWidth`, `screenDistance`)
 *   and the new seed has one. A value the user changed is kept: both are measured
 *   in seat widths and row depths, so they stay meaningful for the new grid, and
 *   a changed screen alone doesn't keep the hall from the corrected seat numbers.
 *   The screen offset follows its own rule above, with one addition: when the
 *   new layout has fewer columns than the replaced one, the offset is snapped to
 *   the new grid ([ScreenOffsetRules.snap] with the new column count), so the
 *   screen's centre stays over the grid. That is the only time the seeder changes
 *   a non-zero offset. Names, notes and the reference screenshot are never
 *   touched. Seeding the same seed again changes nothing: `appliedLayout` then
 *   holds the new layout.
 * - **Seat ids.** A seed layout's ids start at 1; when it fills or replaces the
 *   layout of an existing hall it is shifted above the hall's seat id mark, so
 *   ids are never reused. A hall whose mark leaves no room for them below
 *   [SeatLayout.MAX_SEAT_ID] (only damaged or crafted data) keeps its layout.
 */
internal class Seeder(
    private val db: CinexDatabase,
    private val readSeed: () -> String,
) {
    /** Returns true if the seed was applied, false if the database was already up to date. */
    suspend fun seedIfNeeded(): Boolean {
        val seed = SeedParser.parse(readSeed())
        return db.withTransaction {
            val applied = db.metadataDao().get(KEY_SEED_VERSION)?.toIntOrNull() ?: 0
            if (seed.seedVersion <= applied) return@withTransaction false
            apply(seed)
            db.metadataDao().put(MetadataEntity(KEY_SEED_VERSION, seed.seedVersion.toString()))
            true
        }
    }

    private suspend fun apply(seed: SeedJson) {
        val cinemaDao = db.cinemaDao()
        val hallDao = db.hallDao()
        val snapshots = Snapshots(db.seedSnapshotDao().getAll())
        val cinemas = cinemaDao.getAll().toMutableList()
        val halls = hallDao.getAll().toMutableList()

        for (seedCinema in seed.cinemas) {
            val matched = cinemas.firstOrNull { it.seedKey == seedCinema.seedKey }
                ?: cinemas.filter { it.seedKey == null && sameName(it.name, seedCinema.name) }.minByOrNull { it.id }
            val cinemaId: Long
            val cinemaIsPreloaded: Boolean
            if (matched == null) {
                // Seeded before and gone: the user deleted it, with its halls (see the class doc).
                if (snapshots.seen(CINEMA, seedCinema.seedKey)) continue
                val entity = CinemaEntity(
                    name = seedCinema.name,
                    address = seedCinema.address,
                    notes = null,
                    isPreloaded = true,
                    seedKey = seedCinema.seedKey,
                )
                cinemaId = cinemaDao.insert(entity)
                cinemaIsPreloaded = true
                cinemas += entity.copy(id = cinemaId)
            } else {
                val key = seedCinema.seedKey
                // Adopting a user-added cinema only links it to the seed; it stays deletable.
                val updated = matched.copy(
                    seedKey = key,
                    address = snapshots.fill(CINEMA, key, "address", matched.address, seedCinema.address),
                )
                if (updated != matched) {
                    cinemaDao.update(updated)
                    cinemas[cinemas.indexOf(matched)] = updated
                }
                cinemaId = matched.id
                cinemaIsPreloaded = matched.isPreloaded
            }
            snapshots.record(CINEMA, seedCinema.seedKey, "address", seedCinema.address)

            for (seedHall in seedCinema.halls) {
                val key = seedHall.seedKey
                // Canonical JSON of the seed layout with ids from 1, used for comparisons.
                val seedLayout = seedHall.layout?.let(LayoutJson::fromJson)
                val seedLayoutJson = seedLayout?.let(LayoutJson::encode)
                // What `appliedLayout` records when this run writes the seed layout into the hall.
                val seedAppliedJson = seedLayout?.let(::canonicalJson)
                // The screen offset is not nullable: 0 (centred) is its "empty" value, on
                // the hall and in the seed (missing and 0 are the same). See the class doc.
                val seedOffset = seedHall.screenOffset.offsetOrNull()
                val matchedHall = halls.firstOrNull { it.seedKey == key }
                    ?: halls.filter { it.seedKey == null && it.cinemaId == cinemaId && sameName(it.name, seedHall.name) }
                        .minByOrNull { it.id }
                if (matchedHall == null) {
                    // Seeded before and gone: the user deleted it (see the class doc).
                    if (snapshots.seen(HALL, key)) continue
                    val entity = HallEntity(
                        cinemaId = cinemaId,
                        name = seedHall.name,
                        layoutJson = seedLayoutJson,
                        screenWidth = seedHall.screenWidth,
                        screenDistance = seedHall.screenDistance,
                        screenOffset = seedOffset?.toFloat() ?: ScreenOffsetRules.CENTRED,
                        referenceImagePath = null,
                        notes = null,
                        // Like its cinema: a hall in a cinema the user created stays deletable.
                        isPreloaded = cinemaIsPreloaded,
                        seedKey = key,
                        nextSeatId = seedLayout?.nextSeatId ?: SeatLayout.FIRST_SEAT_ID,
                    )
                    halls += entity.copy(id = hallDao.insert(entity))
                    snapshots.record(HALL, key, APPLIED_LAYOUT, seedAppliedJson)
                } else {
                    // Adopting a user-added hall only links it to the seed; it stays deletable.
                    var updated = matchedHall.copy(
                        seedKey = key,
                        screenWidth = snapshots.fill(HALL, key, SCREEN_WIDTH, matchedHall.screenWidth?.toString(), seedHall.screenWidth?.toString())
                            ?.toFloat(),
                        screenDistance = snapshots.fill(HALL, key, SCREEN_DISTANCE, matchedHall.screenDistance?.toString(), seedHall.screenDistance?.toString())
                            ?.toFloat(),
                        screenOffset = snapshots.fill(HALL, key, SCREEN_OFFSET, matchedHall.screenOffset.offsetOrNull(), seedOffset)
                            ?.toFloat() ?: ScreenOffsetRules.CENTRED,
                    )
                    val fillLayout = snapshots.fill(HALL, key, LAYOUT, matchedHall.layoutJson, seedLayoutJson)
                    val adopted = matchedHall.seedKey != key
                    val applied = appliedLayoutJson(key, adopted, snapshots)
                    // The seed layout to fill an empty layout with, or to replace an untouched one,
                    // shifted above the hall's mark; null if its ids wouldn't fit (see shiftedAbove).
                    val filled = if (matchedHall.layoutJson == null && fillLayout != null && seedLayout != null) {
                        shiftedAbove(seedLayout, db.seatIdMark(matchedHall, null))
                    } else {
                        null
                    }
                    // The hall's stored layout, decoded once, where a replacement is possible
                    // (null if there is none or it is unreadable).
                    val stored = if (filled == null && seedLayout != null && !adopted) {
                        matchedHall.layoutJson?.let(LayoutJson::decodeOrNull)
                    } else {
                        null
                    }
                    val replacement = if (stored != null && seedLayout != null &&
                        replacesUntouchedLayout(matchedHall, stored, seedLayout, applied)
                    ) {
                        shiftedAbove(seedLayout, db.seatIdMark(matchedHall, stored))
                    } else {
                        null
                    }
                    if (filled != null) {
                        updated = updated.copy(layoutJson = LayoutJson.encode(filled), nextSeatId = filled.nextSeatId)
                        snapshots.record(HALL, key, APPLIED_LAYOUT, seedAppliedJson)
                    } else if (replacement != null) {
                        val shifted = replacement
                        // A stored value that is still the previous seed's follows the new seed;
                        // anything else keeps what the fill rule gave (the user's value, or a filled gap).
                        fun screenValue(field: String, stored: Float?, filled: Float?, seedValue: Float?): Float? =
                            if (seedValue != null && stored != null && stored.toString() == snapshots.previous(HALL, key, field)) {
                                seedValue
                            } else {
                                filled
                            }
                        // The one change to a set offset: on a narrower grid it is kept over the grid.
                        val narrower = stored != null && shifted.columnCount < stored.columnCount
                        updated = updated.copy(
                            layoutJson = LayoutJson.encode(shifted),
                            nextSeatId = shifted.nextSeatId,
                            screenWidth = screenValue(SCREEN_WIDTH, matchedHall.screenWidth, updated.screenWidth, seedHall.screenWidth),
                            screenDistance = screenValue(
                                SCREEN_DISTANCE, matchedHall.screenDistance, updated.screenDistance, seedHall.screenDistance,
                            ),
                            screenOffset = if (narrower) {
                                ScreenOffsetRules.snap(updated.screenOffset, shifted.columnCount)
                            } else {
                                updated.screenOffset
                            },
                        )
                        snapshots.record(HALL, key, APPLIED_LAYOUT, seedAppliedJson)
                    } else {
                        // Nothing written: the record stays as it was; an adopted hall's layout is
                        // the user's (null), and a hall seeded before the field gets its fallback.
                        snapshots.record(HALL, key, APPLIED_LAYOUT, applied)
                    }
                    if (updated != matchedHall) {
                        hallDao.update(updated)
                        halls[halls.indexOf(matchedHall)] = updated
                    }
                }
                snapshots.record(HALL, key, LAYOUT, seedLayoutJson)
                snapshots.record(HALL, key, SCREEN_WIDTH, seedHall.screenWidth?.toString())
                snapshots.record(HALL, key, SCREEN_DISTANCE, seedHall.screenDistance?.toString())
                snapshots.record(HALL, key, SCREEN_OFFSET, seedOffset)
            }
        }
        db.seedSnapshotDao().putAll(snapshots.changed())
    }

    /** A screen offset as a seed field value: `null` for centred (0) or missing. */
    private fun Float?.offsetOrNull(): String? = this?.takeIf { it != ScreenOffsetRules.CENTRED }?.toString()

    private fun sameName(a: String, b: String): Boolean =
        a.trim().lowercase(Locale.ROOT) == b.trim().lowercase(Locale.ROOT)

    /**
     * [seedLayout] (ids from 1) with its ids moved up to start at the hall's id
     * [mark], or `null` if they would reach [SeatLayout.MAX_SEAT_ID]. Only damaged
     * or crafted data has such a mark; that hall keeps its layout instead of the
     * whole seed run failing (and with it every later one).
     */
    private fun shiftedAbove(seedLayout: SeatLayout, mark: Int): SeatLayout? {
        val delta = mark - SeatLayout.FIRST_SEAT_ID // mark ≥ FIRST_SEAT_ID, so no overflow
        return if (delta > SeatLayout.MAX_SEAT_ID - seedLayout.nextSeatId) null else seedLayout.withIdsShiftedBy(delta)
    }

    /** [layout] in the canonical form `appliedLayout` records: ids from 1. */
    private fun canonicalJson(layout: SeatLayout): String = LayoutJson.encode(layout.withCanonicalSeatIds())

    /**
     * The layout the seeder wrote into the hall linked to [key] before this run
     * (canonical JSON, `null` = none): its `appliedLayout`, or for a hall seeded
     * before that field existed, the previous seed's `layout`. `null` for a hall
     * [adopted] in this run: a layout it has is the user's.
     */
    private fun appliedLayoutJson(key: String, adopted: Boolean, snapshots: Snapshots): String? = when {
        adopted -> null
        snapshots.has(HALL, key, APPLIED_LAYOUT) -> snapshots.previous(HALL, key, APPLIED_LAYOUT)
        else -> snapshots.previous(HALL, key, LAYOUT)?.let(LayoutJson::decodeOrNull)?.let(::canonicalJson)
    }

    /**
     * Whether [hall]'s [stored] layout (as decoded from it) is still exactly the
     * layout the seeder wrote into it ([appliedJson], see [appliedLayoutJson]) and
     * [seedLayout], a different one, replaces it: see "Untouched seed layouts are
     * updated" in the class doc.
     */
    private suspend fun replacesUntouchedLayout(
        hall: HallEntity,
        stored: SeatLayout,
        seedLayout: SeatLayout,
        appliedJson: String?,
    ): Boolean {
        val applied = appliedJson?.let(LayoutJson::decodeOrNull) ?: return false
        if (seedLayout.hasSameStructureAs(applied) || !stored.hasSameStructureAs(applied)) return false
        return db.visitDao().countForHall(hall.id) == 0 && db.seatOverrideDao().countForHall(hall.id) == 0
    }

    /** The values the seed supplied last time, per (kind, seed key, field). */
    private class Snapshots(existing: List<SeedSnapshotEntity>) {
        private val last = existing.associateBy({ Triple(it.kind, it.seedKey, it.field) }, { it.value })
        private val changed = LinkedHashMap<Triple<String, String, String>, String?>()
        private val seenItems = existing.mapTo(HashSet()) { it.kind to it.seedKey }

        /** The value the previous seed supplied for a field; `null` if none (or never seeded). */
        fun previous(kind: String, key: String, field: String): String? = last[Triple(kind, key, field)]

        /** Whether any value was recorded for the item before this run, i.e. it was seeded before. */
        fun seen(kind: String, key: String): Boolean = (kind to key) in seenItems

        /** Whether a value (possibly `null`) was recorded for the field before this run. */
        fun has(kind: String, key: String, field: String): Boolean = Triple(kind, key, field) in last

        /**
         * The value a field should have: [current] if set; otherwise [seedValue] if
         * it is new compared to what the seed supplied last time; otherwise null.
         */
        fun fill(kind: String, key: String, field: String, current: String?, seedValue: String?): String? {
            if (current != null) return current
            val id = Triple(kind, key, field)
            val isNew = seedValue != null && (id !in last || last[id] != seedValue)
            return if (isNew) seedValue else null
        }

        fun record(kind: String, key: String, field: String, seedValue: String?) {
            val id = Triple(kind, key, field)
            if (id !in last || last[id] != seedValue) changed[id] = seedValue
        }

        fun changed(): List<SeedSnapshotEntity> =
            changed.map { (id, value) -> SeedSnapshotEntity(id.first, id.second, id.third, value) }
    }

    companion object {
        const val KEY_SEED_VERSION: String = "seed_version"
        private const val CINEMA = "cinema"
        private const val HALL = "hall"
        private const val LAYOUT = "layout"
        private const val SCREEN_WIDTH = "screenWidth"
        private const val SCREEN_DISTANCE = "screenDistance"
        private const val SCREEN_OFFSET = "screenOffset"

        /** The layout the seeder last wrote into the hall (see the class doc). */
        private const val APPLIED_LAYOUT = "appliedLayout"
    }
}

internal object SeedParser {
    /**
     * Parses and validates seed JSON: positive `seedVersion`, non-blank and unique
     * seed keys (hall keys are unique across all cinemas) and names, valid layouts
     * and screen offsets.
     * `address` may be null or missing, `halls` may be empty or missing.
     */
    fun parse(text: String): SeedJson {
        val seed = LayoutJson.json.decodeFromString(SeedJson.serializer(), text)
        require(seed.seedVersion > 0) { "seedVersion must be positive" }
        val cinemaKeys = HashSet<String>()
        val hallKeys = HashSet<String>()
        for (cinema in seed.cinemas) {
            require(cinema.seedKey.isNotBlank() && cinemaKeys.add(cinema.seedKey)) {
                "Blank or duplicate cinema seedKey '${cinema.seedKey}'"
            }
            require(cinema.name.isNotBlank()) { "Cinema '${cinema.seedKey}' has a blank name" }
            for (hall in cinema.halls) {
                require(hall.seedKey.isNotBlank() && hallKeys.add(hall.seedKey)) {
                    "Blank or duplicate hall seedKey '${hall.seedKey}'"
                }
                require(hall.name.isNotBlank()) { "Hall '${hall.seedKey}' has a blank name" }
                hall.screenOffset?.let { offset ->
                    require(ScreenOffsetRules.isValid(offset)) { "Hall '${hall.seedKey}' has an invalid screenOffset $offset" }
                }
                hall.layout?.let(LayoutJson::fromJson)
            }
        }
        return seed
    }
}
