package io.github.katznmeik.cinexperience.core.program

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/** Why a page could not be turned into a [Program]. */
enum class ProgramParseFailure {
    /** The page has no (or more than one) `__NEXT_DATA__` script block. */
    NO_DATA_BLOCK,

    /**
     * The block is not valid JSON (e.g. truncated), or the structure that leads to
     * the entries has the wrong type (e.g. a film entry that isn't an object).
     */
    INVALID_JSON,

    /** The JSON has no `props.pageProps.films` list. */
    UNEXPECTED_STRUCTURE,

    /** The block has far more JSON values or deeper nesting than a program needs (checked before parsing). */
    TOO_COMPLEX,

    /** More than [ProgramRules.MAX_FILMS] films and specials. */
    TOO_MANY_FILMS,

    /** More than [ProgramRules.MAX_SHOWINGS] showings. */
    TOO_MANY_SHOWINGS,

    /** No valid showing remained after the checks. */
    NOTHING_VALID,
}

sealed interface ProgramParseResult {
    /**
     * [skippedFilms] and [skippedShowings] count the entries that failed a check or
     * were duplicates; a showing that a special lists too is linked, not skipped.
     */
    data class Success(val program: Program, val skippedFilms: Int, val skippedShowings: Int) : ProgramParseResult

    data class Failure(val reason: ProgramParseFailure) : ProgramParseResult
}

/**
 * Turns the text of Yorck's program page (`https://www.yorck.de/en/films`) into a
 * [Program] (spec §11.1, Network & security rule 7). A pure function of the page
 * text and [clock] (the download time and the allowed date range); it never
 * throws an exception.
 *
 * The page is untrusted. The data block is cut out by a plain text search for the
 * `<script id="__NEXT_DATA__"` element (no HTML parsing, no regex over the page).
 * A linear pre-scan bounds its size ([withinJsonLimits]); then it is decoded
 * into the parser's own small types with unknown keys skipped, so the page's
 * many other parts (images, banners, "coming soon") are never built in memory:
 *
 * - `props.pageProps.films[]` and `props.pageProps.specials[]`, each with `fields`:
 *   `title`, `slug`, `tagline` (specials: `specialSubline`), `mainLabel` (genre),
 *   `runtime` and `fsk` (number or digit string), `releaseDate` (yyyy-mm-dd) and
 *   `sessions[]`.
 * - Each session: `sys.id` (the showing ID), `fields.startTime`, `fields.formats`
 *   (labels) and `fields.cinema.fields.name`.
 *
 * Every used value is checked ([ProgramRules]). An invalid optional value (e.g. a
 * runtime of 0) is dropped; a film or showing with an invalid required value
 * (title, slug, ID, start, cinema) or of the wrong type is skipped. Only where the
 * structure itself breaks (an entry or its `fields` that isn't an object) is the
 * whole page rejected.
 *
 * Films and specials are keyed by [FilmKey] (kind + slug). Films come first: a
 * showing that Yorck lists under a film and under specials belongs to the film
 * and records the specials ([Showing.specialKeys]); a showing listed only under
 * specials belongs to the first one. Other repeated IDs and keys are skipped.
 * A film or special without any showing is left out. If no showing remains, the
 * page is rejected.
 *
 * Start times: Yorck labels every time `+01:00`, also in summer time, but means
 * the Berlin wall-clock time (a `15:00+01:00` showing in October is shown by
 * Yorck as 15:00). So the date-time part is taken as Berlin wall-clock time, and
 * only `+01:00`, `+02:00` or no offset is accepted; any other offset (`Z`,
 * `-05:00`, …) makes the showing invalid. See [ProgramTime] for clock changes.
 */
class ProgramPageParser(private val clock: Clock) {

    fun parse(page: String): ProgramParseResult = try {
        parseOrFail(page)
    } catch (e: Exception) {
        // Defensive: every check below returns a failure instead of throwing.
        ProgramParseResult.Failure(ProgramParseFailure.UNEXPECTED_STRUCTURE)
    }

    private fun parseOrFail(page: String): ProgramParseResult {
        val block = dataBlock(page) ?: return fail(ProgramParseFailure.NO_DATA_BLOCK)
        if (!withinJsonLimits(block)) return fail(ProgramParseFailure.TOO_COMPLEX)
        val data = decode(block) ?: return fail(ProgramParseFailure.INVALID_JSON)
        val pageProps = data.props?.pageProps ?: return fail(ProgramParseFailure.UNEXPECTED_STRUCTURE)
        val films = pageProps.films ?: return fail(ProgramParseFailure.UNEXPECTED_STRUCTURE)
        val specials = pageProps.specials.orEmpty()

        val entries = films.map { it to FilmKind.FILM } + specials.map { it to FilmKind.SPECIAL }
        if (entries.size > ProgramRules.MAX_FILMS) return fail(ProgramParseFailure.TOO_MANY_FILMS)
        val sessionCount = entries.sumOf { (entry, _) -> (entry?.fields?.sessions as? JsonArray)?.size ?: 0 }
        if (sessionCount > ProgramRules.MAX_SHOWINGS) return fail(ProgramParseFailure.TOO_MANY_SHOWINGS)

        val now = ProgramTime.now(clock)
        val startRange = now.minusDays(PAST_DAYS)..now.plusDays(FUTURE_DAYS)
        val releaseRange = EARLIEST_RELEASE..now.toLocalDate().plusYears(RELEASE_YEARS_AHEAD)

        val acceptedFilms = LinkedHashMap<FilmKey, Film>()
        val owned = LinkedHashMap<String, Showing>()
        val links = HashMap<String, LinkedHashSet<FilmKey>>()
        var skippedFilms = 0
        var skippedShowings = 0

        for ((entry, kind) in entries) {
            val sessions = (entry?.fields?.sessions as? JsonArray).orEmpty()
            val film = entry?.fields?.let { parseFilm(it, kind, releaseRange) }
            if (film == null || film.key in acceptedFilms) {
                skippedFilms++
                skippedShowings += sessions.size
                continue
            }
            val ownNew = LinkedHashMap<String, Showing>()
            val linkNew = LinkedHashSet<String>()
            for (session in sessions) {
                val showing = parseShowing(session, film.key, startRange)
                val ownerKey = showing?.let { owned[it.id]?.filmKey }
                when {
                    showing == null -> skippedShowings++
                    ownerKey == null && ownNew.putIfAbsent(showing.id, showing) == null -> Unit
                    // Listed again by a special: linked to the showing its film owns.
                    ownerKey != null && kind == FilmKind.SPECIAL && linkNew.add(showing.id) -> Unit
                    else -> skippedShowings++
                }
            }
            // Only an entry with a showing (own or linked) takes its key.
            if (ownNew.isNotEmpty() || linkNew.isNotEmpty()) {
                acceptedFilms[film.key] = film
                owned.putAll(ownNew)
                linkNew.forEach { id -> links.getOrPut(id) { LinkedHashSet() } += film.key }
            }
        }
        if (owned.isEmpty()) return fail(ProgramParseFailure.NOTHING_VALID)

        val showings = owned.values
            .map { showing -> links[showing.id]?.let { showing.copy(specialKeys = it.toList()) } ?: showing }
            .sortedWith(SHOWING_ORDER)
        val program = Program(clock.instant(), acceptedFilms.values.toList(), showings)
        return ProgramParseResult.Success(program, skippedFilms, skippedShowings)
    }

    private fun fail(reason: ProgramParseFailure) = ProgramParseResult.Failure(reason)

    private fun decode(block: String): PageDto? = try {
        json.decodeFromString(PageDto.serializer(), block)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: StackOverflowError) {
        null // can't happen within MAX_JSON_DEPTH; the stack has unwound by now
    }

    private fun parseFilm(fields: FieldsDto, kind: FilmKind, releaseRange: ClosedRange<LocalDate>): Film? {
        val slug = fields.slug.string()?.takeIf(ProgramRules::isValidSlug) ?: return null
        val title = ProgramText.cleanOrNull(fields.title.string(), ProgramRules.MAX_TITLE_LENGTH) ?: return null
        val tagline = optionalText(fields.tagline, ProgramRules.MAX_TAGLINE_LENGTH)
            ?: if (kind == FilmKind.SPECIAL) optionalText(fields.specialSubline, ProgramRules.MAX_TAGLINE_LENGTH) else null
        return guarded {
            Film(
                slug = slug,
                title = title,
                kind = kind,
                tagline = tagline,
                genre = optionalText(fields.mainLabel, ProgramRules.MAX_GENRE_LENGTH),
                runtimeMinutes = fields.runtime.smallNumber()?.takeIf { it in ProgramRules.RUNTIME_MINUTES },
                fsk = fields.fsk.smallNumber()?.takeIf { it in ProgramRules.FSK_RATINGS },
                releaseDate = parseDate(fields.releaseDate.string())?.takeIf { it in releaseRange },
            )
        }
    }

    private fun parseShowing(session: JsonElement, filmKey: FilmKey, startRange: ClosedRange<LocalDateTime>): Showing? {
        val obj = session.obj() ?: return null
        val id = obj["sys"].obj()?.get("id").string()?.takeIf(ProgramRules::isValidShowingId) ?: return null
        val fields = obj["fields"].obj() ?: return null
        val start = parseStart(fields["startTime"].string())?.takeIf { it in startRange } ?: return null
        val cinemaName = ProgramText.cleanOrNull(
            fields["cinema"].obj()?.get("fields").obj()?.get("name").string(),
            ProgramRules.MAX_CINEMA_NAME_LENGTH,
        ) ?: return null
        val labels = when (val formats = fields["formats"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> formats.take(ProgramRules.MAX_LABELS_PER_SHOWING).mapNotNull { it.string() }
            else -> return null
        }
        val classified = ProgramLabels.classify(labels)
        return guarded {
            Showing(
                id = id,
                filmKey = filmKey,
                start = start,
                cinema = ProgramCinema.fromYorckName(cinemaName),
                versions = classified.versions,
                eventLabels = classified.eventLabels,
            )
        }
    }

    /** Runs a model constructor whose checks the values above already passed; `null` if one still fails. */
    private inline fun <T> guarded(build: () -> T): T? = try {
        build()
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun optionalText(element: JsonElement?, maxLength: Int): String? =
        ProgramText.cleanOrNull(element.string(), maxLength)

    // The parts of the page that are used; everything else is skipped while decoding.
    // Values are JsonElement, so a value of the wrong type drops that value or entry only.

    @Serializable
    private class PageDto(val props: PropsDto? = null)

    @Serializable
    private class PropsDto(val pageProps: PagePropsDto? = null)

    @Serializable
    private class PagePropsDto(val films: List<EntryDto?>? = null, val specials: List<EntryDto?>? = null)

    @Serializable
    private class EntryDto(val fields: FieldsDto? = null)

    @Serializable
    private class FieldsDto(
        val title: JsonElement? = null,
        val slug: JsonElement? = null,
        val tagline: JsonElement? = null,
        val specialSubline: JsonElement? = null,
        val mainLabel: JsonElement? = null,
        val runtime: JsonElement? = null,
        val fsk: JsonElement? = null,
        val releaseDate: JsonElement? = null,
        val sessions: JsonElement? = null,
    )

    internal companion object {
        private const val MARKER = "<script id=\"__NEXT_DATA__\""
        private const val SCRIPT_END = "</script>"

        /** The opening tag (`<script id="__NEXT_DATA__" type="application/json">`) is short. */
        private const val MAX_TAG_LENGTH = 200

        /** Showings may start this long before the download (clock skew) … */
        private const val PAST_DAYS = 2L

        /** … and up to this far ahead (Yorck sells about five months ahead). */
        private const val FUTURE_DAYS = 400L

        private val EARLIEST_RELEASE: LocalDate = LocalDate.of(1888, 1, 1)
        private const val RELEASE_YEARS_AHEAD = 10L

        /**
         * Today's block has about 140,000 JSON values, nested up to 24 deep; a page
         * at the showing limit would need about 350,000.
         */
        const val MAX_JSON_VALUES: Int = 500_000
        const val MAX_JSON_DEPTH: Int = 64

        /** The order of [Program.showings]: start, then cinema, then ID. */
        val SHOWING_ORDER: Comparator<Showing> = compareBy<Showing>({ it.start }, { it.cinema.name }, { it.id })

        private val json = Json { ignoreUnknownKeys = true }

        // Date and time, optional seconds and fraction, and Berlin's offset or none.
        private val START_TIME = Regex(
            "([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,9})?)?)(?:\\+01:00|\\+02:00)?",
        )
        private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private val SMALL_NUMBER = Regex("[0-9]{1,4}")

        /**
         * The text between `<script id="__NEXT_DATA__" …>` and the next `</script>`,
         * found by plain text search; `null` if the page has none, or more than one
         * such element.
         */
        fun dataBlock(page: String): String? {
            val start = page.indexOf(MARKER)
            if (start < 0 || page.indexOf(MARKER, start + MARKER.length) >= 0) return null
            val tagEnd = page.indexOf('>', start + MARKER.length)
            if (tagEnd < 0 || tagEnd - start > MAX_TAG_LENGTH || page.indexOf('<', start + 1) in (start + 1) until tagEnd) {
                return null
            }
            val end = page.indexOf(SCRIPT_END, tagEnd + 1)
            if (end < 0) return null
            return page.substring(tagEnd + 1, end)
        }

        /**
         * True if [block] has at most [MAX_JSON_VALUES] values (counted as commas and
         * opening brackets outside strings) nested at most [MAX_JSON_DEPTH] deep. One
         * linear pass, no allocation; malformed JSON is left to the decoder.
         */
        fun withinJsonLimits(block: String): Boolean {
            var inString = false
            var escaped = false
            var depth = 0
            var values = 1
            for (ch in block) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> inString = false
                    }
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '{', '[' -> {
                        values++
                        if (++depth > MAX_JSON_DEPTH) return false
                    }
                    '}', ']' -> depth--
                    ',' -> values++
                }
                if (values > MAX_JSON_VALUES) return false
            }
            return true
        }

        /**
         * Yorck's start time as Berlin wall-clock time: `+01:00`, `+02:00` or no offset
         * (the offset itself is ignored, see the class comment); anything else is `null`.
         */
        fun parseStart(text: String?): LocalDateTime? {
            if (text == null || text.length > 40) return null
            val match = START_TIME.matchEntire(text) ?: return null
            return try {
                LocalDateTime.parse(match.groupValues[1])
            } catch (e: DateTimeParseException) {
                null
            }
        }

        fun parseDate(text: String?): LocalDate? {
            if (text == null || !DATE.matches(text)) return null
            return try {
                LocalDate.parse(text)
            } catch (e: DateTimeParseException) {
                null
            }
        }

        private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

        /** The value of a JSON string; `null` for anything else (numbers, null, objects). */
        private fun JsonElement?.string(): String? =
            (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

        /** A small non-negative integer given as a JSON number or a string of digits. */
        private fun JsonElement?.smallNumber(): Int? {
            val primitive = this as? JsonPrimitive ?: return null
            if (primitive is JsonNull) return null
            return primitive.content.takeIf(SMALL_NUMBER::matches)?.toInt()
        }
    }
}
