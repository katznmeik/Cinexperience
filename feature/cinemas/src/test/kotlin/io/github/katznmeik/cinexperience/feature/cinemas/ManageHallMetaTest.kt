package io.github.katznmeik.cinexperience.feature.cinemas

import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Added by you" in Manage cinemas & halls names only halls the user really created. */
class ManageHallMetaTest {

    private fun summary(hall: Hall) = HallSummary(hall, overallScore = null, visitCount = 2, favouriteSeat = null)

    @Test
    fun aHallTheUserCreatedIsAddedByYou() {
        val own = Hall(id = 1, cinemaId = 1, name = "Saal 9")
        assertEquals("Added by you · Layout not drawn yet · 2 visits", manageHallMeta(summary(own)))
    }

    @Test
    fun aPreloadedHallIsNot() {
        val preloaded = Hall(id = 2, cinemaId = 1, name = "Saal 1", isPreloaded = true, seedKey = "delphi-lux/saal-1")
        assertEquals("Layout not drawn yet · 2 visits", manageHallMeta(summary(preloaded)))
    }

    /** The seeder adds the seed halls to a cinema the user created: not preloaded, but not the user's either. */
    @Test
    fun aSeedHallInTheUsersCinemaIsNot() {
        val seeded = Hall(id = 3, cinemaId = 7, name = "Saal 2", isPreloaded = false, seedKey = "blauer-stern/saal-2")
        assertEquals("Layout not drawn yet · 2 visits", manageHallMeta(summary(seeded)))
    }
}
