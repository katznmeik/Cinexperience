package io.github.katznmeik.cinexperience.core.program

/**
 * Links to yorck.de (spec §11.1, Links). Every link is built here from a fixed
 * pattern and a value that passed the strict ID or slug check; the app never
 * opens an address taken from the page. Links open in the phone's browser,
 * never inside the app. An invalid ID or slug gives `null`, so the UI falls back
 * to the film's page or shows no link.
 */
object ProgramLinks {
    private const val BASE = "https://www.yorck.de/en"

    /** The seat selection of the showing [id] (works without login), `null` for an invalid ID. */
    fun showing(id: String): String? =
        if (ProgramRules.isValidShowingId(id)) "$BASE/checkout/seats?sessionid=$id" else null

    /** The page of the film [slug], `null` for an invalid slug. */
    fun film(slug: String): String? =
        if (ProgramRules.isValidSlug(slug)) "$BASE/films/$slug" else null

    /** The page of the special (film series, festival, event) [slug], `null` for an invalid slug. */
    fun special(slug: String): String? =
        if (ProgramRules.isValidSlug(slug)) "$BASE/specials/$slug" else null

    /** The yorck.de page of [film] ("Open on yorck.de"): [film] or [special] by its kind. */
    fun page(film: Film): String? = when (film.kind) {
        FilmKind.FILM -> film(film.slug)
        FilmKind.SPECIAL -> special(film.slug)
    }

    /**
     * "Book on yorck.de" for [showing]: its seat selection, or the page of its
     * [film] if the ID is unusable, or `null`. Only for upcoming showings (the UI
     * hides past ones).
     */
    fun booking(showing: Showing, film: Film?): String? = booking(showing.id, film)

    /** [booking] for the showing ID [showingId]. */
    fun booking(showingId: String, film: Film?): String? = showing(showingId) ?: film?.let(::page)
}
