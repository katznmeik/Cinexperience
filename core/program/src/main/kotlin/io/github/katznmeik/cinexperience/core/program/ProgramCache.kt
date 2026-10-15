package io.github.katznmeik.cinexperience.core.program

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Where the downloaded program lives in app-private storage (spec §11.1). */
object ProgramFiles {
    /** The program's own folder under `filesDir`; nothing else ever writes or sweeps it. */
    const val FOLDER: String = "program"

    /** The cache file inside [FOLDER]. */
    const val CACHE_FILE: String = "program.json"
}

/**
 * The downloaded program as one file, `filesDir/program/program.json`, in the
 * app's own versioned JSON format (not Yorck's page). It is not in the database,
 * not in backups and not in Android Auto Backup (the rules include only the
 * database and shared preferences, spec §8).
 *
 * - [write] is atomic: a temporary file in the same folder, flushed to disk and
 *   then renamed over the old file, so a crash leaves either the old or the new
 *   program, never a mix. The repository calls it only with a program that was
 *   parsed and checked.
 * - [read] never throws: a missing, corrupt, oversized or unknown-version file
 *   counts as no cache. Every value is checked again like data from the page.
 * - [delete] removes the file ("Delete downloaded program").
 *
 * Everything stays inside [folder]. Blocking; call it on an IO dispatcher.
 */
internal class ProgramCache(private val folder: File) {

    private val file: File get() = File(folder, ProgramFiles.CACHE_FILE)

    fun read(): Program? = try {
        val target = file
        if (!target.isFile || target.length() > MAX_FILE_BYTES) {
            null
        } else {
            fromDto(json.decodeFromString(CachedProgram.serializer(), target.readText(Charsets.UTF_8)))
        }
    } catch (e: Exception) {
        null // unreadable or corrupt: treated as no cache
    }

    /** Replaces the cache with [program]; returns false (keeping the old file) if it couldn't be written. */
    fun write(program: Program): Boolean {
        var temp: File? = null
        return try {
            if (!folder.isDirectory && !folder.mkdirs()) return false
            deleteTempFiles()
            val tempFile = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, folder).also { temp = it }
            FileOutputStream(tempFile).use { out ->
                out.write(json.encodeToString(CachedProgram.serializer(), toDto(program)).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            moveReplacing(tempFile, file)
            temp = null
            true
        } catch (e: Exception) {
            false
        } finally {
            temp?.delete()
        }
    }

    /** Deletes the cache (and leftover temporary files); returns true if no cache file remains. */
    fun delete(): Boolean = try {
        deleteTempFiles()
        val target = file
        !target.exists() || target.delete()
    } catch (e: Exception) {
        false
    }

    private fun deleteTempFiles() {
        folder.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(TEMP_PREFIX) && it.name.endsWith(TEMP_SUFFIX) }
            .forEach { it.delete() }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            // Same folder, so this doesn't happen on Android; rename() is atomic there too.
            if (!source.renameTo(target)) throw IOException("Couldn't replace the program cache")
        }
    }

    private fun toDto(program: Program) = CachedProgram(
        formatVersion = FORMAT_VERSION,
        fetchedAt = program.fetchedAt.toString(),
        films = program.films.map { film ->
            CachedFilm(
                slug = film.slug,
                kind = film.kind.name,
                title = film.title,
                tagline = film.tagline,
                genre = film.genre,
                runtimeMinutes = film.runtimeMinutes,
                fsk = film.fsk,
                releaseDate = film.releaseDate?.toString(),
            )
        },
        showings = program.showings.map { showing ->
            CachedShowing(
                id = showing.id,
                filmSlug = showing.filmSlug,
                start = showing.start.toString(),
                cinema = showing.cinema.name,
                versions = showing.versions.map { it.label },
                eventLabels = showing.eventLabels,
            )
        },
    )

    /**
     * Checks the stored values like data from the page (the file could be damaged):
     * an invalid film or showing is skipped, an unknown format version or a file
     * without valid showings counts as no cache. Cinema seed keys are matched
     * again, so a newer table applies to an older cache.
     */
    private fun fromDto(dto: CachedProgram): Program? {
        if (dto.formatVersion != FORMAT_VERSION) return null
        if (dto.films.size > ProgramRules.MAX_FILMS || dto.showings.size > ProgramRules.MAX_SHOWINGS) return null
        val fetchedAt = Instant.parse(dto.fetchedAt)
        val films = LinkedHashMap<String, Film>()
        for (cached in dto.films) {
            val film = orNull {
                Film(
                    slug = cached.slug,
                    title = cached.title,
                    kind = FilmKind.valueOf(cached.kind),
                    tagline = cached.tagline,
                    genre = cached.genre,
                    runtimeMinutes = cached.runtimeMinutes,
                    fsk = cached.fsk,
                    releaseDate = cached.releaseDate?.let(LocalDate::parse),
                )
            } ?: continue
            films.putIfAbsent(film.slug, film)
        }
        val ids = HashSet<String>()
        val showings = dto.showings.mapNotNull { cached ->
            orNull {
                Showing(
                    id = cached.id,
                    filmSlug = cached.filmSlug,
                    start = LocalDateTime.parse(cached.start),
                    cinema = ProgramCinema.fromYorckName(cached.cinema),
                    versions = cached.versions.mapNotNull(ShowingVersion::fromLabel).distinct(),
                    eventLabels = cached.eventLabels.distinct(),
                )
            }?.takeIf { it.filmSlug in films && ids.add(it.id) }
        }.sortedWith(compareBy<Showing>({ it.start }, { it.cinema.name }, { it.id }))
        if (showings.isEmpty()) return null
        val usedSlugs = showings.mapTo(HashSet()) { it.filmSlug }
        return Program(fetchedAt, films.values.filter { it.slug in usedSlugs }, showings)
    }

    private inline fun <T> orNull(build: () -> T): T? = try {
        build()
    } catch (e: RuntimeException) {
        null // IllegalArgumentException from the model checks, DateTimeParseException
    }

    @Serializable
    private data class CachedProgram(
        val formatVersion: Int,
        val fetchedAt: String,
        val films: List<CachedFilm>,
        val showings: List<CachedShowing>,
    )

    @Serializable
    private data class CachedFilm(
        val slug: String,
        val kind: String,
        val title: String,
        val tagline: String?,
        val genre: String?,
        val runtimeMinutes: Int?,
        val fsk: Int?,
        val releaseDate: String?,
    )

    @Serializable
    private data class CachedShowing(
        val id: String,
        val filmSlug: String,
        /** Berlin wall-clock time, ISO local date-time. */
        val start: String,
        /** Yorck's cinema name; the seed key is matched again on reading. */
        val cinema: String,
        val versions: List<String>,
        val eventLabels: List<String>,
    )

    companion object {
        /** Version of the cache file format; a file with another version is ignored (and replaced on the next download). */
        const val FORMAT_VERSION: Int = 1

        /** Today's program is about 0.3 MB in this format. */
        const val MAX_FILE_BYTES: Long = 16L * 1024 * 1024

        private const val TEMP_PREFIX = "program-"
        private const val TEMP_SUFFIX = ".tmp"

        private val json = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
            encodeDefaults = true
        }
    }
}
