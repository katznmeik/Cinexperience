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

    /**
     * A hall the user created and the seeder later adopted (same name as a seed hall)
     * gets the seed key and loses the label: an accepted trade-off, the subtitle stays neutral.
     */
    @Test
    fun anAdoptedHallHasNoLabel() {
        val adopted = Hall(id = 4, cinemaId = 5, name = "Saal 3", isPreloaded = false, seedKey = "delphi-lux/saal-3")
        assertEquals("Layout not drawn yet · 2 visits", manageHallMeta(summary(adopted)))
    }

    /** Preloaded without a seed key (only from a crafted backup): never "Added by you" next to a disabled Delete. */
    @Test
    fun aPreloadedHallWithoutASeedKeyIsNot() {
        val crafted = Hall(id = 5, cinemaId = 1, name = "Saal 4", isPreloaded = true, seedKey = null)
        assertEquals("Layout not drawn yet · 2 visits", manageHallMeta(summary(crafted)))
    }
}
