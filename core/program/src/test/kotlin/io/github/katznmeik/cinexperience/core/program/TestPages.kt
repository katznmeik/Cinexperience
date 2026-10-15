package io.github.katznmeik.cinexperience.core.program

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Test data: the synthetic fixture page and a builder for variants of its structure (all invented). */
internal object TestPages {

    /** 15 Oct 2026, 10:00 in Berlin (summer time, UTC+2). */
    val NOW: Instant = Instant.parse("2026-10-15T08:00:00Z")
    val clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    /** `src/test/resources/program/films-page.html`: the page's structure with invented content. */
    val fixture: String by lazy { File("src/test/resources/program/films-page.html").readText() }

    /**
     * A page around [data] like Yorck's: HTML with the JSON in the `__NEXT_DATA__`
     * script element. Like Next.js, `<` is written as `\u003c` inside the block, so a
     * text containing `</script>` can't end it early.
     */
    fun page(data: String): String =
        "<!DOCTYPE html><html lang=\"en\"><head><title>Films</title>" +
            "<script src=\"/_next/static/chunks/main.js\"></script></head>" +
            "<body><div id=\"__next\"><h1>Films</h1></div>" +
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">${data.replace("<", "\\u003c")}</script>" +
            "<script src=\"/_next/static/chunks/pages/films.js\"></script></body></html>"

    fun page(films: List<JsonElement>, specials: List<JsonElement>? = emptyList()): String = page(data(films, specials))

    fun data(films: List<JsonElement>, specials: List<JsonElement>? = emptyList()): String = buildJsonObject {
        putJsonObject("props") {
            putJsonObject("pageProps") {
                put("films", JsonArray(films))
                if (specials != null) put("specials", JsonArray(specials))
                put("comingSoon", JsonArray(emptyList()))
            }
        }
        put("page", "/films")
        put("buildId", "test")
    }.toString()

    private var nextFilm = 1

    /** A film entry; pass [JsonNull] (or leave out with `null`) to omit a field, any element for wrong types. */
    fun film(
        slug: Any? = "film-${nextFilm++}",
        title: Any? = "An Invented Film",
        sessions: Any? = listOf(session()),
        tagline: Any? = "A tagline.",
        mainLabel: Any? = "Drama",
        runtime: Any? = 100,
        fsk: Any? = 12,
        releaseDate: Any? = "2026-10-01",
        extra: Map<String, Any?> = emptyMap(),
    ): JsonObject = buildJsonObject {
        putJsonObject("sys") { put("id", "x${nextFilm}") }
        put(
            "fields",
            fields(
                mapOf(
                    "title" to title,
                    "slug" to slug,
                    "tagline" to tagline,
                    "mainLabel" to mainLabel,
                    "runtime" to runtime,
                    "fsk" to fsk,
                    "releaseDate" to releaseDate,
                    "sessions" to sessions,
                    "vistaId" to "HO1",
                    "heroImage" to mapOf("fields" to mapOf("url" to "//images.example.invalid/x.jpg")),
                ) + extra,
            ),
        )
    }

    /** A special entry (no runtime, FSK or genre; a subline instead of a tagline). */
    fun special(slug: Any? = "special-${nextFilm++}", title: Any? = "An Invented Special", sessions: Any? = listOf(session()), subline: Any? = "A subline."): JsonObject =
        buildJsonObject {
            putJsonObject("sys") { put("id", "s${nextFilm}") }
            put(
                "fields",
                fields(
                    mapOf(
                        "title" to title,
                        "slug" to slug,
                        "type" to "Special Screening",
                        "specialSubline" to subline,
                        "sessions" to sessions,
                        "startDate" to "2026-10-15",
                    ),
                ),
            )
        }

    private var nextSession = 1

    fun session(
        id: Any? = "1001-${nextSession++}",
        startTime: Any? = "2026-10-16T17:00:00+01:00",
        cinema: Any? = "delphi LUX",
        formats: Any? = listOf("OmU"),
    ): JsonObject = buildJsonObject {
        put("sys", fields(mapOf("id" to id)))
        put(
            "fields",
            fields(
                mapOf(
                    "startTime" to startTime,
                    "formats" to formats,
                    "cinema" to mapOf("fields" to mapOf("name" to cinema, "accessibility" to "")),
                ),
            ),
        )
    }

    /** Leaves out `null` values; [JsonNull] is written as JSON null. */
    private fun fields(values: Map<String, Any?>): JsonObject =
        JsonObject(values.filterValues { it != null }.mapValues { (_, value) -> toJson(value) })

    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> buildJsonArray { value.forEach { add(toJson(it)) } }
        is Map<*, *> -> JsonObject(value.entries.filter { it.value != null }.associate { it.key as String to toJson(it.value) })
        else -> error("Unsupported $value")
    }
}
