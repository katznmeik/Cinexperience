package io.github.katznmeik.cinexperience.core.data.repository

import androidx.room.withTransaction
import io.github.katznmeik.cinexperience.core.data.db.CinemaEntity
import io.github.katznmeik.cinexperience.core.data.db.CinemaWithHalls
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.db.HallWithCinema
import io.github.katznmeik.cinexperience.core.data.db.maxSeatId
import io.github.katznmeik.cinexperience.core.data.db.seatIdMark
import io.github.katznmeik.cinexperience.core.data.db.toEntity
import io.github.katznmeik.cinexperience.core.data.db.toModel
import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.CinemaSummary
import io.github.katznmeik.cinexperience.core.model.DataLimits
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallDetail
import io.github.katznmeik.cinexperience.core.model.HallSummary
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.NameOrder
import io.github.katznmeik.cinexperience.core.model.ScreenOffsetRules
import io.github.katznmeik.cinexperience.core.model.ScreenSizeRules
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.SeatOverrides
import io.github.katznmeik.cinexperience.core.model.TextLimits
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_LONG_TEXT_LENGTH
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_NAME_LENGTH
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_ROW_LABEL_LENGTH
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.model.cinemaScores
import io.github.katznmeik.cinexperience.core.model.hallScores
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.model.seatStates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Waits until seeding has finished, so the first emission already contains the seed data. */
internal typealias AwaitReady = suspend () -> Unit

/** Newest first: by date, then by insertion order. */
private val NEWEST_FIRST = compareByDescending<Visit> { it.date }.thenByDescending { it.id }

/**
 * Trimmed, non-blank and at most [MAX_NAME_LENGTH] code points. Every text saved
 * here must fit [TextLimits], the backup validator's limits, so whatever is saved
 * can be exported (IllegalArgumentException otherwise; the forms cap their
 * fields, so this is only a defence).
 */
private fun String.requireName(): String = trim().also {
    require(it.isNotEmpty()) { "Name must not be blank" }
    require(TextLimits.fits(it, MAX_NAME_LENGTH)) { "Name is too long (at most $MAX_NAME_LENGTH code points)" }
}

/** Trimmed, blank → `null`; more than [max] code points → IllegalArgumentException naming [what]. */
private fun String?.cleanText(max: Int, what: String): String? = this?.trim()?.takeIf { it.isNotEmpty() }?.also {
    require(TextLimits.fits(it, max)) { "$what is too long (at most $max code points)" }
}

private fun <T> Flow<T>.prepared(awaitReady: AwaitReady, dispatcher: CoroutineDispatcher): Flow<T> {
    val source = this
    return flow {
        awaitReady()
        emitAll(source)
    }.distinctUntilChanged().flowOn(dispatcher)
}

internal class RoomCinemaRepository(
    private val db: CinexDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val awaitReady: AwaitReady = {},
) : CinemaRepository {
    private val dao = db.cinemaDao()

    override fun observeCinemas(): Flow<List<CinemaSummary>> =
        dao.observeAllWithHalls()
            .map { rows -> rows.map { it.toSummary() }.sortedWith(compareBy(NameOrder) { it.cinema.name }) }
            .prepared(awaitReady, dispatcher)

    override fun observeCinemaDetails(): Flow<List<CinemaDetail>> =
        dao.observeAllWithHalls()
            .map { rows -> rows.map { it.toDetail() }.sortedWith(compareBy(NameOrder) { it.cinema.name }) }
            .prepared(awaitReady, dispatcher)

    override fun observeCinemaDetail(cinemaId: Long): Flow<CinemaDetail?> =
        dao.observeWithHalls(cinemaId)
            .map { it?.toDetail() }
            .prepared(awaitReady, dispatcher)

    override suspend fun createCinema(name: String, address: String?, notes: String?): Long =
        dao.insert(
            CinemaEntity(
                name = name.requireName(),
                address = address.cleanText(MAX_NAME_LENGTH, "Address"),
                notes = notes.cleanText(MAX_LONG_TEXT_LENGTH, "Notes"),
                isPreloaded = false,
                seedKey = null,
            ),
        )

    override suspend fun updateCinema(cinemaId: Long, name: String, address: String?, notes: String?): Boolean {
        val cleanName = name.requireName()
        val cleanAddress = address.cleanText(MAX_NAME_LENGTH, "Address")
        val cleanNotes = notes.cleanText(MAX_LONG_TEXT_LENGTH, "Notes")
        return db.withTransaction {
            val existing = dao.get(cinemaId) ?: return@withTransaction false
            dao.update(existing.copy(name = cleanName, address = cleanAddress, notes = cleanNotes)) > 0
        }
    }

    override suspend fun renameCinema(cinemaId: Long, name: String): Boolean {
        val cleanName = name.requireName()
        return db.withTransaction {
            val existing = dao.get(cinemaId) ?: return@withTransaction false
            dao.update(existing.copy(name = cleanName)) > 0
        }
    }

    override suspend fun setFavourite(cinemaId: Long, favourite: Boolean): Boolean = db.withTransaction {
        val existing = dao.get(cinemaId) ?: return@withTransaction false
        existing.isFavourite == favourite || dao.update(existing.copy(isFavourite = favourite)) > 0
    }

    override suspend fun deleteCinema(cinemaId: Long): DeleteResult = db.withTransaction {
        val existing = dao.get(cinemaId)
        when {
            existing == null -> DeleteResult.NOT_FOUND
            existing.isPreloaded -> DeleteResult.PRELOADED
            else -> if (dao.delete(cinemaId) > 0) DeleteResult.DELETED else DeleteResult.NOT_FOUND
        }
    }

    private fun CinemaWithHalls.toSummary(): CinemaSummary {
        val scores = cinemaScores(halls.associate { row -> row.hall.id to row.visits.map { it.toModel() } })
        return CinemaSummary(cinema.toModel(), scores.overall, scores.visitCount, halls.size)
    }

    private fun CinemaWithHalls.toDetail(): CinemaDetail {
        val scores = cinemaScores(halls.associate { row -> row.hall.id to row.visits.map { it.toModel() } })
        val hallSummaries = halls.map { row ->
            val hall = row.hall.toModel(maxSeatId(row.visits, row.overrides))
            val hallScores = scores.halls.getValue(hall.id)
            val favourite = SeatOverrides.favourite(row.overrides.map { it.toModel() })
            HallSummary(
                hall = hall,
                overallScore = hallScores.overall,
                visitCount = hallScores.visitCount,
                favouriteSeat = favourite?.let { hall.layout?.labels()?.labelOf(it) },
            )
        }.sortedWith(compareBy(NameOrder) { it.hall.name })
        return CinemaDetail(cinema.toModel(), scores, hallSummaries)
    }
}

internal class RoomHallRepository(
    private val db: CinexDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val awaitReady: AwaitReady = {},
) : HallRepository {
    private val hallDao = db.hallDao()
    private val overrideDao = db.seatOverrideDao()

    override fun observeHallDetail(hallId: Long): Flow<HallDetail?> =
        hallDao.observeWithCinema(hallId)
            .map { it?.toDetail() }
            .prepared(awaitReady, dispatcher)

    override suspend fun getHall(hallId: Long): Hall? = db.withTransaction {
        hallDao.get(hallId)?.let { it.toModel(db.seatIdMark(it, null) - 1) }
    }

    override suspend fun createHall(cinemaId: Long, name: String): Long {
        val cleanName = name.requireName()
        return db.withTransaction {
            requireNotNull(db.cinemaDao().get(cinemaId)) { "No cinema $cinemaId" }
            // Counted in the transaction, so two concurrent creates can't both take the last place.
            if (hallDao.count() >= DataLimits.MAX_HALLS) throw HallLimitReachedException()
            hallDao.insert(
                HallEntity(
                    cinemaId = cinemaId,
                    name = cleanName,
                    layoutJson = null,
                    screenWidth = null,
                    screenDistance = null,
                    referenceImagePath = null,
                    notes = null,
                    isPreloaded = false,
                    seedKey = null,
                ),
            )
        }
    }

    override suspend fun renameHall(hallId: Long, name: String): Boolean {
        val cleanName = name.requireName()
        return db.withTransaction {
            val existing = hallDao.get(hallId) ?: return@withTransaction false
            hallDao.update(existing.copy(name = cleanName)) > 0
        }
    }

    override suspend fun updateHall(hall: Hall): Boolean {
        val cleanName = hall.name.trim().also { require(it.isNotEmpty()) { "Name must not be blank" } }
        val cleanNotes = hall.notes?.trim()?.takeIf { it.isNotEmpty() }
        val cleanReference = hall.referenceImagePath.cleanText(MAX_NAME_LENGTH, "Reference image path")
        require(ScreenOffsetRules.isValid(hall.screenOffset)) { "Invalid screen offset ${hall.screenOffset}" }
        val layoutJson = hall.layout?.let(LayoutJson::encode)
        require(layoutJson == null || layoutJson.length <= LayoutJson.MAX_JSON_LENGTH) {
            "Layout of hall ${hall.id} is too large (${layoutJson?.length} chars of JSON)"
        }
        return db.withTransaction {
            val existing = hallDao.get(hall.id) ?: return@withTransaction false
            require(existing.cinemaId == hall.cinemaId) { "Moving a hall to another cinema is not supported" }
            val storedLayout = existing.layoutJson?.let(LayoutJson::decodeOrNull)
            // A changed name, notes, row label or screen size must fit the limits. A value
            // the hall already has is kept as it is: an older build may have stored one
            // beyond today's limits, and it must not make every save of the hall fail.
            require(cleanName == existing.name || TextLimits.fits(cleanName, MAX_NAME_LENGTH)) {
                "Name is too long (at most $MAX_NAME_LENGTH code points)"
            }
            require(cleanNotes == existing.notes || TextLimits.fits(cleanNotes, MAX_LONG_TEXT_LENGTH)) {
                "Notes are too long (at most $MAX_LONG_TEXT_LENGTH code points)"
            }
            require(hall.screenWidth == existing.screenWidth || ScreenSizeRules.isValidWidth(hall.screenWidth)) {
                "Invalid screen width ${hall.screenWidth}"
            }
            require(hall.screenDistance == existing.screenDistance || ScreenSizeRules.isValidDistance(hall.screenDistance)) {
                "Invalid screen distance ${hall.screenDistance}"
            }
            hall.layout?.let { layout ->
                // By label, not by row: a stored over-long label may move to another row (see updateHall's KDoc).
                val storedLabels = storedLayout?.rows?.mapNotNullTo(HashSet()) { it.label }.orEmpty()
                require(layout.rows.all { TextLimits.fits(it.label, MAX_ROW_LABEL_LENGTH) || it.label in storedLabels }) {
                    "Row label is too long (at most $MAX_ROW_LABEL_LENGTH code points)"
                }
            }
            // A hall read with an unreadable layout comes back with layout = null; saving
            // its other fields must not wipe the stored layout.
            val keepStoredLayout = hall.layout == null && hall.layoutUnreadable &&
                existing.layoutJson != null && storedLayout == null
            val newLayoutJson = if (keepStoredLayout) existing.layoutJson else layoutJson

            // Seat ids are never reused within a hall: a seat that is not in the stored
            // layout must get an id at or above the hall's mark (see SeatId).
            val mark = db.seatIdMark(existing, storedLayout)
            val newLayout = hall.layout
            if (newLayout != null) {
                // (1) Seats not in the stored layout are new and need ids ≥ the mark.
                val storedIds = storedLayout?.seatIds.orEmpty()
                val reused = newLayout.seatIds.filter { it !in storedIds && it.value < mark }
                // (2) Seats sharing an id with the stored layout must still be in the same
                //     relative place; otherwise this is a new grid that reuses old ids.
                val consistent = storedLayout == null || newLayout.isEditOf(storedLayout)
                require(reused.isEmpty() && consistent) {
                    "Layout of hall ${hall.id} reuses seat ids of other seats (mark $mark). " +
                        "Start new grids at Hall.nextSeatId, e.g. with Hall.startLayoutEdit()."
                }
            }
            val updated = existing.copy(
                name = cleanName,
                layoutJson = newLayoutJson,
                screenWidth = hall.screenWidth,
                screenDistance = hall.screenDistance,
                // + 0f stores -0.0 as 0.0, so an unchanged offset always compares equal.
                screenOffset = hall.screenOffset + 0f,
                referenceImagePath = cleanReference,
                notes = cleanNotes,
                nextSeatId = maxOf(mark, hall.layout?.nextSeatId ?: 0),
            )
            hallDao.update(updated)
            if (existing.layoutJson != newLayoutJson) {
                // Overrides of seats that no longer exist would be invisible but could
                // still hold the hall's only favourite, so they are removed.
                val remaining = hall.layout?.seatIds.orEmpty()
                val overrides = overrideDao.getForHall(hall.id)
                val kept = overrides.filter { SeatId(it.seatId) in remaining }
                if (kept.size != overrides.size) {
                    overrideDao.deleteForHall(hall.id)
                    overrideDao.insertAll(kept)
                }
            }
            true
        }
    }

    override suspend fun deleteHall(hallId: Long): DeleteResult = db.withTransaction {
        val existing = hallDao.get(hallId)
        when {
            existing == null -> DeleteResult.NOT_FOUND
            existing.isPreloaded -> DeleteResult.PRELOADED
            else -> if (hallDao.delete(hallId) > 0) DeleteResult.DELETED else DeleteResult.NOT_FOUND
        }
    }

    private fun HallWithCinema.toDetail(): HallDetail {
        val hallModel = hall.toModel(maxSeatId(visits, overrides))
        val visitModels = visits.map { it.toModel() }.sortedWith(NEWEST_FIRST)
        val overrideModels = overrides.map { it.toModel() }.sortedBy { it.seatId.value }
        val layout = hallModel.layout
        return HallDetail(
            hall = hallModel,
            cinema = cinema.toModel(),
            scores = hallScores(visitModels),
            visits = visitModels,
            labels = layout?.labels(),
            seats = layout?.let { seatStates(it, visitModels, overrideModels) }.orEmpty(),
            overrides = overrideModels,
        )
    }
}

internal class RoomVisitRepository(
    private val db: CinexDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val awaitReady: AwaitReady = {},
    /** Reads a stored layout (`null` = unreadable); replaceable so tests can count the reads. */
    private val decodeLayout: (String) -> SeatLayout? = LayoutJson::decodeOrNull,
) : VisitRepository {
    private val dao = db.visitDao()

    override fun observeAllVisits(): Flow<List<VisitListItem>> =
        dao.observeAllWithContext()
            .map { rows ->
                // Each hall's layout is read once and decoded once, however many visits it has.
                val labelsByHall = HashMap<Long, LayoutLabels?>()
                rows.map { row ->
                    val visit = row.visit.toModel()
                    val labels = if (visit.hallId in labelsByHall) {
                        labelsByHall[visit.hallId]
                    } else {
                        // Unreadable layouts give no labels instead of failing the list.
                        row.hallLayout?.layoutJson?.let(decodeLayout)?.labels().also { labelsByHall[visit.hallId] = it }
                    }
                    VisitListItem(
                        visit = visit,
                        cinemaId = row.cinemaId,
                        cinemaName = row.cinemaName,
                        hallName = row.hallName,
                        seatLabel = visit.seatId?.let { labels?.labelOf(it) },
                    )
                }
            }
            .prepared(awaitReady, dispatcher)

    override fun observeVisit(visitId: Long): Flow<Visit?> =
        dao.observe(visitId).map { it?.toModel() }.prepared(awaitReady, dispatcher)

    override suspend fun getVisit(visitId: Long): Visit? = dao.get(visitId)?.toModel()

    override suspend fun saveVisit(visit: Visit): SaveVisitResult = db.withTransaction {
        val stored = if (visit.id == 0L) null else requireNotNull(dao.get(visit.id)) { "No visit ${visit.id}" }
        val hall = requireNotNull(db.hallDao().get(visit.hallId)) { "No hall ${visit.hallId}" }
        // An unchanged seat in an unchanged hall is kept even if it was since removed
        // from the layout; any other seat must exist in the hall's current layout.
        val unchangedSeat = stored != null && stored.hallId == visit.hallId && stored.seatId == visit.seatId?.value
        val seatValid = visit.seatId == null || unchangedSeat ||
            hall.layoutJson?.let(LayoutJson::decodeOrNull)?.seatIds?.contains(visit.seatId) == true
        val entity = visit.copy(
            filmTitle = visit.filmTitle.cleanText(MAX_LONG_TEXT_LENGTH, "Film title"),
            note = visit.note.cleanText(MAX_LONG_TEXT_LENGTH, "Note"),
            photoPath = visit.photoPath.cleanText(MAX_NAME_LENGTH, "Photo path"),
            seatId = if (seatValid) visit.seatId else null,
        ).toEntity()
        val id = if (stored == null) {
            dao.insert(entity)
        } else {
            dao.update(entity)
            visit.id
        }
        SaveVisitResult(visitId = id, seatCleared = !seatValid)
    }

    override suspend fun deleteVisit(visitId: Long): Boolean = dao.delete(visitId) > 0
}

internal class RoomSeatOverrideRepository(private val db: CinexDatabase) : SeatOverrideRepository {
    private val overrideDao = db.seatOverrideDao()

    override suspend fun setSeatColour(hallId: Long, seatId: SeatId, colour: SeatColour?) =
        modify(hallId, seatId) { SeatOverrides.withColour(it, hallId, seatId, colour) }

    override suspend fun setFavouriteSeat(hallId: Long, seatId: SeatId?) =
        modify(hallId, seatId) { SeatOverrides.withFavourite(it, hallId, seatId) }

    override suspend fun clearSeatOverride(hallId: Long, seatId: SeatId) =
        modify(hallId, seatId) { SeatOverrides.cleared(it, hallId, seatId) }

    private suspend fun modify(hallId: Long, seatId: SeatId?, change: (List<SeatOverride>) -> List<SeatOverride>) {
        db.withTransaction {
            val hall = requireNotNull(db.hallDao().get(hallId)) { "No hall $hallId" }
            if (seatId != null) {
                val layout = hall.layoutJson?.let(LayoutJson::decodeOrNull)
                require(layout != null && seatId in layout.seatIds) { "$seatId is not in the layout of hall $hallId" }
            }
            val current = overrideDao.getForHall(hallId).map { it.toModel() }
            val updated = change(current)
            if (updated.toSet() != current.toSet()) {
                overrideDao.deleteForHall(hallId)
                overrideDao.insertAll(updated.map { it.toEntity() })
            }
        }
    }
}
