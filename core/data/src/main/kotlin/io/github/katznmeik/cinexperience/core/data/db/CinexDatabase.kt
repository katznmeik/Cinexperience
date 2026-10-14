package io.github.katznmeik.cinexperience.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.katznmeik.cinexperience.core.data.DatabaseConfig
import java.time.LocalDate

/**
 * The app database.
 *
 * Schema changes: bump [version], add a [Migration] to [ALL_MIGRATIONS], commit
 * the newly exported `core/data/schemas/.../<version>.json` and add a case to
 * `MigrationTest`. There is deliberately no destructive fallback: losing the
 * user's ratings is never acceptable.
 */
@Database(
    entities = [
        CinemaEntity::class,
        HallEntity::class,
        VisitEntity::class,
        SeatOverrideEntity::class,
        SeedSnapshotEntity::class,
        MetadataEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
internal abstract class CinexDatabase : RoomDatabase() {
    abstract fun cinemaDao(): CinemaDao
    abstract fun hallDao(): HallDao
    abstract fun visitDao(): VisitDao
    abstract fun seatOverrideDao(): SeatOverrideDao
    abstract fun metadataDao(): MetadataDao
    abstract fun seedSnapshotDao(): SeedSnapshotDao
    abstract fun backupDao(): BackupDao

    companion object {
        /** Every migration, in order. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context): CinexDatabase =
            Room.databaseBuilder(context.applicationContext, CinexDatabase::class.java, DatabaseConfig.DATABASE_NAME)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}

/**
 * 1 → 2 (M8): favourite cinemas (`cinema.is_favourite`, spec §4.1) and the
 * screen's horizontal offset (`hall.screen_offset`, spec §4.2). Existing rows
 * keep all their data and get the defaults: not a favourite, screen centred.
 */
internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `cinema` ADD COLUMN `is_favourite` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `hall` ADD COLUMN `screen_offset` REAL NOT NULL DEFAULT 0")
    }
}

internal class Converters {
    @TypeConverter
    fun localDateToString(date: LocalDate?): String? = date?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}
