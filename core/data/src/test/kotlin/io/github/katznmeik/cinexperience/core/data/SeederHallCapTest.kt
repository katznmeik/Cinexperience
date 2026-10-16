package io.github.katznmeik.cinexperience.core.data

import androidx.room.withTransaction
import io.github.katznmeik.cinexperience.core.data.backup.BackupValidator
import io.github.katznmeik.cinexperience.core.data.backup.readBackupData
import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.repository.DeleteResult
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import io.github.katznmeik.cinexperience.core.model.DataLimits.MAX_HALLS
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seeder never takes the database beyond [MAX_HALLS] halls (so seeded data
 * always exports): seed halls that don't fit are skipped, and a later seed version
 * adds them once there is room. Adopting a user hall adds none, so it still happens.
 */
internal class SeederHallCapTest : DatabaseTest() {

    private fun seed(version: Int) = """
        {"seedVersion": $version, "cinemas": [
          {"seedKey": "kino", "name": "Kino", "halls": [
            {"seedKey": "kino/1", "name": "Saal 1"},
            {"seedKey": "kino/a", "name": "Saal A"},
            {"seedKey": "kino/b", "name": "Saal B"},
            {"seedKey": "kino/c", "name": "Saal C", "screenWidth": 3, "screenDistance": 1.5,
             "layout": {"formatVersion": 1, "rows": [{"label": "1", "cells": "SSS"}]}}
          ]}
        ]}
    """

    private suspend fun seedIfNeeded(version: Int) = Seeder(db) { seed(version) }.seedIfNeeded()

    /** A user cinema "Kino" with [count] user halls "Saal 1" … */
    private suspend fun userHalls(count: Int): Long {
        val kino = cinemas.createCinema("Kino")
        db.withTransaction {
            repeat(count) { i ->
                db.hallDao().insert(
                    HallEntity(
                        cinemaId = kino, name = "Saal ${i + 1}", layoutJson = null, screenWidth = null, screenDistance = null,
                        referenceImagePath = null, notes = null, isPreloaded = false, seedKey = null,
                    ),
                )
            }
        }
        return kino
    }

    private suspend fun hallKeys(): Set<String> = db.hallDao().getAll().mapNotNull { it.seedKey }.toSet()

    private suspend fun assertExports() = BackupValidator.validate(db.readBackupData(), emptySet())

    @Test
    fun seedHallsBeyondTheCapAreSkippedAndAddedLaterWhenThereIsRoom() = runTest {
        userHalls(MAX_HALLS - 2)

        assertTrue(seedIfNeeded(1))
        // "Saal 1" is adopted, A and B fill the last two places, C doesn't fit.
        assertEquals(MAX_HALLS, db.hallDao().getAll().size)
        assertEquals(setOf("kino/1", "kino/a", "kino/b"), hallKeys())
        assertEquals("1", db.metadataDao().get(Seeder.KEY_SEED_VERSION))
        // Nothing is recorded for the skipped hall, so a later seed inserts it like a new one.
        assertTrue(db.seedSnapshotDao().getAll().none { it.seedKey == "kino/c" })
        assertExports()

        // The same seed version doesn't run again, even with room.
        val userHall = db.hallDao().getAll().first { it.seedKey == null }
        assertEquals(DeleteResult.DELETED, halls.deleteHall(userHall.id))
        assertFalse(seedIfNeeded(1))
        assertEquals(MAX_HALLS - 1, db.hallDao().getAll().size)

        // A later seed version adds it, with its layout.
        assertTrue(seedIfNeeded(2))
        assertEquals(MAX_HALLS, db.hallDao().getAll().size)
        val c = db.hallDao().getAll().single { it.seedKey == "kino/c" }
        assertFalse(c.isPreloaded) // like its cinema, which the user created
        assertNotNull(c.layoutJson)
        assertEquals(3f, c.screenWidth)
        assertTrue(db.seedSnapshotDao().getAll().any { it.seedKey == "kino/c" && it.field == "appliedLayout" && it.value != null })
        assertExports()
    }

    @Test
    fun atTheCapUserHallsAreStillAdopted() = runTest {
        userHalls(MAX_HALLS)

        assertTrue(seedIfNeeded(1))
        assertEquals(MAX_HALLS, db.hallDao().getAll().size)
        assertEquals(setOf("kino/1"), hallKeys())
        assertEquals("kino", db.cinemaDao().getAll().single().seedKey)
        assertExports()
    }
}
