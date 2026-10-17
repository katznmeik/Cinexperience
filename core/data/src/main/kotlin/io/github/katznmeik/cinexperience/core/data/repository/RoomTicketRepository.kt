package io.github.katznmeik.cinexperience.core.data.repository

import androidx.room.withTransaction
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.db.MAX_IDS_PER_QUERY
import io.github.katznmeik.cinexperience.core.data.db.toEntity
import io.github.katznmeik.cinexperience.core.data.db.toModel
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.ShowingTime
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** [TicketRepository] on Room. The fake in core:testing models the same rules (RepositoryContract). */
internal class RoomTicketRepository(
    private val db: CinexDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val awaitReady: AwaitReady = {},
) : TicketRepository {
    private val dao = db.ticketDao()

    override fun observeTicket(visitId: Long): Flow<Ticket?> =
        dao.observe(visitId).map { it?.toModel() }.prepared(awaitReady, dispatcher)

    override suspend fun getTicket(visitId: Long): Ticket? = dao.get(visitId)?.toModel()

    override suspend fun findVisitId(bookingNumber: BookingNumber): Long? = dao.visitIdOf(bookingNumber.value)

    override suspend fun saveVisitWithTicket(visit: Visit, ticket: Ticket): SaveTicketResult {
        require(ticket.visitId == visit.id) { "The ticket belongs to visit ${ticket.visitId}, not ${visit.id}" }
        return db.withTransaction {
            val owner = dao.visitIdOf(ticket.bookingNumber.value)
            if (owner != null && owner != visit.id) return@withTransaction SaveTicketResult.BookingNumberInUse(owner)
            val saved = db.saveVisitInTransaction(visit)
            dao.put(ticket.copy(visitId = saved.visitId).toEntity())
            SaveTicketResult.Saved(saved.visitId, saved.seatCleared)
        }
    }

    override suspend fun removeTicket(visitId: Long): Boolean = dao.delete(visitId) > 0

    override suspend fun storeQrModules(visitId: Long, qrModules: QrModules): Boolean =
        dao.setQrModules(visitId, qrModules.encode()) > 0

    override suspend fun removeExpiredQrModules(now: Instant): Int = db.withTransaction {
        // The expiry comes from the model (Berlin days), not from SQL date math.
        val expired = dao.showingTimesWithQrModules()
            .filter { ShowingTime.qrModulesExpired(it.date, it.startTime, now) }
            .map { it.visitId }
        expired.chunked(MAX_IDS_PER_QUERY).sumOf { dao.clearQrModules(it) }
    }
}
