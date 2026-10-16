package io.github.katznmeik.cinexperience.core.program

import io.github.katznmeik.cinexperience.core.program.TestPages.film
import io.github.katznmeik.cinexperience.core.program.TestPages.page
import io.github.katznmeik.cinexperience.core.program.TestPages.session
import io.github.katznmeik.cinexperience.core.program.TestPages.special
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec §11.1, Network & security rule 7, "invalid entries are skipped": a film or
 * special entry that isn't an object, or whose `fields` isn't one, is skipped like
 * any other invalid entry; the rest of the page is still read.
 */
class ProgramPageParserEntryTest {

    private val parser = ProgramPageParser(TestPages.clock)

    private fun success(page: String): ProgramParseResult.Success = when (val result = parser.parse(page)) {
        is ProgramParseResult.Success -> result
        is ProgramParseResult.Failure -> throw AssertionError("Expected a program, got ${result.reason}")
    }

    private fun failure(page: String): ProgramParseFailure = when (val result = parser.parse(page)) {
        is ProgramParseResult.Failure -> result.reason
        is ProgramParseResult.Success -> throw AssertionError("Expected a failure, got $result")
    }

    @Test
    fun theFixtureWithEntriesOfTheWrongTypeStillGivesItsProgram() {
        val fixture = TestPages.fixture
        val films = "\"films\": ["
        val specials = "\"specials\": ["
        // Exactly one place each, so the variant below really changes both lists.
        assertEquals(1, fixture.split(films).size - 1)
        assertEquals(1, fixture.split(specials).size - 1)
        val variant = fixture
            .replace(
                films,
                "$films\n" +
                    "        \"not a film\",\n" +
                    "        { \"sys\": { \"id\": \"zzzzzzzzz1\" }, \"fields\": \"x\" },\n" +
                    "        { \"sys\": { \"id\": \"zzzzzzzzz2\" }, \"fields\": [{ \"title\": \"In a list\", \"slug\": \"in-a-list\" }] },\n" +
                    "        42,\n",
            )
            .replace(
                specials,
                "$specials\n" +
                    "        [],\n" +
                    "        { \"sys\": { \"id\": \"zzzzzzzzz3\" }, \"fields\": 7 },\n",
            )

        val expected = success(fixture)
        val result = success(variant)
        assertEquals(expected.program, result.program)
        assertEquals(6, result.skippedFilms)
        assertEquals(0, result.skippedShowings)
    }

    @Test
    fun entriesAndFieldsThatArentObjectsAreSkipped() {
        val result = success(
            page(
                listOf(
                    JsonPrimitive("not a film"),
                    buildJsonObject { put("fields", JsonPrimitive("x")) },
                    buildJsonObject { put("fields", buildJsonArray { add(film(slug = "inside-a-list")) }) },
                    buildJsonObject { put("fields", JsonPrimitive(1)) },
                    buildJsonArray { add(film(slug = "film-in-an-array")) },
                    film(slug = "kept", sessions = listOf(session(id = "8-1"))),
                    JsonNull,
                ),
                listOf(JsonPrimitive(1), buildJsonObject { put("fields", JsonPrimitive(false)) }, special(slug = "kept-special", sessions = listOf(session(id = "8-2")))),
            ),
        )
        val program = result.program
        assertEquals(listOf(FilmKey(FilmKind.FILM, "kept"), FilmKey(FilmKind.SPECIAL, "kept-special")), program.films.map { it.key })
        assertEquals(listOf("8-1", "8-2"), program.showings.map { it.id }.sorted())
        assertEquals(8, result.skippedFilms)
        assertEquals(0, result.skippedShowings)
    }

    @Test
    fun aPageOfOnlyInvalidEntriesHasNothingValid() {
        val page = page(listOf(JsonPrimitive("x"), buildJsonObject { put("fields", JsonPrimitive("x")) }), listOf(JsonPrimitive(1)))
        assertEquals(ProgramParseFailure.NOTHING_VALID, failure(page))
    }

    @Test
    fun theStructureAroundTheEntriesStillHasToBeRight() {
        // A list of films or specials that isn't a list: the page is unreadable; the last good program stays.
        val data = listOf(
            "{\"props\":{\"pageProps\":{\"films\":[],\"specials\":\"x\"}}}",
            "{\"props\":{\"pageProps\":{\"films\":[{\"fields\":\"x\"}],\"specials\":{}}}}",
            "{\"props\":{\"pageProps\":{\"films\":\"x\",\"specials\":[]}}}",
            "{\"props\":{\"pageProps\":[{\"fields\":\"x\"}]}}",
            // Broken JSON after an entry of the wrong type is still broken JSON.
            "{\"props\":{\"pageProps\":{\"films\":[{\"fields\":\"x\"},{\"fields\":{\"title\":}]}}}",
        )
        for (block in data) assertEquals(block, ProgramParseFailure.INVALID_JSON, failure(page(block)))
        // Missing lists after an entry of the wrong type: as without one.
        assertEquals(ProgramParseFailure.UNEXPECTED_STRUCTURE, failure(page("{\"props\":{\"pageProps\":{\"specials\":[\"x\"]}}}")))
    }

    @Test
    fun theLimitsStillApplyWithEntriesOfTheWrongType() {
        val tooMany = List(ProgramRules.MAX_FILMS) { JsonPrimitive("x") } + film(slug = "one-too-many")
        assertEquals(ProgramParseFailure.TOO_MANY_FILMS, failure(page(tooMany)))
        val sessions = List(ProgramRules.MAX_SHOWINGS + 1) { index -> session(id = "9-$index") }
        assertEquals(ProgramParseFailure.TOO_MANY_SHOWINGS, failure(page(listOf(JsonPrimitive("x"), film(sessions = sessions)))))
        val deep = "[".repeat(ProgramPageParser.MAX_JSON_DEPTH) + "]".repeat(ProgramPageParser.MAX_JSON_DEPTH)
        assertEquals(ProgramParseFailure.TOO_COMPLEX, failure(page("{\"props\":{\"pageProps\":{\"films\":[\"x\",$deep]}}}")))
    }
}
