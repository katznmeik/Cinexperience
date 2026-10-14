package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.db.CinemaEntity
import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.db.MetadataEntity
import io.github.katznmeik.cinexperience.core.data.db.SeatOverrideEntity
import io.github.katznmeik.cinexperience.core.data.db.SeedSnapshotEntity
import io.github.katznmeik.cinexperience.core.data.db.VisitEntity
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/*
 * Backup file format (spec §8), version 1. A zip file with:
 *
 *   manifest.json   what the backup is (BackupManifest), e.g.
 *                   { "format": "cinexperience-backup", "backupVersion": 1,
 *                     "appVersion": "0.1.0", "exportedAt": "2026-10-03T12:05:00Z",
 *                     "counts": { "cinemas": 13, "halls": 36, "visits": 27,
 *                                 "overrides": 4, "photos": 9, "referenceImages": 2 } }
 *   data.json       every row of the database (BackupData), ids included
 *   photos/<name>     visit photos      (the zip path equals the stored relative path)
 *   reference/<name>  reference images
 *
 * The DTOs below are the format; they are deliberately separate from the Room
 * entities, so a schema change never changes the backup format by accident.
 * Rules for changing the format:
 * - Any change to the DTOs (even an optional field) bumps BACKUP_VERSION and adds
 *   a step to BackupMigrations that converts the previous version's data.json.
 *   Imports parse data.json strictly (unknown or missing fields are errors), so
 *   an older app never drops data it doesn't understand: it rejects the backup
 *   as "made by a newer app version" via the manifest.
 * - Enum values use their storage keys (ScreeningFormat, SeatColour), which never change.
 * - Layouts are stored verbatim as the JSON string the database holds (LayoutJson).
 */

internal object BackupFormat {
    const val FORMAT_ID: String = "cinexperience-backup"

    /** The backup format this app writes and the newest it reads. */
    const val BACKUP_VERSION: Int = 1

    const val MANIFEST: String = "manifest.json"
    const val DATA: String = "data.json"

    /** Folders of app-private files in the zip, equal to their folders under `filesDir`. */
    val FILE_FOLDERS: Set<String> = setOf(VisitPhotoStore.FOLDER, ReferenceImageStore.FOLDER)

    /** A stored file name: no separators, no leading dot, nothing but safe characters. */
    private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    /**
     * True if [path] is a safe relative file path inside [folder] (or inside any of
     * the [FILE_FOLDERS] if [folder] is null), e.g. `photos/visit-<uuid>.jpg`.
     * Rejects absolute paths, `..`, backslashes, nested folders and odd characters.
     */
    fun isFilePath(path: String, folder: String? = null): Boolean {
        val slash = path.indexOf('/')
        if (slash <= 0) return false
        val head = path.substring(0, slash)
        val name = path.substring(slash + 1)
        return (if (folder == null) head in FILE_FOLDERS else head == folder) && FILE_NAME.matches(name)
    }

    /** JSON for writing: every field present (nulls too), readable. */
    val writeJson: Json = Json {
        prettyPrint = true
        explicitNulls = true
        encodeDefaults = true
    }

    /** JSON for reading data.json: strict, unknown or missing fields are errors. */
    val strictJson: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    /** JSON for reading the manifest: lenient, so a newer manifest still reveals its version. */
    val manifestJson: Json = Json {
        ignoreUnknownKeys = true
    }
}

/** Size limits of an import (zip bomb guard). */
internal data class BackupLimits(
    val maxEntries: Int = 50_000,
    val maxManifestBytes: Long = 64L * 1024,
    val maxDataBytes: Long = 64L * 1024 * 1024,
    /** Per photo or reference image; stored images are at most 2048 px JPEGs. */
    val maxFileBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
)

@Serializable
internal data class BackupManifest(
    val format: String,
    val backupVersion: Int,
    val appVersion: String,
    /** ISO-8601 instant, e.g. `2026-10-03T12:05:00Z`. */
    val exportedAt: String,
    val counts: BackupCountsJson,
)

@Serializable
internal data class BackupCountsJson(
    val cinemas: Int,
    val halls: Int,
    val visits: Int,
    val overrides: Int,
    val photos: Int,
    val referenceImages: Int,
) {
    fun toModel() = BackupCounts(cinemas, halls, visits, overrides, photos, referenceImages)
}

internal fun BackupCounts.toJson() = BackupCountsJson(cinemas, halls, visits, overrides, photos, referenceImages)

/** data.json: all rows of the database. */
@Serializable
internal data class BackupData(
    val backupVersion: Int,
    val cinemas: List<BackupCinema>,
    val halls: List<BackupHall>,
    val visits: List<BackupVisit>,
    val seatOverrides: List<BackupSeatOverride>,
    /** What the seed supplied last time (spec §9), so seeding after an import behaves as before. */
    val seedSnapshots: List<BackupSeedSnapshot>,
    /** Database bookkeeping, e.g. the applied seed version. */
    val metadata: List<BackupMetadata>,
) {
    val photoPaths: Set<String> get() = visits.mapNotNullTo(LinkedHashSet()) { it.photoPath }
    val referenceImagePaths: Set<String> get() = halls.mapNotNullTo(LinkedHashSet()) { it.referenceImagePath }

    /** Every referenced app-private file. */
    val filePaths: Set<String> get() = photoPaths + referenceImagePaths

    fun counts() = BackupCounts(
        cinemas = cinemas.size,
        halls = halls.size,
        visits = visits.size,
        overrides = seatOverrides.size,
        photos = photoPaths.size,
        referenceImages = referenceImagePaths.size,
    )

    /** With every file path replaced through [rename] (paths not in it stay). */
    fun withFilePaths(rename: Map<String, String>): BackupData = if (rename.isEmpty()) {
        this
    } else {
        copy(
            halls = halls.map { it.copy(referenceImagePath = it.referenceImagePath?.let { p -> rename[p] ?: p }) },
            visits = visits.map { it.copy(photoPath = it.photoPath?.let { p -> rename[p] ?: p }) },
        )
    }
}

@Serializable
internal data class BackupCinema(
    val id: Long,
    val name: String,
    val address: String?,
    val notes: String?,
    val isPreloaded: Boolean,
    val seedKey: String?,
)

@Serializable
internal data class BackupHall(
    val id: Long,
    val cinemaId: Long,
    val name: String,
    /** The layout JSON exactly as stored (see LayoutJson), `null` if not drawn. */
    val layoutJson: String?,
    val screenWidth: Float?,
    val screenDistance: Float?,
    val referenceImagePath: String?,
    val notes: String?,
    val isPreloaded: Boolean,
    val seedKey: String?,
    /** The hall's seat id mark (never reuse ids below it). */
    val nextSeatId: Int,
)

@Serializable
internal data class BackupVisit(
    val id: Long,
    val hallId: Long,
    /** ISO date, e.g. `2026-10-03`. */
    val date: String,
    val filmTitle: String?,
    val seatId: Int?,
    /** ScreeningFormat.storageKey */
    val screeningFormat: String?,
    val hallRatings: BackupHallRatings,
    val seatRating: Int?,
    val cinemaRatings: BackupCinemaRatings,
    val note: String?,
    val photoPath: String?,
)

@Serializable
internal data class BackupHallRatings(
    val sound: Int?,
    val picture: Int?,
    val screenSize: Int?,
    val seatComfort: Int?,
    val legroom: Int?,
    val steepness: Int?,
) {
    val all: List<Int?> get() = listOf(sound, picture, screenSize, seatComfort, legroom, steepness)
}

@Serializable
internal data class BackupCinemaRatings(
    val atmosphere: Int?,
    val barSnacks: Int?,
    val popcorn: Int?,
    val seatComfort: Int?,
) {
    val all: List<Int?> get() = listOf(atmosphere, barSnacks, popcorn, seatComfort)
}

@Serializable
internal data class BackupSeatOverride(
    val hallId: Long,
    val seatId: Int,
    /** SeatColour.storageKey, `null` = automatic colour. */
    val colour: String?,
    val isFavourite: Boolean,
)

@Serializable
internal data class BackupSeedSnapshot(
    val kind: String,
    val seedKey: String,
    val field: String,
    val value: String?,
)

@Serializable
internal data class BackupMetadata(
    val key: String,
    val value: String,
)

/**
 * Converts data.json of an older backup version, step by step, to the current
 * [BackupFormat.BACKUP_VERSION]. `steps[i]` turns version `i + 1` into `i + 2`.
 * Only version 1 exists so far; when version 2 comes, add its step here.
 */
internal object BackupMigrations {
    private val steps: List<(JsonObject) -> JsonObject> = emptyList()

    init {
        check(steps.size == BackupFormat.BACKUP_VERSION - 1) { "One migration step per older backup version" }
    }

    fun migrate(data: JsonObject, fromVersion: Int): JsonObject {
        require(fromVersion in 1..BackupFormat.BACKUP_VERSION) { "Unsupported backup version $fromVersion" }
        var result = data
        for (version in fromVersion until BackupFormat.BACKUP_VERSION) {
            result = steps[version - 1](result)
            result = JsonObject(result + ("backupVersion" to JsonPrimitive(version + 1)))
        }
        return result
    }
}

// Entity ↔ DTO conversion. Values are copied unchanged, so an import restores
// exactly what was exported.

internal fun CinemaEntity.toBackup() = BackupCinema(id, name, address, notes, isPreloaded, seedKey)

internal fun BackupCinema.toEntity() = CinemaEntity(id, name, address, notes, isPreloaded, seedKey)

internal fun HallEntity.toBackup() = BackupHall(
    id = id,
    cinemaId = cinemaId,
    name = name,
    layoutJson = layoutJson,
    screenWidth = screenWidth,
    screenDistance = screenDistance,
    referenceImagePath = referenceImagePath,
    notes = notes,
    isPreloaded = isPreloaded,
    seedKey = seedKey,
    nextSeatId = nextSeatId,
)

internal fun BackupHall.toEntity() = HallEntity(
    id = id,
    cinemaId = cinemaId,
    name = name,
    layoutJson = layoutJson,
    screenWidth = screenWidth,
    screenDistance = screenDistance,
    referenceImagePath = referenceImagePath,
    notes = notes,
    isPreloaded = isPreloaded,
    seedKey = seedKey,
    nextSeatId = nextSeatId,
)

internal fun VisitEntity.toBackup() = BackupVisit(
    id = id,
    hallId = hallId,
    date = date.toString(),
    filmTitle = filmTitle,
    seatId = seatId,
    screeningFormat = screeningFormat,
    hallRatings = BackupHallRatings(
        sound = ratingSound,
        picture = ratingPicture,
        screenSize = ratingScreenSize,
        seatComfort = ratingSeatComfort,
        legroom = ratingLegroom,
        steepness = ratingSteepness,
    ),
    seatRating = seatRating,
    cinemaRatings = BackupCinemaRatings(
        atmosphere = cinemaRatingAtmosphere,
        barSnacks = cinemaRatingBarSnacks,
        popcorn = cinemaRatingPopcorn,
        seatComfort = cinemaRatingSeatComfort,
    ),
    note = note,
    photoPath = photoPath,
)

/** @throws java.time.format.DateTimeParseException for a bad date (validated before). */
internal fun BackupVisit.toEntity() = VisitEntity(
    id = id,
    hallId = hallId,
    date = LocalDate.parse(date),
    filmTitle = filmTitle,
    seatId = seatId,
    screeningFormat = screeningFormat,
    ratingSound = hallRatings.sound,
    ratingPicture = hallRatings.picture,
    ratingScreenSize = hallRatings.screenSize,
    ratingSeatComfort = hallRatings.seatComfort,
    ratingLegroom = hallRatings.legroom,
    ratingSteepness = hallRatings.steepness,
    seatRating = seatRating,
    cinemaRatingAtmosphere = cinemaRatings.atmosphere,
    cinemaRatingBarSnacks = cinemaRatings.barSnacks,
    cinemaRatingPopcorn = cinemaRatings.popcorn,
    cinemaRatingSeatComfort = cinemaRatings.seatComfort,
    note = note,
    photoPath = photoPath,
)

internal fun SeatOverrideEntity.toBackup() = BackupSeatOverride(hallId, seatId, colour, isFavourite)

internal fun BackupSeatOverride.toEntity() = SeatOverrideEntity(hallId, seatId, colour, isFavourite)

internal fun SeedSnapshotEntity.toBackup() = BackupSeedSnapshot(kind, seedKey, field, value)

internal fun BackupSeedSnapshot.toEntity() = SeedSnapshotEntity(kind, seedKey, field, value)

internal fun MetadataEntity.toBackup() = BackupMetadata(key, value)

internal fun BackupMetadata.toEntity() = MetadataEntity(key, value)
