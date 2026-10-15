package io.github.katznmeik.cinexperience.core.program

import io.github.katznmeik.cinexperience.core.program.TestPages.film
import io.github.katznmeik.cinexperience.core.program.TestPages.page
import io.github.katznmeik.cinexperience.core.program.TestPages.session
import io.github.katznmeik.cinexperience.core.program.TestPages.special
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

class ProgramPageParserTest {

    private val parser = ProgramPageParser(TestPages.clock)

    private fun success(page: String): ProgramParseResult.Success = when (val result = parser.parse(page)) {
        is ProgramParseResult.Success -> result
        is ProgramParseResult.Failure -> throw AssertionError("Expected a program, got ${result.reason}")
    }

    private fun program(page: String): Program = success(page).program

    private fun failure(page: String): ProgramParseFailure = when (val result = parser.parse(page)) {
        is ProgramParseResult.Failure -> result.reason
        is ProgramParseResult.Success -> throw AssertionError("Expected a failure, got $result")
    }

    /** The program of a page with one film holding [sessions], or of [films]. */
    private fun programOf(vararg films: Any): Program = program(page(films.map { TestPages.toJson(it) }))

    // --- The fixture ---------------------------------------------------------------------------

    @Test
    fun parsesTheFixture() {
        val result = success(TestPages.fixture)
        val program = result.program

        assertEquals(TestPages.NOW, program.fetchedAt)
        assertEquals(
            listOf("the-quiet-projectionist", "neon-harbour", "paper-kites", "night-of-the-long-reels"),
            program.films.map { it.slug },
        )
        assertEquals(
            Film(
                slug = "the-quiet-projectionist",
                title = "The Quiet Projectionist",
                kind = FilmKind.FILM,
                tagline = "A night shift that never ends.",
                genre = "Drama",
                runtimeMinutes = 118,
                fsk = 12,
                releaseDate = LocalDate.of(2026, 10, 1),
            ),
            program.film("the-quiet-projectionist"),
        )
        // Runtime and FSK as strings.
        val neon = program.film("neon-harbour")!!
        assertEquals(97, neon.runtimeMinutes)
        assertEquals(16, neon.fsk)
        assertEquals(FilmKind.FILM, neon.kind) // the special with the same slug is skipped
        assertEquals(0, program.film("paper-kites")!!.fsk)
        // A special: no runtime, FSK, genre or release date; its subline is the tagline.
        assertEquals(
            Film(slug = "night-of-the-long-reels", title = "Night of the Long Reels", kind = FilmKind.SPECIAL, tagline = "Twelve hours of cinema."),
            program.film("night-of-the-long-reels"),
        )

        // Sorted by start; the special's copy of 1001-1001 and the duplicate-slug special are skipped.
        assertEquals(
            listOf("1001-1001", "1003-2001", "1002-1002", "1005-3001", "1004-2002", "1006-4001"),
            program.showings.map { it.id },
        )
        assertEquals(1, result.skippedFilms)
        assertEquals(2, result.skippedShowings)

        val first = program.showings.first()
        assertEquals(
            Showing(
                id = "1001-1001",
                filmSlug = "the-quiet-projectionist",
                start = LocalDateTime.of(2026, 10, 15, 17, 0),
                cinema = ProgramCinema("delphi LUX", "delphi-lux"),
                versions = listOf(ShowingVersion.OMU),
                eventLabels = emptyList(),
            ),
            first,
        )
        val byId = program.showings.associateBy { it.id }
        assertEquals(ProgramCinema("Kino International", "international"), byId.getValue("1002-1002").cinema)
        assertEquals(ProgramCinema("Blauer Stern", "blauer-stern"), byId.getValue("1003-2001").cinema)
        assertEquals(listOf(ShowingVersion.OMEU), byId.getValue("1003-2001").versions)
        assertEquals(listOf("Special"), byId.getValue("1003-2001").eventLabels)
        assertEquals(listOf("Premiere"), byId.getValue("1004-2002").eventLabels)
        assertEquals(listOf(ShowingVersion.DF), byId.getValue("1005-3001").versions)
        assertEquals(listOf("Kids"), byId.getValue("1005-3001").eventLabels)
        assertEquals(listOf("Festival", "In the Mood"), byId.getValue("1006-4001").eventLabels)
        assertEquals("night-of-the-long-reels", byId.getValue("1006-4001").filmSlug)
    }

    // --- Times ---------------------------------------------------------------------------------

    @Test
    fun theOffsetIsIgnoredAndTheTimeIsBerlinWallClockTime() {
        // Yorck labels every time +01:00, also in summer time; yorck.de shows this one as 15:00.
        val summer = programOf(film(sessions = listOf(session(startTime = "2026-10-15T15:00:00+01:00")))).showings.single()
        assertEquals(LocalDateTime.of(2026, 10, 15, 15, 0), summer.start)
        assertEquals(Instant.parse("2026-10-15T13:00:00Z"), summer.startInstant) // CEST, UTC+2

        val winter = programOf(film(sessions = listOf(session(startTime = "2026-11-20T15:00:00+01:00")))).showings.single()
        assertEquals(LocalDateTime.of(2026, 11, 20, 15, 0), winter.start)
        assertEquals(Instant.parse("2026-11-20T14:00:00Z"), winter.startInstant) // CET, UTC+1

        // Whatever the offset says, the wall-clock part counts.
        for (time in listOf("2026-10-15T15:00:00Z", "2026-10-15T15:00:00+02:00", "2026-10-15T15:00:00-05:00", "2026-10-15T15:00", "2026-10-15T15:00:00.000+01:00")) {
            val showing = programOf(film(sessions = listOf(session(startTime = time)))).showings.single()
            assertEquals(time, LocalDateTime.of(2026, 10, 15, 15, 0), showing.start)
        }
    }

    @Test
    fun invalidAndOutOfRangeStartTimesAreSkipped() {
        val bad = listOf(
            "2020-01-01T10:00:00+01:00", // long past
            "2026-10-12T10:00:00+01:00", // more than 2 days before the download
            "2028-01-01T10:00:00+01:00", // more than 400 days ahead
            "2026-13-40T10:00:00+01:00",
            "2026-02-30T10:00:00+01:00",
            "2026-10-16 17:00:00",
            "2026-10-16T25:00:00+01:00",
            "16.10.2026 17:00",
            "2026-10-16T17:00:00+01:00 ",
            "2026-10-16T17:00:00+01:00\n",
            "",
        )
        val sessions = bad.map { session(startTime = it) } +
            session(startTime = 1_760_000_000) +
            session(startTime = JsonNull) +
            session(startTime = null) +
            session(id = "1001-999999", startTime = "2026-10-14T10:00:00+01:00") // yesterday: kept
        val program = programOf(film(sessions = sessions))
        assertEquals(listOf("1001-999999"), program.showings.map { it.id })
    }

    // --- Missing data block, broken JSON, wrong structure ---------------------------------------

    @Test
    fun aPageWithoutTheDataBlockIsRejected() {
        assertEquals(ProgramParseFailure.NO_DATA_BLOCK, failure(""))
        assertEquals(ProgramParseFailure.NO_DATA_BLOCK, failure("<html><body><h1>Films</h1></body></html>"))
        assertEquals(ProgramParseFailure.NO_DATA_BLOCK, failure("<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{}}"))
        assertEquals(ProgramParseFailure.NO_DATA_BLOCK, failure("<script id=\"__NEXT_DATA__\""))
        assertEquals(ProgramParseFailure.NO_DATA_BLOCK, failure("<script id=\"__other__\">{}</script>"))
        // Two data blocks are ambiguous.
        val one = page(listOf(film()))
        assertEquals(ProgramParseFailure.NO_DATA_BLOCK, failure(one + one))
        // An opening tag that never ends (or swallows another tag).
        assertEquals(ProgramParseFailure.NO_DATA_BLOCK, failure("<script id=\"__NEXT_DATA__\" ${"x".repeat(500)}>{}</script>"))
        assertEquals(ProgramParseFailure.NO_DATA_BLOCK, failure("<script id=\"__NEXT_DATA__\" <b>{}</script>"))
    }

    @Test
    fun truncatedOrInvalidJsonIsRejected() {
        val data = TestPages.data(listOf(film()))
        assertEquals(ProgramParseFailure.INVALID_JSON, failure(page(data.dropLast(10))))
        assertEquals(ProgramParseFailure.INVALID_JSON, failure(page(data.take(data.length / 2))))
        assertEquals(ProgramParseFailure.INVALID_JSON, failure(page("")))
        assertEquals(ProgramParseFailure.INVALID_JSON, failure(page("{'props': 1}")))
        assertEquals(ProgramParseFailure.INVALID_JSON, failure(page("<html>not json</html>")))
        // The fixture cut off in the middle of its data block.
        val fixture = TestPages.fixture
        val cut = fixture.indexOf("\"specials\"")
        assertEquals(ProgramParseFailure.INVALID_JSON, failure(fixture.substring(0, cut) + "</script>"))
    }

    @Test
    fun anUnexpectedStructureIsRejected() {
        for (data in listOf("[]", "42", "null", "\"text\"", "{}", "{\"props\":[]}", "{\"props\":{\"pageProps\":{}}}", "{\"props\":{\"pageProps\":{\"films\":{}}}}", "{\"props\":{\"pageProps\":{\"films\":\"x\"}}}")) {
            assertEquals(data, ProgramParseFailure.UNEXPECTED_STRUCTURE, failure(page(data)))
        }
    }

    @Test
    fun deeplyNestedJsonIsRejectedBeforeParsing() {
        val nested = "[".repeat(200_000) + "]".repeat(200_000)
        assertEquals(ProgramParseFailure.TOO_COMPLEX, failure(page("{\"props\":{\"pageProps\":{\"films\":$nested}}}")))
        assertEquals(ProgramParseFailure.TOO_COMPLEX, failure(page("{\"a\":" + "{\"a\":".repeat(100_000))))
        // The limit: the block's own object plus 63 levels is fine for the scan.
        assertTrue(ProgramPageParser.withinJsonLimits("[".repeat(ProgramPageParser.MAX_JSON_DEPTH) + "]".repeat(ProgramPageParser.MAX_JSON_DEPTH)))
        assertFalse(ProgramPageParser.withinJsonLimits("[".repeat(ProgramPageParser.MAX_JSON_DEPTH + 1)))
    }

    @Test
    fun aPageOfCountlessTinyValuesIsRejectedBeforeParsing() {
        // About 5 MB of zeros would be millions of JSON tree nodes.
        val zeros = List(2_500_000) { "0" }.joinToString(",")
        assertEquals(ProgramParseFailure.TOO_COMPLEX, failure(page("{\"props\":{\"pageProps\":{\"films\":[$zeros]}}}")))
        assertTrue(ProgramPageParser.withinJsonLimits("[" + List(ProgramPageParser.MAX_JSON_VALUES - 2) { "0" }.joinToString(",") + "]"))
        assertFalse(ProgramPageParser.withinJsonLimits("[" + List(ProgramPageParser.MAX_JSON_VALUES) { "0" }.joinToString(",") + "]"))
        // Commas and brackets inside strings (escaped quotes too) don't count.
        val text = "\"a\\\"" + ",[{".repeat(1_000_000) + "\""
        assertTrue(ProgramPageParser.withinJsonLimits("[$text]"))
        val film = programOf(film(slug = "commas", tagline = ",[{".repeat(100))).films.single()
        assertEquals(",[{".repeat(100), film.tagline)
    }

    @Test
    fun nothingValidIsRejected() {
        assertEquals(ProgramParseFailure.NOTHING_VALID, failure(page(emptyList())))
        assertEquals(ProgramParseFailure.NOTHING_VALID, failure(page(listOf(film(sessions = emptyList<Any>())))))
        assertEquals(ProgramParseFailure.NOTHING_VALID, failure(page(listOf(film(slug = "Not A Slug")))))
        assertEquals(ProgramParseFailure.NOTHING_VALID, failure(page(listOf(film(sessions = listOf(session(id = "x")))))))
    }

    // --- Limits ---------------------------------------------------------------------------------

    @Test
    fun tooManyFilmsAreRejected() {
        val films = List(ProgramRules.MAX_FILMS + 1) { index -> film(slug = "film-$index", sessions = listOf(session(id = "1-$index"))) }
        assertEquals(ProgramParseFailure.TOO_MANY_FILMS, failure(page(films.take(1000), films.drop(1000))))
        // The limit itself is fine.
        assertEquals(ProgramRules.MAX_FILMS, program(page(films.dropLast(1))).films.size)
    }

    @Test
    fun tooManyShowingsAreRejected() {
        val sessions = List(ProgramRules.MAX_SHOWINGS + 1) { index -> session(id = "2-$index") }
        assertEquals(ProgramParseFailure.TOO_MANY_SHOWINGS, failure(page(listOf(film(sessions = sessions)))))
        assertEquals(
            ProgramParseFailure.TOO_MANY_SHOWINGS,
            failure(page(listOf(film(sessions = sessions.take(20_000))), listOf(special(sessions = sessions.drop(20_000))))),
        )
        assertEquals(ProgramRules.MAX_SHOWINGS, program(page(listOf(film(sessions = sessions.dropLast(1))))).showings.size)
    }

    // --- IDs and slugs --------------------------------------------------------------------------

    @Test
    fun invalidShowingIdsAreSkipped() {
        val bad = listOf(
            "", "1001", "1001-", "-89886", "1001_89886", "abc-123", "1001-89886-1", "1234567-1",
            "1-1234567890123", " 1001-1", "1001-1 ", "1001-1\n", "١٢-٣", "1001-1;x", "1001-1&a=b",
            "../1001-1", "1001-1#x",
        )
        val sessions = bad.map { session(id = it) } + session(id = 1001) + session(id = JsonNull) + session(id = null) +
            session(id = "123456-123456789012")
        assertEquals(listOf("123456-123456789012"), programOf(film(sessions = sessions)).showings.map { it.id })
    }

    @Test
    fun invalidSlugsAreSkipped() {
        val bad = listOf(
            "", "Upper-Case", "a--b", "-a", "a-", "a/b", "../x", "a b", "a_b", "über", "a.b", "a?b=c",
            "x".repeat(ProgramRules.MAX_SLUG_LENGTH + 1), "film\n", "film%2F",
        )
        val films = bad.map { film(slug = it) } + film(slug = 42) + film(slug = null) + film(slug = JsonNull) +
            film(slug = "x".repeat(ProgramRules.MAX_SLUG_LENGTH)) + film(slug = "2001-a-space-film")
        val program = program(page(films))
        assertEquals(listOf("x".repeat(ProgramRules.MAX_SLUG_LENGTH), "2001-a-space-film"), program.films.map { it.slug })
    }

    @Test
    fun duplicateShowingIdsAndSlugsAreSkipped() {
        val result = success(
            page(
                listOf(
                    film(slug = "first", sessions = listOf(session(id = "1-1"), session(id = "1-2"), session(id = "1-1"))),
                    film(slug = "second", sessions = listOf(session(id = "1-2"), session(id = "1-3"))),
                    film(slug = "first", title = "Another Film", sessions = listOf(session(id = "1-4"))),
                ),
                listOf(special(slug = "first", sessions = listOf(session(id = "1-5")))),
            ),
        )
        assertEquals(listOf("first", "second"), result.program.films.map { it.slug })
        assertEquals("An Invented Film", result.program.film("first")!!.title)
        assertEquals(setOf("1-1", "1-2", "1-3"), result.program.showings.map { it.id }.toSet())
        assertEquals("first", result.program.showings.single { it.id == "1-2" }.filmSlug)
        assertEquals(2, result.skippedFilms)
        assertEquals(4, result.skippedShowings)
    }

    @Test
    fun aFilmWithoutValidShowingsDoesNotTakeItsSlug() {
        val program = program(
            page(
                listOf(film(slug = "shared", title = "Film", sessions = listOf(session(id = "bad")))),
                listOf(special(slug = "shared", title = "Special", sessions = listOf(session(id = "5-1")))),
            ),
        )
        assertEquals(Film(slug = "shared", title = "Special", kind = FilmKind.SPECIAL, tagline = "A subline."), program.films.single())
    }

    // --- Texts ----------------------------------------------------------------------------------

    @Test
    fun controlAndFormatCharactersAreRemoved() {
        val film = programOf(
            film(
                slug = "clean",
                title = "\u0000The\u0007 Quiet​ Hour‮﻿ ",
                tagline = "Line one\nline two\t\ttabbed end\u0085",
                mainLabel = "‪Drama‬",
                sessions = listOf(session(cinema = "delphi LUX\u0000")),
            ),
        ).films.single()
        assertEquals("The Quiet Hour", film.title)
        assertEquals("Line one line two tabbed end", film.tagline)
        assertEquals("Drama", film.genre)
        // Non-breaking space is a space; the cinema still matches.
        val program = programOf(film(sessions = listOf(session(cinema = "delphi LUX\u0000"))))
        assertEquals(ProgramCinema("delphi LUX", "delphi-lux"), program.showings.single().cinema)
    }

    @Test
    fun aTitleOfOnlyInvisibleCharactersIsSkipped() {
        val program = program(
            page(listOf(film(slug = "invisible", title = "​\u0000 ‮"), film(slug = "visible", title = "Visible"))),
        )
        assertEquals(listOf("visible"), program.films.map { it.slug })
    }

    @Test
    fun htmlAndScriptLookingTextsStayPlainText() {
        val film = programOf(
            film(
                slug = "markup",
                title = "<script>alert('x')</script>",
                tagline = "<img src=x onerror=alert(1)> &amp; &lt;b&gt; </script>",
                mainLabel = "<b>Bold</b>",
            ),
        ).films.single()
        assertEquals("<script>alert('x')</script>", film.title)
        assertEquals("<img src=x onerror=alert(1)> &amp; &lt;b&gt; </script>", film.tagline)
        assertEquals("<b>Bold</b>", film.genre)
    }

    @Test
    fun textLengthsAreChecked() {
        val program = program(
            page(
                listOf(
                    film(slug = "long-title", title = "T".repeat(ProgramRules.MAX_TITLE_LENGTH + 1)),
                    film(slug = "max-title", title = "T".repeat(ProgramRules.MAX_TITLE_LENGTH), tagline = "x".repeat(ProgramRules.MAX_TAGLINE_LENGTH + 1), mainLabel = "g".repeat(ProgramRules.MAX_GENRE_LENGTH + 1)),
                    film(slug = "max-texts", tagline = "x".repeat(ProgramRules.MAX_TAGLINE_LENGTH), mainLabel = "g".repeat(ProgramRules.MAX_GENRE_LENGTH)),
                    // A character outside the BMP counts once.
                    film(slug = "emoji", title = "🎬".repeat(ProgramRules.MAX_TITLE_LENGTH)),
                    film(slug = "long-cinema", sessions = listOf(session(cinema = "C".repeat(ProgramRules.MAX_CINEMA_NAME_LENGTH + 1)))),
                ),
            ),
        )
        assertEquals(listOf("max-title", "max-texts", "emoji"), program.films.map { it.slug })
        val maxTitle = program.film("max-title")!!
        assertNull(maxTitle.tagline) // too long: dropped, the film stays
        assertNull(maxTitle.genre)
        assertEquals(ProgramRules.MAX_TAGLINE_LENGTH, program.film("max-texts")!!.tagline!!.length)
    }

    @Test
    fun unpairedSurrogatesAreRemoved() {
        val film = programOf(film(slug = "surrogates", title = "Half\uD83C a \uDFACpair")).films.single()
        assertEquals("Half a pair", film.title)
    }

    // --- Numbers and dates ----------------------------------------------------------------------

    @Test
    fun runtimeAndFskAreChecked() {
        fun filmWith(runtime: Any?, fsk: Any?) = programOf(film(slug = "numbers", runtime = runtime, fsk = fsk)).films.single()

        assertEquals(132, filmWith("132", "12").runtimeMinutes)
        assertEquals(1, filmWith(1, 0).runtimeMinutes)
        assertEquals(600, filmWith(600, 18).runtimeMinutes)
        for (runtime in listOf(0, 601, -5, "-5", "abc", "1e2", 132.5, "132 min", true, JsonNull, null, "", " 132", listOf(132))) {
            assertNull("$runtime", filmWith(runtime, 12).runtimeMinutes)
        }
        for (fsk in listOf(0, 6, 12, 16, 18)) {
            assertEquals(fsk, filmWith(100, fsk).fsk)
            assertEquals(fsk, filmWith(100, fsk.toString()).fsk)
        }
        for (fsk in listOf(7, 21, -1, "FSK 12", "12+", 12.0, false, JsonNull, null, mapOf("value" to 12))) {
            assertNull("$fsk", filmWith(100, fsk).fsk)
        }
    }

    @Test
    fun releaseDatesAreChecked() {
        fun releaseOf(value: Any?) = programOf(film(slug = "release", releaseDate = value)).films.single().releaseDate

        assertEquals(LocalDate.of(2026, 10, 1), releaseOf("2026-10-01"))
        assertEquals(LocalDate.of(1927, 1, 10), releaseOf("1927-01-10"))
        for (value in listOf("2026-02-30", "0001-01-01", "2099-01-01", "2026-10-01T00:00:00", "01.10.2026", "", 2026, JsonNull, null)) {
            assertNull("$value", releaseOf(value))
        }
    }

    @Test
    fun missingOptionalFieldsAreFine() {
        val minimal = buildJsonObject {
            put("fields", TestPages.toJson(mapOf("title" to "Minimal", "slug" to "minimal", "sessions" to listOf(session(formats = null)))))
        }
        val program = program(page(listOf(minimal), specials = null))
        assertEquals(Film(slug = "minimal", title = "Minimal"), program.films.single())
        val showing = program.showings.single()
        assertEquals(emptyList<ShowingVersion>(), showing.versions)
        assertEquals(emptyList<String>(), showing.eventLabels)
    }

    @Test
    fun wrongTypesSkipTheEntryOrDropTheValue() {
        val program = program(
            page(
                listOf(
                    film(slug = "number-title", title = 42),
                    film(slug = "null-title", title = JsonNull),
                    film(slug = "object-title", title = mapOf("text" to "x")),
                    film(slug = "sessions-object", sessions = mapOf("a" to session())),
                    JsonPrimitive("not a film"),
                    JsonNull,
                    buildJsonObject { put("fields", JsonPrimitive("x")) },
                    film(
                        slug = "typed",
                        tagline = 7,
                        mainLabel = listOf("Drama"),
                        sessions = listOf(
                            session(id = "3-1", formats = "OmU"), // not an array: skipped
                            session(id = "3-2", cinema = 5),
                            session(id = "3-3", cinema = null),
                            session(id = "3-4", formats = listOf(1, JsonNull, "OV", listOf("DF"))),
                            JsonPrimitive("not a session"),
                            buildJsonObject { put("sys", JsonPrimitive("3-5")) },
                        ),
                    ),
                ),
            ),
        )
        val typed = program.films.single()
        assertEquals("typed", typed.slug)
        assertNull(typed.tagline)
        assertNull(typed.genre)
        val showing = program.showings.single()
        assertEquals("3-4", showing.id)
        assertEquals(listOf(ShowingVersion.OV), showing.versions)
    }

    // --- Cinemas and labels ---------------------------------------------------------------------

    @Test
    fun unknownCinemasKeepYorcksNameWithoutAMatch() {
        val showings = programOf(
            film(sessions = listOf(session(id = "4-1", cinema = "Kino Unbekannt"), session(id = "4-2", cinema = "  kino   INTERNATIONAL "))),
        ).showings.associateBy { it.id }
        assertEquals(ProgramCinema("Kino Unbekannt", null), showings.getValue("4-1").cinema)
        assertEquals(ProgramCinema("kino INTERNATIONAL", "international"), showings.getValue("4-2").cinema)
    }

    @Test
    fun labelsBecomeVersionsAndEventTexts() {
        val labels = listOf(
            "OmU", "omu", "DF", " OV ", "OmeU", "KF", "SV", "InTheMood", "Festival", "Premiere", "Sneak", "Mongay",
            "Creepy C", "Lesung", "L".repeat(25), "Q\u0000&​A", "",
        )
        val showing = programOf(film(sessions = listOf(session(formats = labels)))).showings.single()
        assertEquals(listOf(ShowingVersion.OMU, ShowingVersion.DF, ShowingVersion.OV, ShowingVersion.OMEU), showing.versions)
        // At most 16 labels are looked at: the empty 17th doesn't matter, the 25-character one is dropped.
        assertEquals(
            listOf("Kids", "Special", "In the Mood", "Festival", "Premiere", "Sneak", "Mongay", "Creepy C", "Lesung", "Q&A"),
            showing.eventLabels,
        )
        val many = programOf(film(sessions = listOf(session(formats = List(40) { "Label $it" })))).showings.single()
        assertEquals(ProgramRules.MAX_LABELS_PER_SHOWING, many.eventLabels.size)
    }

    // --- Robustness -----------------------------------------------------------------------------

    @Test
    fun neverThrowsOnMangledPages() {
        val random = Random(20261015)
        val fixture = TestPages.fixture
        val start = fixture.indexOf("<script id=\"__NEXT_DATA__\"")
        repeat(300) {
            val chars = fixture.toCharArray()
            repeat(1 + random.nextInt(20)) {
                val index = start + random.nextInt(chars.size - start)
                chars[index] = "{}[]\",:0a-\\\u0000<>/ ".random(random)
            }
            try {
                parser.parse(String(chars))
            } catch (e: Throwable) {
                fail("parse threw $e")
            }
        }
        repeat(100) {
            val garbage = String(CharArray(random.nextInt(2000)) { random.nextInt(0, 0x3000).toChar() })
            parser.parse(garbage)
            parser.parse(page(garbage))
        }
    }
}
