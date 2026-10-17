package io.github.katznmeik.cinexperience.feature.program

import android.content.Context
import android.content.SharedPreferences
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.ui.text.codePointLength
import io.github.katznmeik.cinexperience.core.ui.text.takeCodePoints
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalTime

/** The current filter and the saved filters of the Program tab. */
data class ProgramFilterSettings(
    val current: ProgramFilter = ProgramFilter(),
    val saved: List<SavedFilter> = emptyList(),
)

/**
 * Keeps the Program tab's filters between visits (spec §6.9). They are app
 * settings (spec §8): covered by Auto Backup, not part of the backup file.
 * Implementations never throw: unreadable settings read as the defaults, and a
 * failed write returns false.
 */
interface ProgramFilterStore {
    suspend fun read(): ProgramFilterSettings

    suspend fun write(settings: ProgramFilterSettings): Boolean
}

/**
 * [ProgramFilterStore] in shared preferences (`program_filters.xml`), as JSON.
 * Reads and writes run on [ioDispatcher].
 */
class SharedPreferencesProgramFilterStore(
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProgramFilterStore {

    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE), ioDispatcher)

    override suspend fun read(): ProgramFilterSettings = withContext(ioDispatcher) {
        try {
            // getString throws if another type is stored under the key: then the defaults.
            prefs.getString(KEY_FILTERS, null)?.let(ProgramFilterCodec::decode) ?: ProgramFilterSettings()
        } catch (e: Exception) {
            ProgramFilterSettings()
        }
    }

    override suspend fun write(settings: ProgramFilterSettings): Boolean = withContext(ioDispatcher) {
        try {
            // commit (on the IO dispatcher): its result tells whether the filters were stored.
            val editor = prefs.edit()
            editor.putString(KEY_FILTERS, ProgramFilterCodec.encode(settings))
            val stored = editor.commit()
            stored
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val PREFERENCES_NAME: String = "program_filters"
        private const val KEY_FILTERS = "filters"
    }
}

/**
 * The stored form of [ProgramFilterSettings] (JSON). Reading is tolerant: every
 * field of a filter and every saved filter is read on its own, so a wrong value
 * (an unknown version, a malformed date, a field of the wrong type, a broken
 * saved entry) only drops that value or entry and never the others.
 */
internal object ProgramFilterCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Upper limits, so a damaged file can't blow up the UI. Lengths in code points. */
    const val MAX_SAVED = 50
    const val MAX_NAME_LENGTH = 40
    const val MAX_SEARCH_LENGTH = 100
    private const val MAX_ITEMS = 100
    private const val MAX_TEXT = 200

    fun encode(settings: ProgramFilterSettings): String = buildJsonObject {
        put("current", settings.current.toJson())
        put(
            "saved",
            buildJsonArray {
                settings.saved.forEach { saved ->
                    add(buildJsonObject {
                        put("name", saved.name)
                        put("filter", saved.filter.toJson())
                    })
                }
            },
        )
    }.toString()

    /** Never throws: unreadable text gives the defaults, unreadable parts are skipped. */
    fun decode(text: String): ProgramFilterSettings {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return ProgramFilterSettings()
        val current = (root["current"] as? JsonObject)?.let(::filterOf) ?: ProgramFilter()
        val saved = (root["saved"] as? JsonArray).orEmpty()
            .mapNotNull { element -> runCatching { savedOf(element) }.getOrNull() }
            .distinctBy { it.name }
            .take(MAX_SAVED)
        return ProgramFilterSettings(current, saved)
    }

    private fun savedOf(element: JsonElement): SavedFilter? {
        val obj = element as? JsonObject ?: return null
        val name = obj.string("name")?.trim()?.takeCodePoints(MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() } ?: return null
        val filter = obj["filter"] as? JsonObject ?: return null
        return SavedFilter(name, filterOf(filter))
    }

    private fun ProgramFilter.toJson(): JsonObject = buildJsonObject {
        put("cinemas", strings(cinemas.sorted()))
        put("day", day.name)
        put("dates", strings(dates.sorted().map { it.toString() }))
        from?.let { put("from", it.toString()) }
        to?.let { put("to", it.toString()) }
        put("versions", strings(versions.sorted().map { it.name }))
        put("events", strings(events.sorted()))
        put("search", search)
    }

    private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    /** Each field on its own: a wrong one keeps its default. */
    private fun filterOf(obj: JsonObject): ProgramFilter {
        val defaults = ProgramFilter()
        fun <T> field(read: () -> T?, default: T): T = runCatching(read).getOrNull() ?: default
        return ProgramFilter(
            cinemas = field({ obj.strings("cinemas").filter { it.codePointLength <= MAX_TEXT }.toSet() }, defaults.cinemas),
            day = field({ obj.string("day")?.let { name -> DayChoice.entries.firstOrNull { it.name == name } } }, defaults.day),
            dates = field({ obj.strings("dates").mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSortedSet() }, defaults.dates),
            from = field({ obj.string("from")?.let(LocalTime::parse) }, null),
            to = field({ obj.string("to")?.let(LocalTime::parse) }, null),
            versions = field({ obj.strings("versions").mapNotNull { name -> ShowingVersion.entries.firstOrNull { it.name == name } }.toSet() }, defaults.versions),
            events = field({ obj.strings("events").filter { it.isNotBlank() && it.codePointLength <= MAX_TEXT }.toSet() }, defaults.events),
            search = field({ obj.string("search")?.takeCodePoints(MAX_SEARCH_LENGTH) }, defaults.search),
        )
    }

    /** The string [key], `null` if it is missing or not a string. */
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The strings of the array [key] (at most [MAX_ITEMS]); other elements are skipped. */
    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty()
        .take(MAX_ITEMS)
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}
