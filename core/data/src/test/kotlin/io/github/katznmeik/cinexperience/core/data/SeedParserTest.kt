package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.seed.SeedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SeedParserTest {

    /** Gradle runs unit tests with the module directory as working directory. */
    private val asset = File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}")

    @Test
    fun shippedSeedAssetIsValid() {
        val seed = SeedParser.parse(asset.readText())
        assertEquals(2, seed.seedVersion)
        assertEquals(13, seed.cinemas.size)
        assertEquals(36, seed.cinemas.sumOf { it.halls.size })
        assertTrue(seed.cinemas.all { it.address != null })
        // Hall keys are namespaced by their cinema key.
        seed.cinemas.forEach { cinema ->
            cinema.halls.forEach { hall -> assertTrue(hall.seedKey, hall.seedKey.startsWith("${cinema.seedKey}/")) }
        }
        val lux = seed.cinemas.single { it.seedKey == "delphi-lux" }
        assertEquals("delphi LUX", lux.name)
        // The seventh hall is the Luxbox; its seed key stays "saal-7".
        assertEquals((1..6).map { "Saal $it" } + "Luxbox", lux.halls.map { it.name })
        assertEquals((1..7).map { "delphi-lux/saal-$it" }, lux.halls.map { it.seedKey })
        assertEquals(
            listOf("Yorck", "New Yorck"),
            seed.cinemas.single { it.seedKey == "yorck" }.halls.map { it.name },
        )
    }

    @Test
    fun nullAddressMissingAndEmptyHallsAreAccepted() {
        val seed = SeedParser.parse(
            """
            {"seedVersion": 3, "cinemas": [
              {"seedKey": "a", "name": "A", "address": null, "halls": []},
              {"seedKey": "b", "name": "B"}
            ]}
            """,
        )
        assertEquals(3, seed.seedVersion)
        assertNull(seed.cinemas[0].address)
        assertTrue(seed.cinemas[1].halls.isEmpty())
    }

    @Test
    fun hallWithLayoutIsAccepted() {
        val seed = SeedParser.parse(
            """
            {"seedVersion": 1, "cinemas": [{"seedKey": "a", "name": "A", "halls": [
              {"seedKey": "a/1", "name": "Saal 1", "screenWidth": 8, "screenDistance": 2.5,
               "layout": {"formatVersion": 1, "rows": [{"cells": "SS|SS"}]}},
              {"seedKey": "a/2", "name": "Saal 2",
               "layout": {"formatVersion": 2, "rows": [{"cells": "WC|SF"}]}}
            ]}]}
            """,
        )
        assertEquals(8f, seed.cinemas[0].halls[0].screenWidth)
        assertEquals("WC|SF", seed.cinemas[0].halls[1].layout!!.rows[0].cells)
    }

    @Test
    fun screenOffsetIsOptional() {
        val seed = SeedParser.parse(
            """
            {"seedVersion": 1, "cinemas": [{"seedKey": "a", "name": "A", "halls": [
              {"seedKey": "a/1", "name": "Saal 1", "screenOffset": -1.5},
              {"seedKey": "a/2", "name": "Saal 2"}
            ]}]}
            """,
        )
        assertEquals(-1.5f, seed.cinemas[0].halls[0].screenOffset)
        assertNull(seed.cinemas[0].halls[1].screenOffset)
        // The shipped seed has no offsets yet: every hall starts centred.
        assertTrue(SeedParser.parse(asset.readText()).cinemas.flatMap { it.halls }.all { it.screenOffset == null })
    }

    @Test
    fun invalidSeedsAreRejected() {
        val invalid = listOf(
            """{"seedVersion": 0, "cinemas": []}""",
            """{"cinemas": []}""",
            """{"seedVersion": 1, "cinemas": [{"seedKey": "a", "name": "A"}, {"seedKey": "a", "name": "B"}]}""",
            """{"seedVersion": 1, "cinemas": [{"seedKey": " ", "name": "A"}]}""",
            """{"seedVersion": 1, "cinemas": [{"seedKey": "a", "name": ""}]}""",
            """{"seedVersion": 1, "cinemas": [
                 {"seedKey": "a", "name": "A", "halls": [{"seedKey": "x", "name": "1"}]},
                 {"seedKey": "b", "name": "B", "halls": [{"seedKey": "x", "name": "1"}]}]}""",
            """{"seedVersion": 1, "cinemas": [{"seedKey": "a", "name": "A", "halls": [
                 {"seedKey": "a/1", "name": "1", "layout": {"formatVersion": 1, "rows": [{"cells": "?"}]}}]}]}""",
            """{"seedVersion": 1, "cinemas": [{"seedKey": "a", "name": "A", "halls": [
                 {"seedKey": "a/1", "name": "1", "screenOffset": 0.25}]}]}""",
            """{"seedVersion": 1, "cinemas": [{"seedKey": "a", "name": "A", "halls": [
                 {"seedKey": "a/1", "name": "1", "screenOffset": -40}]}]}""",
        )
        invalid.forEach { json ->
            assertThrows(json, IllegalArgumentException::class.java) { SeedParser.parse(json) }
        }
    }
}
