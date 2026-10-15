package io.github.katznmeik.cinexperience.core.program

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class YorckCinemasTest {

    /** The names yorck.de used on 15 Oct 2026, exactly as on the page. */
    private val yorckNames = mapOf(
        "delphi LUX" to "delphi-lux",
        "Kant Kino" to "kant-kino",
        "Filmtheater am Friedrichshain" to "filmtheater-am-friedrichshain",
        "Rollberg" to "rollberg",
        "Passage" to "passage",
        "Yorck" to "yorck",
        "Blauer Stern " to "blauer-stern",
        "Babylon Kreuzberg" to "babylon-kreuzberg",
        "Kino International" to "international",
        "Odeon" to "odeon",
        "Cinema Paris" to "cinema-paris",
        "Neues Off" to "neues-off",
        "Delphi Filmpalast" to "delphi-filmpalast",
        "Capitol Dahlem" to "capitol-dahlem",
    )

    @Test
    fun everyYorckNameMatchesItsCinema() {
        yorckNames.forEach { (name, key) -> assertEquals(name, key, YorckCinemas.seedKeyFor(name)) }
    }

    @Test
    fun matchingIgnoresCaseAndSurroundingOrRepeatedSpaces() {
        assertEquals("delphi-lux", YorckCinemas.seedKeyFor("DELPHI LUX"))
        assertEquals("blauer-stern", YorckCinemas.seedKeyFor("  blauer   stern\t"))
        assertEquals("international", YorckCinemas.seedKeyFor("kino international"))
    }

    @Test
    fun unknownNamesHaveNoMatch() {
        for (name in listOf("", " ", "International", "delphi", "Kino Unbekannt", "delphi LUX 2", "Yorck Kino")) {
            assertNull(name, YorckCinemas.seedKeyFor(name))
        }
    }

    @Test
    fun theTableCoversExactlyTheSeedCinemas() {
        val seed = Json.parseToJsonElement(File("../data/src/main/assets/seed/yorck.json").readText()).jsonObject
        val seedKeys = seed.getValue("cinemas").jsonArray.map { it.jsonObject.getValue("seedKey").jsonPrimitive.content }.toSet()
        assertEquals(14, seedKeys.size)
        assertEquals(seedKeys, YorckCinemas.seedKeys)
        assertEquals(seedKeys, yorckNames.values.toSet())
    }
}
