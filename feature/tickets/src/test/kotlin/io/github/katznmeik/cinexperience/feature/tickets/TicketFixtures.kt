package io.github.katznmeik.cinexperience.feature.tickets

import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.qrModulesExpireAt
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The ticket screen's states for the screenshot and accessibility tests (invented data, [SampleTickets]). */
internal object TicketScreenStates {
    private val showing = SampleTickets.visit(SampleTickets.dateAfter(SampleProgram.FETCHED_AT, 2))

    /** [store]'s ticket as the screen shows it at [now]. */
    fun of(store: FakeStore = SampleTickets.store(), now: Instant = SampleProgram.FETCHED_AT): TicketContent {
        val repository = FakeCinexRepository(store)
        val vm = TicketViewModel(SampleTickets.VISIT, repository, repository, repository, Clock.fixed(now, ZoneOffset.UTC), flowOf(Unit))
        return runBlocking { vm.uiState.first { it != TicketContent.Loading } }
    }

    /** Two days before the showing, with the QR code. */
    fun withQrCode(): TicketContent = of()

    /** Ten days after the showing: the QR code was removed. */
    fun qrCodeRemoved(): TicketContent =
        of(SampleTickets.store(tickets = listOf(SampleTickets.ticket(qr = null))), showing.qrModulesExpireAt.plus(Duration.ofDays(3)))

    /** Before the showing, without a QR code (e.g. after a backup restore). */
    fun qrCodeNotStored(): TicketContent = of(SampleTickets.store(tickets = listOf(SampleTickets.ticket(qr = null))))

    /** Before the showing, a ticket whose seats couldn't be read: the visit's own seat shows. */
    fun withoutSeats(): TicketContent = of(SampleTickets.store(tickets = listOf(SampleTickets.ticket().copy(seats = emptyList()))))

    /** The longest booking number the app accepts (16 characters, invented). */
    val LONG_BOOKING_NUMBER = BookingNumber("ABCD1234EFGH5678")

    /** With the QR code and [LONG_BOOKING_NUMBER]. */
    fun withLongBookingNumber(): TicketContent =
        of(SampleTickets.store(tickets = listOf(SampleTickets.ticket().copy(bookingNumber = LONG_BOOKING_NUMBER))))
}
