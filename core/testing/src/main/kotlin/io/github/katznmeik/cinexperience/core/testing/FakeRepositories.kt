package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.DeleteResult
import io.github.katznmeik.cinexperience.core.data.repository.HallLimitReachedException
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.SaveTicketResult
import io.github.katznmeik.cinexperience.core.data.repository.SaveVisitResult
import io.github.katznmeik.cinexperience.core.data.repository.SeatOverrideRepository
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.CinemaSummary
import io.github.katznmeik.cinexperience.core.model.DataLimits
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallDetail
import io.github.katznmeik.cinexperience.core.model.HallSummary
import io.github.katznmeik.cinexperience.core.model.NameOrder
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.ScreenOffsetRules
import io.github.katznmeik.cinexperience.core.model.ScreenSizeRules
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.SeatOverrides
import io.github.katznmeik.cinexperience.core.model.ShowingTime
import io.github.katznmeik.cinexperience.core.model.TextLimits
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.model.cinemaScores
import io.github.katznmeik.cinexperience.core.model.hallScores
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.model.seatStates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant

/** Newest first: by date, then by insertion order (id), like the Room repositories. */
private val NEWEST_FIRST = compareByDescending<Visit> { it.date }.thenByDescending { it.id }

/** The limit of notes and film titles, like in the Room repositories. */
private const val LONG = TextLimits.MAX_LONG_TEXT_LENGTH

/**
 * Everything the fake repositories hold.
 *
 * @property favourites fixture shortcut: favourite seats by label (the first seat
 *   with that label). A favourite in [overrides] takes precedence for its hall.
 * @property overrides manual colours and favourites by seat id, as written by the
 *   [SeatOverrideRepository] methods.
 * @property tickets the visits' tickets, as written by the [TicketRepository]
 *   methods: at most one per visit, deleted with it, booking numbers unique.
 */
data class FakeStore(
    val cinemas: List<Cinema> = emptyList(),
    val halls: List<Hall> = emptyList(),
    val visits: List<Visit> = emptyList(),
    val favourites: Map<Long, SeatLabel> = emptyMap(),
    val overrides: List<SeatOverride> = emptyList(),
    val tickets: List<Ticket> = emptyList(),
) {
    /** Without the visits that aren't in [keep] and their tickets (Room's ON DELETE CASCADE). */
    fun withVisits(keep: (Visit) -> Boolean): FakeStore {
        val kept = visits.filter(keep)
        val ids = kept.mapTo(HashSet()) { it.id }
        return copy(visits = kept, tickets = tickets.filter { it.visitId in ids })
    }

    /** The hall's overrides, with the [favourites] label shortcut resolved to a seat id. */
    fun overridesOf(hallId: Long): List<SeatOverride> {
        val own = overrides.filter { it.hallId == hallId }
        if (own.any { it.isFavourite }) return own
        val labels = halls.firstOrNull { it.id == hallId }?.layout?.labels() ?: return own
        val favourite = favourites[hallId]?.let { label -> labels.seats.values.firstOrNull { it.label == label }?.id }
            ?: return own
        return SeatOverrides.withFavourite(own, hallId, favourite)
    }

    /**
     * [hall] as the Room repository reads it: [Hall.nextSeatId] is the hall's full
     * seat id mark, raised to cover its layout and every seat id that its visits and
     * overrides use (core:data's `seatIdMark`), so a new grid never takes one of them.
     * (A [favourites] shortcut always names a seat of the layout, which is covered.)
     */
    fun withSeatIdMark(hall: Hall): Hall {
        val highestUsed = maxOf(
            visits.filter { it.hallId == hall.id }.maxOfOrNull { it.seatId?.value ?: 0 } ?: 0,
            overrides.filter { it.hallId == hall.id }.maxOfOrNull { it.seatId.value } ?: 0,
        )
        val mark = maxOf(hall.nextSeatId, hall.layout?.nextSeatId ?: SeatLayout.FIRST_SEAT_ID, highestUsed + 1)
        return if (mark == hall.nextSeatId) hall else hall.copy(nextSeatId = mark)
    }
}

/**
 * In-memory implementation of the core:data repositories for ViewModel tests and
 * screenshot tests. Scores come from the core:model functions, like in the real
 * repositories; lists are sorted with the same natural [NameOrder] as Room.
 *
 * Set [failure] to make every write throw it, and [observeFailure] to make every
 * flow fail (to test error handling).
 */
class FakeCinexRepository(initial: FakeStore = FakeStore()) :
    CinemaRepository,
    HallRepository,
    VisitRepository,
    SeatOverrideRepository,
    TicketRepository {

    val store = MutableStateFlow(initial)

    /** When set, every write throws this exception. */
    var failure: Exception? = null

    /** When set, every flow collected afterwards throws this exception. */
    var observeFailure: Exception? = null

    private var nextId = 100_000L

    /** The store as a flow, failing with [observeFailure] if set. */
    private val data: Flow<FakeStore> = flow {
        observeFailure?.let { throw it }
        emitAll(store)
    }

    private fun write() {
        failure?.let { throw it }
    }

    private fun FakeStore.detailOf(cinema: Cinema): CinemaDetail {
        val cinemaHalls = halls.filter { it.cinemaId == cinema.id }
        val visitsByHall = cinemaHalls.associate { hall -> hall.id to visits.filter { it.hallId == hall.id } }
        val scores = cinemaScores(visitsByHall)
        val hallSummaries = cinemaHalls.map { hall ->
            val hallScores = scores.halls.getValue(hall.id)
            val favourite = SeatOverrides.favourite(overridesOf(hall.id))
            HallSummary(
                withSeatIdMark(hall),
                hallScores.overall,
                hallScores.visitCount,
                favourite?.let { hall.layout?.labels()?.labelOf(it) },
            )
        }.sortedWith(compareBy(NameOrder) { it.hall.name })
        return CinemaDetail(cinema, scores, hallSummaries)
    }

    private val FakeStore.sortedCinemas: List<Cinema> get() = cinemas.sortedWith(compareBy(NameOrder) { it.name })

    // CinemaRepository

    override fun observeCinemas(): Flow<List<CinemaSummary>> = data.map { s ->
        s.sortedCinemas.map { cinema ->
            val detail = s.detailOf(cinema)
            CinemaSummary(cinema, detail.scores.overall, detail.scores.visitCount, detail.halls.size)
        }
    }.distinctUntilChanged()

    override fun observeCinemaDetails(): Flow<List<CinemaDetail>> =
        data.map { s -> s.sortedCinemas.map { s.detailOf(it) } }.distinctUntilChanged()

    override fun observeCinemaDetail(cinemaId: Long): Flow<CinemaDetail?> =
        data.map { s -> s.cinemas.firstOrNull { it.id == cinemaId }?.let { s.detailOf(it) } }.distinctUntilChanged()

    override suspend fun createCinema(name: String, address: String?, notes: String?): Long {
        write()
        val id = nextId++
        store.update {
            it.copy(cinemas = it.cinemas + Cinema(id, name.requireName(), address.clean(), notes.clean(LONG)))
        }
        return id
    }

    override suspend fun updateCinema(cinemaId: Long, name: String, address: String?, notes: String?): Boolean {
        write()
        val cleanName = name.requireName()
        if (store.value.cinemas.none { it.id == cinemaId }) return false
        store.update { s ->
            s.copy(
                cinemas = s.cinemas.map {
                    if (it.id == cinemaId) it.copy(name = cleanName, address = address.clean(), notes = notes.clean(LONG)) else it
                },
            )
        }
        return true
    }

    override suspend fun renameCinema(cinemaId: Long, name: String): Boolean {
        val cinema = store.value.cinemas.firstOrNull { it.id == cinemaId } ?: return false
        return updateCinema(cinemaId, name, cinema.address, cinema.notes)
    }

    /** Like Room: false for an unknown cinema; any number of cinemas can be favourites. */
    override suspend fun setFavourite(cinemaId: Long, favourite: Boolean): Boolean {
        write()
        if (store.value.cinemas.none { it.id == cinemaId }) return false
        store.update { s ->
            s.copy(cinemas = s.cinemas.map { if (it.id == cinemaId) it.copy(isFavourite = favourite) else it })
        }
        return true
    }

    override suspend fun deleteCinema(cinemaId: Long): DeleteResult {
        write()
        val cinema = store.value.cinemas.firstOrNull { it.id == cinemaId } ?: return DeleteResult.NOT_FOUND
        if (cinema.isPreloaded) return DeleteResult.PRELOADED
        store.update { s ->
            val hallIds = s.halls.filter { it.cinemaId == cinemaId }.map { it.id }.toSet()
            s.withVisits { it.hallId !in hallIds }.copy(
                cinemas = s.cinemas - cinema,
                halls = s.halls.filter { it.id !in hallIds },
                favourites = s.favourites - hallIds,
                overrides = s.overrides.filter { it.hallId !in hallIds },
            )
        }
        return DeleteResult.DELETED
    }

    // HallRepository

    override fun observeHallDetail(hallId: Long): Flow<HallDetail?> = data.map { s ->
        val hall = s.halls.firstOrNull { it.id == hallId } ?: return@map null
        val cinema = s.cinemas.first { it.id == hall.cinemaId }
        val visits = s.visits.filter { it.hallId == hallId }.sortedWith(NEWEST_FIRST)
        val labels = hall.layout?.labels()
        val overrides = s.overridesOf(hallId).sortedBy { it.seatId.value }
        val seats = hall.layout?.let { seatStates(it, visits, overrides) }.orEmpty()
        HallDetail(s.withSeatIdMark(hall), cinema, hallScores(visits), visits, labels, seats, overrides)
    }.distinctUntilChanged()

    override suspend fun getHall(hallId: Long): Hall? =
        store.value.let { s -> s.halls.firstOrNull { it.id == hallId }?.let(s::withSeatIdMark) }

    override suspend fun createHall(cinemaId: Long, name: String): Long {
        write()
        val cleanName = name.requireName()
        require(store.value.cinemas.any { it.id == cinemaId }) { "No cinema $cinemaId" }
        // Like Room: at most DataLimits.MAX_HALLS halls in all cinemas together.
        if (store.value.halls.size >= DataLimits.MAX_HALLS) throw HallLimitReachedException()
        val id = nextId++
        store.update { it.copy(halls = it.halls + Hall(id = id, cinemaId = cinemaId, name = cleanName)) }
        return id
    }

    override suspend fun renameHall(hallId: Long, name: String): Boolean {
        val hall = getHall(hallId) ?: return false
        return updateHall(hall.copy(name = name))
    }

    /**
     * Like the Room repository: only the editable fields change, seat ids must not
     * be reused (IllegalArgumentException; the mark also covers the seat ids of the
     * hall's visits and overrides, see [FakeStore.withSeatIdMark]) and the hall's id
     * mark is raised.
     */
    override suspend fun updateHall(hall: Hall): Boolean {
        write()
        val cleanName = hall.name.trim().also { require(it.isNotEmpty()) { "Name must not be blank" } }
        val cleanNotes = hall.notes?.trim()?.takeIf { it.isNotEmpty() }
        require(ScreenOffsetRules.isValid(hall.screenOffset)) { "Invalid screen offset ${hall.screenOffset}" }
        val existing = store.value.halls.firstOrNull { it.id == hall.id } ?: return false
        require(existing.cinemaId == hall.cinemaId) { "Moving a hall to another cinema is not supported" }
        // Like Room: changed values must fit the limits, values the hall already has are kept.
        require(cleanName == existing.name || TextLimits.fits(cleanName, TextLimits.MAX_NAME_LENGTH)) { "Name is too long" }
        require(cleanNotes == existing.notes || TextLimits.fits(cleanNotes, LONG)) { "Notes are too long" }
        require(hall.screenWidth == existing.screenWidth || ScreenSizeRules.isValidWidth(hall.screenWidth)) {
            "Invalid screen width ${hall.screenWidth}"
        }
        require(hall.screenDistance == existing.screenDistance || ScreenSizeRules.isValidDistance(hall.screenDistance)) {
            "Invalid screen distance ${hall.screenDistance}"
        }
        hall.layout?.let { layout ->
            // By label, not by row, like Room.
            val storedLabels = existing.layout?.rows?.mapNotNullTo(HashSet()) { it.label }.orEmpty()
            require(layout.rows.all { TextLimits.fits(it.label, TextLimits.MAX_ROW_LABEL_LENGTH) || it.label in storedLabels }) {
                "Row label is too long"
            }
        }
        val stored = existing.layout
        val mark = store.value.withSeatIdMark(existing).nextSeatId
        val newLayout = hall.layout
        if (newLayout != null) {
            val storedIds = stored?.seatIds.orEmpty()
            val reused = newLayout.seatIds.any { it !in storedIds && it.value < mark }
            require(!reused && (stored == null || newLayout.isEditOf(stored))) {
                "Layout of hall ${hall.id} reuses seat ids of other seats (mark $mark)"
            }
        }
        val keepUnreadable = newLayout == null && hall.layoutUnreadable && existing.layoutUnreadable
        val updated = existing.copy(
            name = cleanName,
            layout = newLayout,
            screenWidth = hall.screenWidth,
            screenDistance = hall.screenDistance,
            screenOffset = hall.screenOffset + 0f,
            referenceImagePath = hall.referenceImagePath.clean(),
            notes = cleanNotes,
            nextSeatId = maxOf(mark, newLayout?.nextSeatId ?: 0),
            layoutUnreadable = keepUnreadable,
        )
        // Like Room: only a changed layout touches the overrides. A kept unreadable
        // layout is unchanged; replacing an unreadable one, or clearing a layout, is a change.
        val layoutChanged = !keepUnreadable && (newLayout != stored || existing.layoutUnreadable)
        store.update { s ->
            if (!layoutChanged) {
                s.copy(halls = s.halls.map { if (it.id == hall.id) updated else it })
            } else {
                // Overrides of seats that no longer exist are deleted. The label shortcut
                // is resolved against the stored layout first, so the favourite cannot
                // jump to a new seat that happens to have the same label.
                val remaining = newLayout?.seatIds.orEmpty()
                val kept = s.overridesOf(hall.id).filter { it.seatId in remaining }
                s.copy(
                    halls = s.halls.map { if (it.id == hall.id) updated else it },
                    favourites = s.favourites - hall.id,
                    overrides = s.overrides.filter { it.hallId != hall.id } + kept,
                )
            }
        }
        return true
    }

    override suspend fun deleteHall(hallId: Long): DeleteResult {
        write()
        val hall = getHall(hallId) ?: return DeleteResult.NOT_FOUND
        if (hall.isPreloaded) return DeleteResult.PRELOADED
        store.update { s ->
            s.withVisits { it.hallId != hallId }.copy(
                halls = s.halls.filter { it.id != hallId },
                favourites = s.favourites - hallId,
                overrides = s.overrides.filter { it.hallId != hallId },
            )
        }
        return DeleteResult.DELETED
    }

    // VisitRepository

    override fun observeAllVisits(): Flow<List<VisitListItem>> = data.map { s ->
        val ticketVisits = s.tickets.mapTo(HashSet()) { it.visitId }
        s.visits.sortedWith(NEWEST_FIRST).map { visit ->
            val hall = s.halls.first { it.id == visit.hallId }
            val cinema = s.cinemas.first { it.id == hall.cinemaId }
            VisitListItem(
                visit,
                cinema.id,
                cinema.name,
                hall.name,
                visit.seatId?.let { hall.layout?.labels()?.labelOf(it) },
                hasTicket = visit.id in ticketVisits,
            )
        }
    }.distinctUntilChanged()

    override fun observeVisit(visitId: Long): Flow<Visit?> =
        data.map { s -> s.visits.firstOrNull { it.id == visitId } }.distinctUntilChanged()

    override suspend fun getVisit(visitId: Long): Visit? = store.value.visits.firstOrNull { it.id == visitId }

    /**
     * Like the Room repository: texts are trimmed (blank → null), and a seat that
     * is not in the hall's current layout is cleared unless it is the visit's
     * unchanged seat in its unchanged hall.
     */
    override suspend fun saveVisit(visit: Visit): SaveVisitResult {
        write()
        val (saved, seatCleared) = checkedVisit(visit)
        // The visit's ticket stays, like Room's UPDATE.
        store.update { st -> st.copy(visits = st.visits.filter { it.id != saved.id } + saved) }
        return SaveVisitResult(saved.id, seatCleared)
    }

    /**
     * [visit] as [saveVisit] stores it, with its new id, and whether its seat was
     * cleared. Throws like Room for an unknown visit or hall; nothing is changed.
     */
    private fun checkedVisit(visit: Visit): Pair<Visit, Boolean> {
        val s = store.value
        val stored = if (visit.id == 0L) null else requireNotNull(s.visits.firstOrNull { it.id == visit.id }) { "No visit ${visit.id}" }
        val hall = requireNotNull(s.halls.firstOrNull { it.id == visit.hallId }) { "No hall ${visit.hallId}" }
        val unchangedSeat = stored != null && stored.hallId == visit.hallId && stored.seatId == visit.seatId
        val seatValid = visit.seatId == null || unchangedSeat || hall.layout?.seatIds?.contains(visit.seatId) == true
        val saved = visit.copy(
            id = if (visit.id == 0L) nextId++ else visit.id,
            filmTitle = visit.filmTitle.clean(LONG),
            note = visit.note.clean(LONG),
            photoPath = visit.photoPath.clean(),
            seatId = if (seatValid) visit.seatId else null,
        )
        return saved to !seatValid
    }

    /** Like Room: the visit's ticket goes with it (ON DELETE CASCADE). */
    override suspend fun deleteVisit(visitId: Long): Boolean {
        write()
        val exists = store.value.visits.any { it.id == visitId }
        store.update { s -> s.withVisits { it.id != visitId } }
        return exists
    }

    // TicketRepository: like Room, at most one ticket per visit, booking numbers unique.

    override fun observeTicket(visitId: Long): Flow<Ticket?> =
        data.map { s -> s.tickets.firstOrNull { it.visitId == visitId } }.distinctUntilChanged()

    override suspend fun getTicket(visitId: Long): Ticket? = store.value.tickets.firstOrNull { it.visitId == visitId }

    override suspend fun findVisitId(bookingNumber: BookingNumber): Long? =
        store.value.tickets.firstOrNull { it.bookingNumber == bookingNumber }?.visitId

    override suspend fun saveVisitWithTicket(visit: Visit, ticket: Ticket): SaveTicketResult {
        write()
        require(ticket.visitId == visit.id) { "The ticket belongs to visit ${ticket.visitId}, not ${visit.id}" }
        val owner = findVisitId(ticket.bookingNumber)
        if (owner != null && owner != visit.id) return SaveTicketResult.BookingNumberInUse(owner)
        val (saved, seatCleared) = checkedVisit(visit)
        val savedTicket = ticket.copy(visitId = saved.id)
        // One update, so the visit and its ticket appear together, like Room's transaction.
        store.update { st ->
            st.copy(
                visits = st.visits.filter { it.id != saved.id } + saved,
                tickets = st.tickets.filter { it.visitId != saved.id } + savedTicket,
            )
        }
        return SaveTicketResult.Saved(saved.id, seatCleared)
    }

    override suspend fun removeTicket(visitId: Long): Boolean {
        write()
        val exists = store.value.tickets.any { it.visitId == visitId }
        store.update { s -> s.copy(tickets = s.tickets.filter { it.visitId != visitId }) }
        return exists
    }

    /** Like Room: one guarded write, only for that visit's ticket with [bookingNumber] and no QR modules. */
    override suspend fun restoreQrModules(visitId: Long, bookingNumber: BookingNumber, qrModules: QrModules): Boolean {
        write()
        fun matches(ticket: Ticket) = ticket.visitId == visitId && ticket.bookingNumber == bookingNumber && ticket.qrModules == null
        var stored = false
        store.update { s ->
            stored = s.tickets.any(::matches)
            s.copy(tickets = s.tickets.map { if (matches(it)) it.copy(qrModules = qrModules) else it })
        }
        return stored
    }

    /** Like Room: the expiry comes from [ShowingTime], by the visit's date and start time. */
    override suspend fun removeExpiredQrModules(now: Instant): Int {
        write()
        val visits = store.value.visits.associateBy { it.id }
        val expired = store.value.tickets.filter { ticket ->
            val visit = visits[ticket.visitId]
            ticket.qrModules != null && visit != null && ShowingTime.qrModulesExpired(visit.date, visit.startTime, now)
        }.mapTo(HashSet()) { it.visitId }
        store.update { s -> s.copy(tickets = s.tickets.map { if (it.visitId in expired) it.copy(qrModules = null) else it }) }
        return expired.size
    }

    // SeatOverrideRepository: like Room, the seat must be in the hall's current layout.

    override suspend fun setSeatColour(hallId: Long, seatId: SeatId, colour: SeatColour?) =
        modifyOverrides(hallId, seatId) { SeatOverrides.withColour(it, hallId, seatId, colour) }

    override suspend fun setFavouriteSeat(hallId: Long, seatId: SeatId?) =
        modifyOverrides(hallId, seatId) { SeatOverrides.withFavourite(it, hallId, seatId) }

    override suspend fun clearSeatOverride(hallId: Long, seatId: SeatId) =
        modifyOverrides(hallId, seatId) { SeatOverrides.cleared(it, hallId, seatId) }

    private fun modifyOverrides(hallId: Long, seatId: SeatId?, change: (List<SeatOverride>) -> List<SeatOverride>) {
        write()
        val hall = requireNotNull(store.value.halls.firstOrNull { it.id == hallId }) { "No hall $hallId" }
        if (seatId != null) {
            require(hall.layout?.seatIds?.contains(seatId) == true) { "$seatId is not in the layout of hall $hallId" }
        }
        store.update { s ->
            // The label shortcut is resolved into real overrides on the first write.
            val updated = change(s.overridesOf(hallId))
            s.copy(favourites = s.favourites - hallId, overrides = s.overrides.filter { it.hallId != hallId } + updated)
        }
    }

    // Like Room: trimmed, and within TextLimits (names, addresses and paths; LONG for notes and film titles).
    private fun String.requireName(): String = trim().also {
        require(it.isNotEmpty()) { "Name must not be blank" }
        require(TextLimits.fits(it, TextLimits.MAX_NAME_LENGTH)) { "Name is too long" }
    }

    private fun String?.clean(max: Int = TextLimits.MAX_NAME_LENGTH): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.also { require(TextLimits.fits(it, max)) { "Text is too long" } }
}
