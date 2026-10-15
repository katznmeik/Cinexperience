package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.buildProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The pure filter logic of the Program tab (spec §6.9). */
class ProgramFiltersTest {

    private fun d(text: String) = LocalDate.parse(text)
    private fun t(text: String) = LocalTime.parse(text)

    // 2026-10-15 is a Thursday.
    private val thursday = d("2026-10-15")
    private val saturday = d("2026-10-17")
    private val sunday = d("2026-10-18")

    @Test
    fun todayAndTomorrow() {
        assertEquals(setOf(thursday), ProgramFilters.datesOf(DayChoice.TODAY, emptySet(), thursday))
        assertEquals(setOf(d("2026-10-16")), ProgramFilters.datesOf(DayChoice.TOMORROW, emptySet(), thursday))
        // Tomorrow of a Sunday is the next week's Monday.
        assertEquals(setOf(d("2026-10-19")), ProgramFilters.datesOf(DayChoice.TOMORROW, emptySet(), sunday))
    }

    @Test
    fun thisWeekRunsUntilSunday() {
        assertEquals(
            ProgramFilters.datesBetween(thursday, sunday),
            ProgramFilters.datesOf(DayChoice.THIS_WEEK, emptySet(), thursday),
        )
        assertEquals(4, ProgramFilters.datesOf(DayChoice.THIS_WEEK, emptySet(), thursday).size)
        // On a Monday: the whole week.
        assertEquals(7, ProgramFilters.datesOf(DayChoice.THIS_WEEK, emptySet(), d("2026-10-12")).size)
        // On a Sunday: only Sunday, not the next week.
        assertEquals(setOf(sunday), ProgramFilters.datesOf(DayChoice.THIS_WEEK, emptySet(), sunday))
        // Across a month and year end.
        assertEquals(
            setOf(d("2026-12-30"), d("2026-12-31"), d("2027-01-01"), d("2027-01-02"), d("2027-01-03")),
            ProgramFilters.datesOf(DayChoice.THIS_WEEK, emptySet(), d("2026-12-30")),
        )
    }

    @Test
    fun weekendIsTheComingSaturdayAndSunday() {
        assertEquals(setOf(saturday, sunday), ProgramFilters.datesOf(DayChoice.WEEKEND, emptySet(), thursday))
        assertEquals(setOf(saturday, sunday), ProgramFilters.datesOf(DayChoice.WEEKEND, emptySet(), d("2026-10-16")))
        assertEquals(setOf(saturday, sunday), ProgramFilters.datesOf(DayChoice.WEEKEND, emptySet(), saturday))
        // On Sunday only what is left of this weekend.
        assertEquals(setOf(sunday), ProgramFilters.datesOf(DayChoice.WEEKEND, emptySet(), sunday))
        // On Monday the next one.
        assertEquals(setOf(d("2026-10-24"), d("2026-10-25")), ProgramFilters.datesOf(DayChoice.WEEKEND, emptySet(), d("2026-10-19")))
    }

    @Test
    fun chosenDatesDropThePast() {
        val chosen = setOf(d("2026-10-14"), thursday, d("2026-10-20"))
        assertEquals(setOf(thursday, d("2026-10-20")), ProgramFilters.datesOf(DayChoice.DATES, chosen, thursday))
        assertEquals(emptySet<LocalDate>(), ProgramFilters.datesOf(DayChoice.DATES, emptySet(), thursday))

        val filter = ProgramFilter(day = DayChoice.DATES, dates = setOf(d("2026-10-01")))
        assertEquals(ProgramFilter(), filter.withoutPastDates(thursday))
        val kept = ProgramFilter(day = DayChoice.DATES, dates = setOf(d("2026-10-01"), sunday))
        assertEquals(setOf(sunday), kept.withoutPastDates(thursday).dates)
        assertEquals(DayChoice.DATES, kept.withoutPastDates(thursday).day)
    }

    @Test
    fun timeRange() {
        assertTrue(ProgramFilters.inTimeRange(t("03:00"), null, null))
        assertTrue(ProgramFilters.inTimeRange(t("16:00"), t("16:00"), t("17:30")))
        assertTrue(ProgramFilters.inTimeRange(t("17:30"), t("16:00"), t("17:30")))
        assertFalse(ProgramFilters.inTimeRange(t("17:31"), t("16:00"), t("17:30")))
        assertFalse(ProgramFilters.inTimeRange(t("15:59"), t("16:00"), null))
        assertTrue(ProgramFilters.inTimeRange(t("23:59"), t("16:00"), null))
        assertTrue(ProgramFilters.inTimeRange(t("11:00"), null, t("12:00")))
        assertFalse(ProgramFilters.inTimeRange(t("12:01"), null, t("12:00")))
        // Across midnight: 22:00–01:00.
        assertTrue(ProgramFilters.inTimeRange(t("23:15"), t("22:00"), t("01:00")))
        assertTrue(ProgramFilters.inTimeRange(t("00:30"), t("22:00"), t("01:00")))
        assertFalse(ProgramFilters.inTimeRange(t("20:00"), t("22:00"), t("01:00")))
    }

    private val program = SampleProgram.program()

    /** 15 Oct 2026, 10:00 in Berlin. */
    private val now: Instant = SampleProgram.FETCHED_AT

    private fun titles(filter: ProgramFilter, at: Instant = now, today: LocalDate = thursday) =
        ProgramFilters.apply(program, filter, at, today).map { "${it.start.toLocalTime()} ${it.cinema.name}" }

    @Test
    fun todayHidesPastShowings() {
        assertEquals(
            listOf("16:30 delphi LUX", "18:00 Rollberg", "20:45 Kant Kino", "21:30 Babylon Kreuzberg"),
            titles(ProgramFilter()),
        )
        // At 19:00 the earlier showings are gone.
        assertEquals(
            listOf("20:45 Kant Kino", "21:30 Babylon Kreuzberg"),
            titles(ProgramFilter(), at = Instant.parse("2026-10-15T17:00:00Z")),
        )
    }

    @Test
    fun cinemasVersionsEventsTimeAndSearchCombine() {
        assertEquals(listOf("16:30 delphi LUX"), titles(ProgramFilter(cinemas = setOf("delphi-lux"))))
        assertEquals(
            listOf("16:30 delphi LUX", "21:30 Babylon Kreuzberg"),
            titles(ProgramFilter(versions = setOf(ShowingVersion.OMU))),
        )
        // Versions and events add up: OV or Kids.
        assertEquals(
            listOf("11:00 Filmtheater am Friedrichshain", "11:00 Capitol Dahlem", "22:00 Odeon"),
            titles(ProgramFilter(day = DayChoice.DATES, dates = setOf(saturday, sunday, d("2026-10-19")), versions = setOf(ShowingVersion.OV), events = setOf("kids"))),
        )
        assertEquals(listOf("18:00 Rollberg", "20:45 Kant Kino"), titles(ProgramFilter(from = t("17:00"), to = t("21:00"))))
        assertEquals(listOf("18:00 Rollberg", "21:30 Babylon Kreuzberg"), titles(ProgramFilter(search = "  neon ")))
        assertEquals(emptyList<String>(), titles(ProgramFilter(search = "nothing like this")))
    }

    @Test
    fun searchAlsoFindsTheShowingsOfASeries() {
        assertEquals(
            listOf("20:45 Kant Kino", "19:00 Blauer Stern"),
            titles(ProgramFilter(day = DayChoice.THIS_WEEK, search = "autumn")),
        )
    }

    /** Thursday 22:30, Friday 00:30 (after midnight) and Thursday 00:30 (last night). */
    private val lateProgram = buildProgram {
        film("Late Show") {
            showing("2026-10-15T00:30", "Kant Kino") // last night, already started at 10:00
            showing("2026-10-15T22:30", "Kant Kino")
            showing("2026-10-16T00:30", "Kant Kino") // tonight, after midnight
            showing("2026-10-16T22:30", "Kant Kino")
            showing("2026-10-17T00:30", "Kant Kino") // tomorrow night, after midnight
        }
    }

    private fun late(filter: ProgramFilter, at: Instant = now) =
        ProgramFilters.apply(lateProgram, filter, at, thursday).map { it.start.toString() }

    @Test
    fun aRangeAcrossMidnightKeepsTheNightTogether() {
        val todayLate = ProgramFilter(from = t("22:00"), to = t("01:00"))
        assertEquals(listOf("2026-10-15T22:30", "2026-10-16T00:30"), late(todayLate))
        val tomorrowLate = todayLate.copy(day = DayChoice.TOMORROW)
        assertEquals(listOf("2026-10-16T22:30", "2026-10-17T00:30"), late(tomorrowLate))
        // Last night's 00:30 belongs to Wednesday, not to Today (even before it started).
        assertEquals(
            listOf("2026-10-15T22:30", "2026-10-16T00:30"),
            late(todayLate, at = Instant.parse("2026-10-14T20:00:00Z")),
        )
        // Without a range across midnight, a showing counts for its own date.
        assertEquals(listOf("2026-10-15T22:30"), late(ProgramFilter()))
        assertEquals(thursday, ProgramFilters.filterDate(java.time.LocalDateTime.parse("2026-10-16T00:30"), t("22:00"), t("01:00")))
        assertEquals(d("2026-10-16"), ProgramFilters.filterDate(java.time.LocalDateTime.parse("2026-10-16T00:30"), t("00:00"), t("01:00")))
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        val program = buildProgram {
            film("Amélie à Paris") { showing("2026-10-15T20:00", "Kant Kino") }
        }
        val filter = ProgramFilter(search = "AMELIE A")
        assertEquals(1, ProgramFilters.apply(program, filter, now, thursday).size)
    }

    @Test
    fun eventLabelsOfUpcomingShowings() {
        assertEquals(listOf("Kids", "Premiere", "Sneak"), ProgramFilters.eventLabels(program, now))
        assertEquals(emptyList<String>(), ProgramFilters.eventLabels(null, now))
    }

    @Test
    fun filtersSurviveTheStore() {
        val settings = ProgramFilterSettings(
            current = ProgramFilter(
                cinemas = setOf("delphi-lux", "kant-kino"),
                day = DayChoice.DATES,
                dates = setOf(thursday, sunday),
                from = t("16:00"),
                to = t("17:30"),
                versions = setOf(ShowingVersion.OMU, ShowingVersion.OV),
                events = setOf("Sneak"),
                search = "neon",
            ),
            saved = listOf(SavedFilter("After work", ProgramFilter(from = t("16:00"))), SavedFilter("Weekend", ProgramFilter(day = DayChoice.WEEKEND))),
        )
        assertEquals(settings, ProgramFilterCodec.decode(ProgramFilterCodec.encode(settings)))
    }

    @Test
    fun wrongTypesDropOnlyTheirOwnValue() {
        val text = """{"current":{"day":7,"cinemas":"delphi-lux","dates":[1,"2026-10-17",null],"from":true,"to":"23:00",
            |"versions":{"x":1},"events":["Sneak",3],"search":["x"]},
            |"saved":[{"name":"Good","filter":{"from":"18:00"}},"broken",{"name":5,"filter":{}},{"name":"No filter"},
            |{"name":"Also good","filter":{"day":"WEEKEND","versions":"OV"}}]}""".trimMargin()
        val settings = ProgramFilterCodec.decode(text)
        with(settings.current) {
            assertEquals(DayChoice.TODAY, day)
            assertEquals(emptySet<String>(), cinemas)
            assertEquals(setOf(saturday), dates)
            assertEquals(null, from)
            assertEquals(t("23:00"), to)
            assertEquals(emptySet<ShowingVersion>(), versions)
            assertEquals(setOf("Sneak"), events)
            assertEquals("", search)
        }
        assertEquals(listOf("Good", "Also good"), settings.saved.map { it.name })
        assertEquals(t("18:00"), settings.saved[0].filter.from)
        assertEquals(DayChoice.WEEKEND, settings.saved[1].filter.day)
    }

    @Test
    fun unreadableTextGivesTheDefaults() {
        assertEquals(ProgramFilterSettings(), ProgramFilterCodec.decode("not json"))
        assertEquals(ProgramFilterSettings(), ProgramFilterCodec.decode("[1,2]"))
        assertEquals(ProgramFilterSettings(), ProgramFilterCodec.decode("{\"current\":\"x\",\"saved\":{}}"))
    }

    @Test
    fun storedTextsAreCutByCodePoints() {
        val emoji = "\uD83C\uDFAC"
        val text = """{"current":{"search":"${emoji.repeat(101)}"},"saved":[{"name":"${emoji.repeat(41)}","filter":{}}]}"""
        val settings = ProgramFilterCodec.decode(text)
        assertEquals(emoji.repeat(100), settings.current.search)
        assertEquals(emoji.repeat(40), settings.saved.single().name)
    }

    @Test
    fun damagedStoredValuesAreDropped() {
        val text = """{"current":{"day":"YESTERDAY","dates":["2026-13-45","2026-10-17"],"from":"25:00","versions":["OMU","XX"],"extra":1},
            |"saved":[{"name":"  ","filter":{}},{"name":"Late","filter":{"from":"22:00"}}]}""".trimMargin()
        val settings = ProgramFilterCodec.decode(text)
        assertEquals(DayChoice.TODAY, settings.current.day)
        assertEquals(setOf(saturday), settings.current.dates)
        assertEquals(null, settings.current.from)
        assertEquals(setOf(ShowingVersion.OMU), settings.current.versions)
        assertEquals(listOf("Late"), settings.saved.map { it.name })
    }
}
