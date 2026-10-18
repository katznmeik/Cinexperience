package io.github.katznmeik.cinexperience.feature.visits

import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * [VisitFixtures] plus two INVENTED upcoming visits, seen from [SampleProgram.FETCHED_AT]
 * (Thursday 15 Oct 2026, 10:00 in Berlin): "The Quiet Projectionist" on Saturday at
 * 20:15 with its ticket ([SampleTickets]) and "Paper Lanterns" tomorrow at 18:00.
 */
internal object UpcomingVisitFixtures {
    const val TOMORROW_VISIT = 7_002L

    val clock: Clock = Clock.fixed(SampleProgram.FETCHED_AT, ZoneOffset.UTC)

    val saturday: Visit = SampleTickets.visit(SampleTickets.dateAfter(SampleProgram.FETCHED_AT, 2))

    val tomorrow: Visit = Visit(
        id = TOMORROW_VISIT,
        hallId = SampleData.BABYLON * 100 + 1,
        date = SampleTickets.dateAfter(SampleProgram.FETCHED_AT, 1),
        startTime = LocalTime.of(18, 0),
        filmTitle = "Paper Lanterns",
        screeningFormat = ScreeningFormat.OV,
    )

    fun store(): FakeStore = VisitFixtures.store().let { s ->
        s.copy(
            halls = s.halls.map { if (it.id == SampleTickets.HALL) it.copy(layout = SampleTickets.saal4Layout) else it },
            visits = s.visits + saturday + tomorrow,
            tickets = listOf(SampleTickets.ticket()),
        )
    }
}
