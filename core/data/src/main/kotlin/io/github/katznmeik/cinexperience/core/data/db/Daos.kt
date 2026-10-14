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

/** A visit with the names needed for the "All visits" list. */
internal data class VisitWithContext(
    @Embedded val visit: VisitEntity,
    @ColumnInfo(name = "hall_name") val hallName: String,
    @ColumnInfo(name = "hall_layout_json") val hallLayoutJson: String?,
    @ColumnInfo(name = "cinema_id") val cinemaId: Long,
    @ColumnInfo(name = "cinema_name") val cinemaName: String,
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

    @Query(
        """
        SELECT visit.*, hall.name AS hall_name, hall.layout_json AS hall_layout_json,
               cinema.id AS cinema_id, cinema.name AS cinema_name
        FROM visit
        INNER JOIN hall ON hall.id = visit.hall_id
        INNER JOIN cinema ON cinema.id = hall.cinema_id
        ORDER BY visit.date DESC, visit.id DESC
        """,
    )
    fun observeAllWithContext(): Flow<List<VisitWithContext>>
}

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
