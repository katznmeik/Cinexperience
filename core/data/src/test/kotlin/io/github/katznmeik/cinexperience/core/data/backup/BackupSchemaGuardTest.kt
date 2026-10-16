package io.github.katznmeik.cinexperience.core.data.backup

import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.db.CinemaEntity
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.db.MetadataEntity
import io.github.katznmeik.cinexperience.core.data.db.SeatOverrideEntity
import io.github.katznmeik.cinexperience.core.data.db.SeedSnapshotEntity
import io.github.katznmeik.cinexperience.core.data.db.VisitEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.time.LocalDate

/**
 * Ties the Room schema to the backup (CLAUDE.md "A new table or column must also
 * reach the backup"). The round-trip tests compare backup snapshots with each
 * other, so they can't see a column the DTOs don't carry; these tests start from
 * the entities and the exported schema instead.
 */
internal class BackupSchemaGuardTest : BackupTestBase() {

    // Compares the imported rows exactly, so nothing may be seeded on top.
    override val seedAfterImport: Boolean = false

    /** Every table and how its rows get through the backup: entity → DTO → JSON → DTO → entity. */
    private val backupTables: List<BackupTable<*, *>> = listOf(
        BackupTable("cinema", CinemaEntity::class.java, BackupCinema.serializer(), { it.toBackup() }, { it.toEntity() }) { it.cinemas.size },
        BackupTable("hall", HallEntity::class.java, BackupHall.serializer(), { it.toBackup() }, { it.toEntity() }) { it.halls.size },
        BackupTable("visit", VisitEntity::class.java, BackupVisit.serializer(), { it.toBackup() }, { it.toEntity() }) { it.visits.size },
        BackupTable(
            "seat_override",
            SeatOverrideEntity::class.java,
            BackupSeatOverride.serializer(),
            { it.toBackup() },
            { it.toEntity() },
        ) { it.seatOverrides.size },
        BackupTable(
            "seed_snapshot",
            SeedSnapshotEntity::class.java,
            BackupSeedSnapshot.serializer(),
            { it.toBackup() },
            { it.toEntity() },
        ) { it.seedSnapshots.size },
        BackupTable("app_metadata", MetadataEntity::class.java, BackupMetadata.serializer(), { it.toBackup() }, { it.toEntity() }) {
            it.metadata.size
        },
    )

    @Test
    fun everyEntityFieldSurvivesTheBackupMapping() {
        for (table in backupTables) table.checkEveryFieldSurvives()
    }

    @Test
    fun theNewestSchemaHasExactlyTheBackupTablesAndEntityFields() {
        val (version, tables) = newestSchema()
        assertEquals("The newest exported schema is the database's version", db.openHelper.readableDatabase.version, version)
        assertEquals(
            "Tables of schema $version.json vs. the tables the backup reads and writes",
            tables.keys,
            backupTables.map { it.name }.toSet(),
        )
        for (table in backupTables) {
            // Every column is a constructor field of the entity, so the reflection test covers it.
            assertEquals("Columns of '${table.name}' in $version.json", tables.getValue(table.name), table.fieldNames().toSet())
        }
    }

    @Test
    fun theCompiledDatabaseHasTheTablesOfTheNewestSchema() {
        val (version, tables) = newestSchema()
        val live = db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            .filterNot { it.isInternalTable() }
            .toSet()
        assertEquals("Tables of the database vs. $version.json", tables.keys, live)
    }

    @Test
    fun exportAndImportCarryEveryRowOfEveryTable() = runTest {
        buildRealisticData()
        val tables = newestSchema().second.keys
        val data = deviceA.snapshot()
        for (table in backupTables) {
            val rows = dump(deviceA.db, table.name)
            assertTrue("buildRealisticData must fill '${table.name}', so the round trip checks it", rows.isNotEmpty())
            assertEquals("Rows of '${table.name}' the backup reads", rows.size, table.rowsIn(data))
        }
        val before = tables.associateWith { dump(deviceA.db, it) }

        val doc = deviceA.document("backup.zip")
        deviceA.repository.export(doc)
        val deviceB = newDevice("b")
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))

        // Raw rows, every column, straight from SQLite: nothing goes through the DTOs here.
        for (table in tables) assertEquals("Rows of '$table' after export and import", before.getValue(table), dump(deviceB.db, table))
    }

    /** The highest exported schema version and its tables (name → field names), Room's own tables left out. */
    private fun newestSchema(): Pair<Int, Map<String, Set<String>>> {
        // The exported schemas are test assets (AndroidRoomConventionPlugin), as for MigrationTest.
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val folder = CinexDatabase::class.java.name
        val version = assets.list(folder).orEmpty()
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
            .maxOrNull() ?: error("No exported schema in $folder (test assets)")
        val database = Json.parseToJsonElement(assets.open("$folder/$version.json").use { it.readBytes().decodeToString() })
            .jsonObject.getValue("database").jsonObject
        assertEquals(version, database.getValue("version").jsonPrimitive.int)
        val tables = database.getValue("entities").jsonArray.map { it.jsonObject }
            .filterNot { it.string("tableName").isInternalTable() }
            .associate { entity ->
                entity.string("tableName") to entity.getValue("fields").jsonArray.map { it.jsonObject.string("fieldPath") }.toSet()
            }
        return version to tables
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun String.isInternalTable(): Boolean =
        startsWith("room_") || startsWith("sqlite_") || this == "android_metadata"

    /** Every row of [table], every column with its SQLite type, sorted (row order doesn't matter). */
    private fun dump(database: CinexDatabase, table: String): List<String> =
        database.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        (0 until cursor.columnCount).joinToString(", ") { i ->
                            val value = when (cursor.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> "NULL"
                                Cursor.FIELD_TYPE_INTEGER -> "INTEGER ${cursor.getLong(i)}"
                                Cursor.FIELD_TYPE_FLOAT -> "REAL ${cursor.getDouble(i)}"
                                Cursor.FIELD_TYPE_STRING -> "TEXT ${cursor.getString(i)}"
                                else -> "BLOB ${cursor.getBlob(i).toList()}"
                            }
                            "${cursor.getColumnName(i)}=$value"
                        },
                    )
                }
            }.sorted()
        }

    private class BackupTable<E : Any, B>(
        val name: String,
        val entity: Class<E>,
        val serializer: KSerializer<B>,
        val toBackup: (E) -> B,
        val toEntity: (B) -> E,
        val rowsIn: (BackupData) -> Int,
    ) {
        /** The primary constructor (Kotlin's synthetic default-argument constructor left out). */
        private val constructor: Constructor<E> = entity.declaredConstructors
            .filter { c -> !c.isSynthetic && c.parameterTypes.none { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" } }
            .let { constructors ->
                @Suppress("UNCHECKED_CAST")
                constructors.singleOrNull() as Constructor<E>? ?: error("${entity.simpleName}: expected one primary constructor, found $constructors")
            }

        /** The data class properties, in constructor order. */
        fun fieldNames(): List<String> = entity.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }

        private fun roundTrip(row: E): E {
            val dto = toBackup(row)
            val json = BackupFormat.writeJson.encodeToString(serializer, dto)
            assertEquals("$name: the DTO survives data.json", dto, BackupFormat.strictJson.decodeFromString(serializer, json))
            return toEntity(dto)
        }

        fun checkEveryFieldSurvives() {
            val types = constructor.parameterTypes
            val names = fieldNames()
            assertEquals("${entity.simpleName}: one field per constructor parameter", types.size, names.size)

            // Every parameter set, all values distinct and none equal to a Kotlin default (0, false, 1, 0f).
            val base = types.mapIndexed { i, type -> value(type, i, alternative = false) }
            val row = constructor.newInstance(*base.toTypedArray())
            assertEquals("${entity.simpleName}: every field survives the backup", row, roundTrip(row))

            // Each parameter changed alone, so two fields of the same type (e.g. two booleans) can't be swapped.
            for (i in types.indices) {
                val args = base.toMutableList().also { it[i] = value(types[i], i, alternative = true) }
                val changed = constructor.newInstance(*args.toTypedArray())
                assertNotEquals("${entity.simpleName}.${names[i]}: the alternative value differs", row, changed)
                assertEquals("${entity.simpleName}.${names[i]} survives the backup", changed, roundTrip(changed))
            }
        }

        private fun value(type: Class<*>, index: Int, alternative: Boolean): Any {
            val n = index + if (alternative) 500 else 100
            return when (type) {
                java.lang.Long.TYPE, java.lang.Long::class.java -> 1_000L + n
                Integer.TYPE, Integer::class.java -> n
                java.lang.Float.TYPE, java.lang.Float::class.java -> n + 0.5f
                java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> !alternative
                String::class.java -> "$name-$index-${if (alternative) "b" else "a"}"
                LocalDate::class.java -> LocalDate.of(2026, 1, 1).plusDays(n.toLong())
                else -> fail("${entity.simpleName}: add a test value for parameter $index of type ${type.name}") as Nothing
            }
        }
    }
}
