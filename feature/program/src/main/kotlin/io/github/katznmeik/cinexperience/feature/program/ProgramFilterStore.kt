package io.github.katznmeik.cinexperience.feature.program

import android.content.Context
import android.content.SharedPreferences
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

/** [ProgramFilterStore] in shared preferences (`program_filters.xml`), as JSON. */
class SharedPreferencesProgramFilterStore(private val prefs: SharedPreferences) : ProgramFilterStore {

    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))

    override suspend fun read(): ProgramFilterSettings = withContext(Dispatchers.IO) {
        try {
            prefs.getString(KEY_FILTERS, null)?.let(ProgramFilterCodec::decode) ?: ProgramFilterSettings()
        } catch (e: Exception) {
            ProgramFilterSettings()
        }
    }

    override suspend fun write(settings: ProgramFilterSettings): Boolean = withContext(Dispatchers.IO) {
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

/** An in-memory [ProgramFilterStore], e.g. for previews and tests. */
class InMemoryProgramFilterStore(var settings: ProgramFilterSettings = ProgramFilterSettings()) : ProgramFilterStore {
    var writes: Int = 0
        private set

    override suspend fun read(): ProgramFilterSettings = settings

    override suspend fun write(settings: ProgramFilterSettings): Boolean {
        writes++
        this.settings = settings
        return true
    }
}

/**
 * The stored form of [ProgramFilterSettings]. Unknown or invalid values (an
 * unknown version, a malformed date) are dropped instead of failing the whole read.
 */
internal object ProgramFilterCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Upper limits, so a damaged file can't blow up the UI. */
    const val MAX_SAVED = 50
    const val MAX_NAME_LENGTH = 40
    private const val MAX_ITEMS = 100
    private const val MAX_TEXT = 200

    @Serializable
    private data class StoredSettings(
        val current: StoredFilter = StoredFilter(),
        val saved: List<StoredSaved> = emptyList(),
    )

    @Serializable
    private data class StoredSaved(val name: String, val filter: StoredFilter)

    @Serializable
    private data class StoredFilter(
        val cinemas: List<String> = emptyList(),
        val day: String = DayChoice.TODAY.name,
        val dates: List<String> = emptyList(),
        val from: String? = null,
        val to: String? = null,
        val versions: List<String> = emptyList(),
        val events: List<String> = emptyList(),
        val search: String = "",
    )

    fun encode(settings: ProgramFilterSettings): String = json.encodeToString(
        StoredSettings.serializer(),
        StoredSettings(
            current = settings.current.toStored(),
            saved = settings.saved.map { StoredSaved(it.name, it.filter.toStored()) },
        ),
    )

    fun decode(text: String): ProgramFilterSettings {
        val stored = json.decodeFromString(StoredSettings.serializer(), text)
        return ProgramFilterSettings(
            current = stored.current.toFilter(),
            saved = stored.saved
                .mapNotNull { saved -> saved.name.trim().take(MAX_NAME_LENGTH).takeIf { it.isNotEmpty() }?.let { SavedFilter(it, saved.filter.toFilter()) } }
                .distinctBy { it.name }
                .take(MAX_SAVED),
        )
    }

    private fun ProgramFilter.toStored() = StoredFilter(
        cinemas = cinemas.sorted(),
        day = day.name,
        dates = dates.sorted().map { it.toString() },
        from = from?.toString(),
        to = to?.toString(),
        versions = versions.sorted().map { it.name },
        events = events.sorted(),
        search = search,
    )

    private fun StoredFilter.toFilter() = ProgramFilter(
        cinemas = cinemas.take(MAX_ITEMS).filter { it.length <= MAX_TEXT }.toSet(),
        day = DayChoice.entries.firstOrNull { it.name == day } ?: DayChoice.TODAY,
        dates = dates.take(MAX_ITEMS).mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSortedSet(),
        from = from?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        to = to?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        versions = versions.mapNotNull { name -> ShowingVersion.entries.firstOrNull { it.name == name } }.toSet(),
        events = events.take(MAX_ITEMS).filter { it.isNotBlank() && it.length <= MAX_TEXT }.toSet(),
        search = search.take(MAX_TEXT),
    )
}
