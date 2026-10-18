package io.github.katznmeik.cinexperience.core.ui.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** Codes read letter by letter (the booking number on the ticket screen and in the visit form). */
class SpokenTest {

    @Test
    fun aCodeIsSpelledOut() {
        assertEquals("T E S T 1 2 3", spelledOut("TEST123"))
        assertEquals("A", spelledOut("A"))
        assertEquals("", spelledOut(""))
    }

    @Test
    fun aCharacterOutsideTheBmpStaysWhole() {
        val clapper = String(Character.toChars(0x1F3AC))
        assertEquals("A $clapper B", spelledOut("A${clapper}B"))
    }
}
