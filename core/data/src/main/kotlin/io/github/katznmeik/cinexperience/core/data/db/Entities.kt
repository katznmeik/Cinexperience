package io.github.katznmeik.cinexperience.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

/*
 * Room tables (spec §4). Column names are snake_case and must never be renamed
 * without a migration. Enums are stored by their explicit `storageKey`, the seat
 * layout as versioned JSON (see LayoutJson.kt).
 *
 * Deleting a cinema deletes its halls; deleting a hall deletes its visits and
 * seat overrides; deleting a visit deletes its ticket (ON DELETE CASCADE). The UI
 * confirms before deleting.
 */

@Entity(
    tableName = "cinema",
    indices = [Index(value = ["seed_key"], unique = true)],
)
internal data class CinemaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String?,
    val notes: String?,
    @ColumnInfo(name = "is_preloaded") val isPreloaded: Boolean,
    /** Stable key from the seed asset, null for user-added cinemas. */
    @ColumnInfo(name = "seed_key") val seedKey: String?,
    /** Marked as a favourite by the user (spec §6.1, §6.2). Added in schema version 2. */
    @ColumnInfo(name = "is_favourite", defaultValue = "0") val isFavourite: Boolean = false,
)

@Entity(
    tableName = "hall",
    foreignKeys = [
        ForeignKey(
            entity = CinemaEntity::class,
            parentColumns = ["id"],
            childColumns = ["cinema_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["cinema_id"]),
        Index(value = ["seed_key"], unique = true),
    ],
)
internal data class HallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "cinema_id") val cinemaId: Long,
    val name: String,
    /** The SeatLayout as versioned JSON; null until drawn. */
    @ColumnInfo(name = "layout_json") val layoutJson: String?,
    @ColumnInfo(name = "screen_width") val screenWidth: Float?,
    @ColumnInfo(name = "screen_distance") val screenDistance: Float?,
    /** App-private relative file path. */
    @ColumnInfo(name = "reference_image_path") val referenceImagePath: String?,
    val notes: String?,
    @ColumnInfo(name = "is_preloaded") val isPreloaded: Boolean,
    /** Stable key from the seed asset (globally unique, e.g. "delphi-lux/saal-3"). */
    @ColumnInfo(name = "seed_key") val seedKey: String?,
    /**
     * Seat id high-water mark of this hall: ids below it may have been used by an
     * earlier layout, so new seats must get ids at or above it (Hall.nextSeatId).
     */
    @ColumnInfo(name = "next_seat_id", defaultValue = "1") val nextSeatId: Int = 1,
    /**
     * Horizontal shift of the screen's centre from the grid's centre, in seat widths
     * (Hall.screenOffset; the repositories only store ScreenOffsetRules.isValid
     * values). Added in schema version 2.
     */
    @ColumnInfo(name = "screen_offset", defaultValue = "0") val screenOffset: Float = 0f,
)

@Entity(
    tableName = "visit",
    foreignKeys = [
        ForeignKey(
            entity = HallEntity::class,
            parentColumns = ["id"],
            childColumns = ["hall_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["hall_id"]), Index(value = ["date"])],
)
internal data class VisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "hall_id") val hallId: Long,
    /** ISO-8601 date (yyyy-MM-dd), so it sorts correctly as text. */
    val date: LocalDate,
    @ColumnInfo(name = "film_title") val filmTitle: String?,
    /** SeatId within the hall's layout; not a foreign key (seats live in the layout JSON). */
    @ColumnInfo(name = "seat_id") val seatId: Int?,
    /** ScreeningFormat.storageKey */
    @ColumnInfo(name = "screening_format") val screeningFormat: String?,
    // Hall ratings (1–5, null = unrated).
    @ColumnInfo(name = "rating_sound") val ratingSound: Int?,
    @ColumnInfo(name = "rating_picture") val ratingPicture: Int?,
    @ColumnInfo(name = "rating_screen_size") val ratingScreenSize: Int?,
    @ColumnInfo(name = "rating_seat_comfort") val ratingSeatComfort: Int?,
    @ColumnInfo(name = "rating_legroom") val ratingLegroom: Int?,
    @ColumnInfo(name = "rating_steepness") val ratingSteepness: Int?,
    @ColumnInfo(name = "seat_rating") val seatRating: Int?,
    // Cinema ratings (spec §4.5), stored per visit.
    @ColumnInfo(name = "cinema_rating_atmosphere") val cinemaRatingAtmosphere: Int?,
    @ColumnInfo(name = "cinema_rating_bar_snacks") val cinemaRatingBarSnacks: Int?,
    @ColumnInfo(name = "cinema_rating_popcorn") val cinemaRatingPopcorn: Int?,
    @ColumnInfo(name = "cinema_rating_seat_comfort") val cinemaRatingSeatComfort: Int?,
    val note: String?,
    /** App-private relative file path. */
    @ColumnInfo(name = "photo_path") val photoPath: String?,
    /** The showing's start, Berlin clock time, as "HH:mm" (Visit.startTime). Added in schema version 3. */
    @ColumnInfo(name = "start_time") val startTime: LocalTime? = null,
)

/**
 * The ticket of a visit (spec §4.7): at most one per visit (the visit id is the
 * primary key), deleted with it. The booking number is unique across tickets.
 * Written only through TicketRepository, whose model types check every value.
 * Added in schema version 3.
 */
@Entity(
    tableName = "ticket",
    foreignKeys = [
        ForeignKey(
            entity = VisitEntity::class,
            parentColumns = ["id"],
            childColumns = ["visit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["booking_number"], unique = true)],
)
internal data class TicketEntity(
    @PrimaryKey @ColumnInfo(name = "visit_id") val visitId: Long,
    /** BookingNumber.value */
    @ColumnInfo(name = "booking_number") val bookingNumber: String,
    /** Every seat of the booking, as TicketSeatsJson. */
    val seats: String,
    /** The QR code (QrModules.encode); null once removed (7 days after the showing) or after a restore. */
    @ColumnInfo(name = "qr_modules") val qrModules: String?,
)

@Entity(
    tableName = "seat_override",
    primaryKeys = ["hall_id", "seat_id"],
    foreignKeys = [
        ForeignKey(
            entity = HallEntity::class,
            parentColumns = ["id"],
            childColumns = ["hall_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SeatOverrideEntity(
    @ColumnInfo(name = "hall_id") val hallId: Long,
    @ColumnInfo(name = "seat_id") val seatId: Int,
    /** SeatColour.storageKey, null = automatic colour. */
    val colour: String?,
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean,
)

/**
 * The value the seed last supplied for one field of one preloaded item (e.g. the
 * address of cinema "delphi-lux"); `value` null = the seed had no value. Lets
 * seeding tell "the user cleared a seeded value" (seed value unchanged → leave it
 * empty) from "the seed has a new value" (fill the empty field).
 */
@Entity(tableName = "seed_snapshot", primaryKeys = ["kind", "seed_key", "field"])
internal data class SeedSnapshotEntity(
    /** "cinema" or "hall". */
    val kind: String,
    @ColumnInfo(name = "seed_key") val seedKey: String,
    val field: String,
    val value: String?,
)

/** Small key/value store for database bookkeeping (e.g. the applied seed version). */
@Entity(tableName = "app_metadata")
internal data class MetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)
