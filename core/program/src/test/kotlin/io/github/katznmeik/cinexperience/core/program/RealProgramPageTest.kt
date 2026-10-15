package io.github.katznmeik.cinexperience.core.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Optional local check against a saved copy of Yorck's real page, which is never
 * committed (Yorck's content is copyrighted). Skipped unless `CINEX_PROGRAM_PAGE`
 * names the file:
 *
 * ```
 * CINEX_PROGRAM_PAGE=/path/to/films.html ./gradlew :core:program:testDebugUnitTest \
 *     --tests '*RealProgramPageTest*' --rerun
 * ```
 *
 * It parses the page with the file's modification time as "now" and prints the
 * counts (in the test report's standard output).
 */
class RealProgramPageTest {

    @Test
    fun parsesASavedCopyOfTheRealPage() {
        val path = System.getenv("CINEX_PROGRAM_PAGE")
        assumeTrue("CINEX_PROGRAM_PAGE is not set", !path.isNullOrBlank())
        val file = File(path!!)
        assumeTrue("$path is not a file", file.isFile)

        val clock = Clock.fixed(Instant.ofEpochMilli(file.lastModified()), ZoneOffset.UTC)
        val result = ProgramPageParser(clock).parse(file.readText())
        assertTrue("Parsing failed: $result", result is ProgramParseResult.Success)
        val success = result as ProgramParseResult.Success
        val program = success.program

        val cinemas = program.showings.groupBy { it.cinema }
        val report = buildString {
            appendLine("Page: ${file.length()} bytes, now = ${clock.instant()}")
            appendLine("Films: ${program.films.size} (${program.films.count { it.kind == FilmKind.FILM }} films, ${program.films.count { it.kind == FilmKind.SPECIAL }} specials)")
            appendLine("Showings: ${program.showings.size}")
            appendLine("Skipped: ${success.skippedFilms} films, ${success.skippedShowings} showings (invalid or duplicate)")
            appendLine("Dates: ${program.showings.first().start} … ${program.showings.last().start}")
            appendLine("Cinemas: ${cinemas.size}, matched ${cinemas.keys.count { it.seedKey != null }}")
            cinemas.entries.sortedBy { it.key.name }.forEach { (cinema, showings) ->
                appendLine("  ${cinema.name} -> ${cinema.seedKey} (${showings.size})")
            }
            appendLine("Versions: " + ShowingVersion.entries.joinToString { version -> "${version.label} ${program.showings.count { version in it.versions }}" })
            appendLine("Without version: ${program.showings.count { it.versions.isEmpty() }}")
            appendLine("Event labels: " + program.showings.flatMap { it.eventLabels }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString())
            appendLine("Films with runtime ${program.films.count { it.runtimeMinutes != null }}, FSK ${program.films.count { it.fsk != null }}, genre ${program.films.count { it.genre != null }}, tagline ${program.films.count { it.tagline != null }}, release date ${program.films.count { it.releaseDate != null }}")
        }
        println(report)

        assertTrue(program.films.isNotEmpty())
        assertEquals("Every Yorck cinema matches an app cinema", emptyList<String>(), cinemas.keys.filter { it.seedKey == null }.map { it.name })
        assertTrue(program.showings.all { ProgramLinks.showing(it.id) != null })
        assertTrue(program.films.all { ProgramLinks.page(it) != null })
    }
}
