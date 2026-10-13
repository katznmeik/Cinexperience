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
 *   halls within the same cinema) is adopted: it gets the seed key and becomes
 *   preloaded, instead of a duplicate being inserted. Otherwise the item is
 *   inserted as preloaded; a new hall of an existing cinema goes into that cinema.
 * - **Never overwrite, never delete.** Names and notes are never touched. The
 *   seed only fills fields that are empty (`null`): a cinema's address, a hall's
 *   layout, screen width and screen distance — and only when the seed's value is
 *   *new*, i.e. differs from the value the seed supplied last time (kept in
 *   `seed_snapshot`). So a value the user cleared stays cleared until the seed
 *   ships a different one (e.g. a corrected address or a newly transcribed layout).
 * - **Seat ids.** A seed layout's ids start at 1; when it fills an existing hall
 *   it is shifted above the hall's seat id mark so ids are never reused.
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
            if (matched == null) {
                val entity = CinemaEntity(
                    name = seedCinema.name,
                    address = seedCinema.address,
                    notes = null,
                    isPreloaded = true,
                    seedKey = seedCinema.seedKey,
                )
                cinemaId = cinemaDao.insert(entity)
                cinemas += entity.copy(id = cinemaId)
            } else {
                val key = seedCinema.seedKey
                val updated = matched.copy(
                    seedKey = key,
                    isPreloaded = true,
                    address = snapshots.fill(CINEMA, key, "address", matched.address, seedCinema.address),
                )
                if (updated != matched) {
                    cinemaDao.update(updated)
                    cinemas[cinemas.indexOf(matched)] = updated
                }
                cinemaId = matched.id
            }
            snapshots.record(CINEMA, seedCinema.seedKey, "address", seedCinema.address)

            for (seedHall in seedCinema.halls) {
                val key = seedHall.seedKey
                // Canonical JSON of the seed layout with ids from 1, used for comparisons.
                val seedLayout = seedHall.layout?.let(LayoutJson::fromJson)
                val seedLayoutJson = seedLayout?.let(LayoutJson::encode)
                val matchedHall = halls.firstOrNull { it.seedKey == key }
                    ?: halls.filter { it.seedKey == null && it.cinemaId == cinemaId && sameName(it.name, seedHall.name) }
                        .minByOrNull { it.id }
                if (matchedHall == null) {
                    val entity = HallEntity(
                        cinemaId = cinemaId,
                        name = seedHall.name,
                        layoutJson = seedLayoutJson,
                        screenWidth = seedHall.screenWidth,
                        screenDistance = seedHall.screenDistance,
                        referenceImagePath = null,
                        notes = null,
                        isPreloaded = true,
                        seedKey = key,
                        nextSeatId = seedLayout?.nextSeatId ?: SeatLayout.FIRST_SEAT_ID,
                    )
                    halls += entity.copy(id = hallDao.insert(entity))
                } else {
                    var updated = matchedHall.copy(
                        seedKey = key,
                        isPreloaded = true,
                        screenWidth = snapshots.fill(HALL, key, "screenWidth", matchedHall.screenWidth?.toString(), seedHall.screenWidth?.toString())
                            ?.toFloat(),
                        screenDistance = snapshots.fill(HALL, key, "screenDistance", matchedHall.screenDistance?.toString(), seedHall.screenDistance?.toString())
                            ?.toFloat(),
                    )
                    val fillLayout = snapshots.fill(HALL, key, "layout", matchedHall.layoutJson, seedLayoutJson)
                    if (matchedHall.layoutJson == null && fillLayout != null && seedLayout != null) {
                        val mark = db.seatIdMark(matchedHall, null)
                        val shifted = seedLayout.withIdsShiftedBy(mark - SeatLayout.FIRST_SEAT_ID)
                        updated = updated.copy(layoutJson = LayoutJson.encode(shifted), nextSeatId = shifted.nextSeatId)
                    }
                    if (updated != matchedHall) {
                        hallDao.update(updated)
                        halls[halls.indexOf(matchedHall)] = updated
                    }
                }
                snapshots.record(HALL, key, "layout", seedLayoutJson)
                snapshots.record(HALL, key, "screenWidth", seedHall.screenWidth?.toString())
                snapshots.record(HALL, key, "screenDistance", seedHall.screenDistance?.toString())
            }
        }
        db.seedSnapshotDao().putAll(snapshots.changed())
    }

    private fun sameName(a: String, b: String): Boolean =
        a.trim().lowercase(Locale.ROOT) == b.trim().lowercase(Locale.ROOT)

    /** The values the seed supplied last time, per (kind, seed key, field). */
    private class Snapshots(existing: List<SeedSnapshotEntity>) {
        private val last = existing.associateBy({ Triple(it.kind, it.seedKey, it.field) }, { it.value })
        private val changed = LinkedHashMap<Triple<String, String, String>, String?>()

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
    }
}

internal object SeedParser {
    /**
     * Parses and validates seed JSON: positive `seedVersion`, non-blank and unique
     * seed keys (hall keys are unique across all cinemas) and names, valid layouts.
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
                hall.layout?.let(LayoutJson::fromJson)
            }
        }
        return seed
    }
}
