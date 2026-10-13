package io.github.katznmeik.cinexperience.core.data.seed

import androidx.room.withTransaction
import io.github.katznmeik.cinexperience.core.data.db.CinemaEntity
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.db.MetadataEntity
import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.json.SeedJson

/**
 * Loads the preloaded cinemas and halls (spec §9) from the seed asset.
 *
 * Runs when the asset's `seedVersion` is newer than the version recorded in the
 * database (a fresh database counts as version 0), inside one transaction:
 * - Items are matched by their stable `seedKey`. Missing cinemas and halls are
 *   inserted as preloaded; a new hall of an existing cinema goes into that cinema.
 * - Existing items are never overwritten, renamed or deleted. The seed only
 *   fills fields that are still empty (`null`): a cinema's address, a hall's
 *   layout, screen width and screen distance. That way verified addresses and
 *   layouts transcribed later reach existing installs without touching anything
 *   the user entered. Names and notes are never touched.
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
        val cinemasByKey = cinemaDao.getAll().filter { it.seedKey != null }.associateBy { it.seedKey!! }
        val hallsByKey = hallDao.getAll().filter { it.seedKey != null }.associateBy { it.seedKey!! }

        for (seedCinema in seed.cinemas) {
            val existingCinema = cinemasByKey[seedCinema.seedKey]
            val cinemaId = if (existingCinema == null) {
                cinemaDao.insert(
                    CinemaEntity(
                        name = seedCinema.name,
                        address = seedCinema.address,
                        notes = null,
                        isPreloaded = true,
                        seedKey = seedCinema.seedKey,
                    ),
                )
            } else {
                if (existingCinema.address == null && seedCinema.address != null) {
                    cinemaDao.update(existingCinema.copy(address = seedCinema.address))
                }
                existingCinema.id
            }

            for (seedHall in seedCinema.halls) {
                val layoutJson = seedHall.layout?.let { LayoutJson.encode(LayoutJson.fromJson(it)) }
                val existingHall = hallsByKey[seedHall.seedKey]
                if (existingHall == null) {
                    hallDao.insert(
                        HallEntity(
                            cinemaId = cinemaId,
                            name = seedHall.name,
                            layoutJson = layoutJson,
                            screenWidth = seedHall.screenWidth,
                            screenDistance = seedHall.screenDistance,
                            referenceImagePath = null,
                            notes = null,
                            isPreloaded = true,
                            seedKey = seedHall.seedKey,
                        ),
                    )
                } else {
                    val filled = existingHall.copy(
                        layoutJson = existingHall.layoutJson ?: layoutJson,
                        screenWidth = existingHall.screenWidth ?: seedHall.screenWidth,
                        screenDistance = existingHall.screenDistance ?: seedHall.screenDistance,
                    )
                    if (filled != existingHall) hallDao.update(filled)
                }
            }
        }
    }

    companion object {
        const val KEY_SEED_VERSION: String = "seed_version"
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
