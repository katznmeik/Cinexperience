package io.github.katznmeik.cinexperience.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime

/** A hall with everything needed to score it. */
internal data class HallWithData(
    @Embedded val hall: HallEntity,
    @Relation(parentColumn = "id", entityColumn = "hall_id")
    val visits: List<VisitEntity>,
    @Relation(parentColumn = "id", entityColumn = "hall_id")
    val overrides: List<SeatOverrideEntity>,
)

/** A cinema with all its halls, visits and overrides, read in one transaction. */
internal data class CinemaWithHalls(
    @Embedded val cinema: CinemaEntity,
    @Relation(entity = HallEntity::class, parentColumn = "id", entityColumn = "cinema_id")
    val halls: List<HallWithData>,
)

/** A hall with its cinema, visits and overrides (hall detail). */
internal data class HallWithCinema(
    @Embedded val hall: HallEntity,
    @Relation(parentColumn = "cinema_id", entityColumn = "id")
    val cinema: CinemaEntity,
    @Relation(parentColumn = "id", entityColumn = "hall_id")
    val visits: List<VisitEntity>,
    @Relation(parentColumn = "id", entityColumn = "hall_id")
    val overrides: List<SeatOverrideEntity>,
)

/** A hall's stored layout JSON, read on its own (see [VisitWithContext]). */
internal data class HallLayoutJson(
    @ColumnInfo(name = "id") val hallId: Long,
    @ColumnInfo(name = "layout_json") val layoutJson: String?,
)

/**
 * A visit with the names needed for the "All visits" list, and its hall's layout
 * for the seat label. Room reads the layouts in a second query, once per hall
 * (not once per visit, as a join column would), and every visit of a hall shares
 * that one [hallLayout] object.
 */
internal data class VisitWithContext(
    @Embedded val visit: VisitEntity,
    @ColumnInfo(name = "hall_name") val hallName: String,
    @ColumnInfo(name = "cinema_id") val cinemaId: Long,
    @ColumnInfo(name = "cinema_name") val cinemaName: String,
    /** The visit has a row in `ticket`. */
    @ColumnInfo(name = "has_ticket") val hasTicket: Boolean,
    @Relation(entity = HallEntity::class, parentColumn = "hall_id", entityColumn = "id")
    val hallLayout: HallLayoutJson?,
)

/** When the showing of a ticket with QR modules starts (for their expiry). */
internal data class TicketShowingTime(
    @ColumnInfo(name = "visit_id") val visitId: Long,
    val date: LocalDate,
    @ColumnInfo(name = "start_time") val startTime: LocalTime?,
)

@Dao
internal interface CinemaDao {
    @Insert
    suspend fun insert(cinema: CinemaEntity): Long

    @Update
    suspend fun update(cinema: CinemaEntity): Int

    @Query("SELECT * FROM cinema WHERE id = :id")
    suspend fun get(id: Long): CinemaEntity?

    @Query("SELECT * FROM cinema")
    suspend fun getAll(): List<CinemaEntity>

    @Query("DELETE FROM cinema WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Transaction
    @Query("SELECT * FROM cinema")
    fun observeAllWithHalls(): Flow<List<CinemaWithHalls>>

    @Transaction
    @Query("SELECT * FROM cinema WHERE id = :id")
    fun observeWithHalls(id: Long): Flow<CinemaWithHalls?>
}

@Dao
internal interface HallDao {
    @Insert
    suspend fun insert(hall: HallEntity): Long

    @Update
    suspend fun update(hall: HallEntity): Int

    @Query("SELECT * FROM hall WHERE id = :id")
    suspend fun get(id: Long): HallEntity?

    @Query("SELECT * FROM hall")
    suspend fun getAll(): List<HallEntity>

    @Query("SELECT COUNT(*) FROM hall")
    suspend fun count(): Int

    @Query("DELETE FROM hall WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Transaction
    @Query("SELECT * FROM hall WHERE id = :id")
    fun observeWithCinema(id: Long): Flow<HallWithCinema?>

    @Query("SELECT reference_image_path FROM hall WHERE reference_image_path IS NOT NULL")
    suspend fun referenceImagePaths(): List<String>
}

@Dao
internal interface VisitDao {
    @Insert
    suspend fun insert(visit: VisitEntity): Long

    @Update
    suspend fun update(visit: VisitEntity): Int

    @Query("SELECT * FROM visit WHERE id = :id")
    suspend fun get(id: Long): VisitEntity?

    @Query("SELECT * FROM visit WHERE id = :id")
    fun observe(id: Long): Flow<VisitEntity?>

    @Query("DELETE FROM visit WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT photo_path FROM visit WHERE photo_path IS NOT NULL")
    suspend fun photoPaths(): List<String>

    @Query("SELECT MAX(seat_id) FROM visit WHERE hall_id = :hallId")
    suspend fun maxSeatId(hallId: Long): Int?

    @Query("SELECT COUNT(*) FROM visit WHERE hall_id = :hallId")
    suspend fun countForHall(hallId: Long): Int

    /** Newest first; the halls' layouts are read once per hall (see [VisitWithContext]), in the same transaction. */
    @Transaction
    @Query(
        """
        SELECT visit.*, hall.name AS hall_name, cinema.id AS cinema_id, cinema.name AS cinema_name,
            ticket.visit_id IS NOT NULL AS has_ticket
        FROM visit
        INNER JOIN hall ON hall.id = visit.hall_id
        INNER JOIN cinema ON cinema.id = hall.cinema_id
        LEFT JOIN ticket ON ticket.visit_id = visit.id
        ORDER BY visit.date DESC, visit.id DESC
        """,
    )
    fun observeAllWithContext(): Flow<List<VisitWithContext>>
}

/** Tickets (spec §4.7); the rules are in RoomTicketRepository. */
@Dao
internal interface TicketDao {
    @Query("SELECT * FROM ticket WHERE visit_id = :visitId")
    suspend fun get(visitId: Long): TicketEntity?

    @Query("SELECT * FROM ticket WHERE visit_id = :visitId")
    fun observe(visitId: Long): Flow<TicketEntity?>

    @Query("SELECT visit_id FROM ticket WHERE booking_number = :bookingNumber")
    suspend fun visitIdOf(bookingNumber: String): Long?

    /**
     * Inserts a ticket for a visit that has none. A booking number another ticket
     * has fails loudly (UNIQUE index, ABORT), like a second ticket for the visit.
     */
    @Insert
    suspend fun insert(ticket: TicketEntity)

    /**
     * Replaces the visit's ticket; returns 0 if the visit has none. Not an upsert:
     * Room's upsert would turn a UNIQUE conflict on the booking number into an
     * update of 0 rows, so the conflict would pass silently. Here it throws.
     */
    @Update
    suspend fun update(ticket: TicketEntity): Int

    @Query("DELETE FROM ticket WHERE visit_id = :visitId")
    suspend fun delete(visitId: Long): Int

    /** Only for the visit's ticket with [bookingNumber] that has no QR modules: one guarded statement. */
    @Query(
        """
        UPDATE ticket SET qr_modules = :qrModules
        WHERE visit_id = :visitId AND booking_number = :bookingNumber AND qr_modules IS NULL
        """,
    )
    suspend fun restoreQrModules(visitId: Long, bookingNumber: String, qrModules: String): Int

    @Query(
        """
        SELECT ticket.visit_id AS visit_id, visit.date AS date, visit.start_time AS start_time
        FROM ticket INNER JOIN visit ON visit.id = ticket.visit_id
        WHERE ticket.qr_modules IS NOT NULL
        """,
    )
    suspend fun showingTimesWithQrModules(): List<TicketShowingTime>

    /** Removes the QR modules of these tickets; at most [MAX_IDS_PER_QUERY] ids per call. */
    @Query("UPDATE ticket SET qr_modules = NULL WHERE visit_id IN (:visitIds) AND qr_modules IS NOT NULL")
    suspend fun clearQrModules(visitIds: List<Long>): Int
}

/** Ids bound in one `IN (…)` query: below SQLite's oldest limit of 999 bound parameters. */
internal const val MAX_IDS_PER_QUERY: Int = 500

@Dao
internal interface SeatOverrideDao {
    @Query("SELECT * FROM seat_override WHERE hall_id = :hallId")
    suspend fun getForHall(hallId: Long): List<SeatOverrideEntity>

    @Insert
    suspend fun insertAll(overrides: List<SeatOverrideEntity>)

    @Query("DELETE FROM seat_override WHERE hall_id = :hallId")
    suspend fun deleteForHall(hallId: Long)

    @Query("SELECT MAX(seat_id) FROM seat_override WHERE hall_id = :hallId")
    suspend fun maxSeatId(hallId: Long): Int?

    /** Stored overrides of the hall: every one has a colour or is the favourite (empty ones aren't stored). */
    @Query("SELECT COUNT(*) FROM seat_override WHERE hall_id = :hallId")
    suspend fun countForHall(hallId: Long): Int
}

@Dao
internal interface SeedSnapshotDao {
    @Query("SELECT * FROM seed_snapshot")
    suspend fun getAll(): List<SeedSnapshotEntity>

    @Upsert
    suspend fun putAll(entries: List<SeedSnapshotEntity>)
}

@Dao
internal interface MetadataDao {
    @Query("SELECT value FROM app_metadata WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: MetadataEntity)
}

/**
 * Whole-table reads and writes for the backup export and import. Reads are
 * ordered, so two snapshots of the same data compare equal. Use only inside a
 * transaction: a snapshot must be consistent and a replace atomic.
 */
@Dao
internal interface BackupDao {
    @Query("SELECT * FROM cinema ORDER BY id")
    suspend fun cinemas(): List<CinemaEntity>

    @Query("SELECT * FROM hall ORDER BY id")
    suspend fun halls(): List<HallEntity>

    @Query("SELECT * FROM visit ORDER BY id")
    suspend fun visits(): List<VisitEntity>

    @Query("SELECT * FROM seat_override ORDER BY hall_id, seat_id")
    suspend fun seatOverrides(): List<SeatOverrideEntity>

    @Query("SELECT * FROM ticket ORDER BY visit_id")
    suspend fun tickets(): List<TicketEntity>

    @Query("SELECT * FROM seed_snapshot ORDER BY kind, seed_key, field")
    suspend fun seedSnapshots(): List<SeedSnapshotEntity>

    @Query("SELECT * FROM app_metadata ORDER BY `key`")
    suspend fun metadata(): List<MetadataEntity>

    @Query("DELETE FROM seat_override")
    suspend fun clearSeatOverrides()

    @Query("DELETE FROM ticket")
    suspend fun clearTickets()

    @Query("DELETE FROM visit")
    suspend fun clearVisits()

    @Query("DELETE FROM hall")
    suspend fun clearHalls()

    @Query("DELETE FROM cinema")
    suspend fun clearCinemas()

    @Query("DELETE FROM seed_snapshot")
    suspend fun clearSeedSnapshots()

    @Query("DELETE FROM app_metadata")
    suspend fun clearMetadata()

    @Insert
    suspend fun insertCinemas(rows: List<CinemaEntity>)

    @Insert
    suspend fun insertHalls(rows: List<HallEntity>)

    @Insert
    suspend fun insertVisits(rows: List<VisitEntity>)

    @Insert
    suspend fun insertSeatOverrides(rows: List<SeatOverrideEntity>)

    @Insert
    suspend fun insertTickets(rows: List<TicketEntity>)

    @Insert
    suspend fun insertSeedSnapshots(rows: List<SeedSnapshotEntity>)

    @Insert
    suspend fun insertMetadata(rows: List<MetadataEntity>)
}
