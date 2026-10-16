package io.github.katznmeik.cinexperience.core.ui.component

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one large-text threshold: layouts stack above a font scale of 1.3. */
class LargeTextTest {

    @Test
    fun stacksAboveOnePointThree() {
        assertFalse(Density(3f, fontScale = 1f).isLargeText)
        assertFalse(Density(3f, fontScale = 1.15f).isLargeText)
        assertFalse(Density(3f, fontScale = 1.3f).isLargeText)
        assertTrue(Density(3f, fontScale = 1.31f).isLargeText)
        assertTrue(Density(3f, fontScale = 1.5f).isLargeText)
        assertTrue(Density(3f, fontScale = 2f).isLargeText)
    }
}
