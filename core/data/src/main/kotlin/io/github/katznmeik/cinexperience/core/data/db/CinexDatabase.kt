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
import java.time.LocalTime
import java.util.Locale

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
        TicketEntity::class,
    ],
    version = 3,
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
    abstract fun ticketDao(): TicketDao

    companion object {
        /** Every migration, in order. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun build(context: Context): CinexDatabase =
            Room.databaseBuilder(context.applicationContext, CinexDatabase::class.java, DatabaseConfig.DATABASE_NAME)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}

/**
 * 1 → 2: favourite cinemas (`cinema.is_favourite`, spec §4.1) and the
 * screen's horizontal offset (`hall.screen_offset`, spec §4.2). Existing rows
 * keep all their data and get the defaults: not a favourite, screen centred.
 */
internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `cinema` ADD COLUMN `is_favourite` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `hall` ADD COLUMN `screen_offset` REAL NOT NULL DEFAULT 0")
    }
}

/**
 * 2 → 3 (v1.4): a visit's start time (`visit.start_time`, spec §4.4) and the
 * `ticket` table (spec §4.7). Existing visits keep all their data and have no
 * start time; there are no tickets yet.
 */
internal val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `visit` ADD COLUMN `start_time` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ticket` (`visit_id` INTEGER NOT NULL, `booking_number` TEXT NOT NULL, " +
                "`seats` TEXT NOT NULL, `qr_modules` TEXT, PRIMARY KEY(`visit_id`), " +
                "FOREIGN KEY(`visit_id`) REFERENCES `visit`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ticket_booking_number` ON `ticket` (`booking_number`)")
    }
}

internal class Converters {
    @TypeConverter
    fun localDateToString(date: LocalDate?): String? = date?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    /** A visit's start time as "HH:mm" (Visit.startTime is always to the minute). */
    @TypeConverter
    fun localTimeToString(time: LocalTime?): String? = time?.let(StartTimes::format)

    /** A text that isn't "HH:mm" (only damaged data) reads as no start time instead of failing a Flow. */
    @TypeConverter
    fun stringToLocalTime(value: String?): LocalTime? = value?.let(StartTimes::parseOrNull)
}

/** The "HH:mm" form of a visit's start time, in the database and in backups. */
internal object StartTimes {
    private val PATTERN = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

    /** [time] as "HH:mm"; seconds would be dropped, but a visit's start time has none. */
    fun format(time: LocalTime): String = "%02d:%02d".format(Locale.ROOT, time.hour, time.minute)

    /** Whether [text] is a valid "HH:mm" start time, exactly as [format] writes it. */
    fun isValid(text: String): Boolean = PATTERN.matches(text)

    /** [text] as a time, or `null` if it isn't "HH:mm". */
    fun parseOrNull(text: String): LocalTime? =
        if (isValid(text)) LocalTime.of(text.substring(0, 2).toInt(), text.substring(3, 5).toInt()) else null
}
