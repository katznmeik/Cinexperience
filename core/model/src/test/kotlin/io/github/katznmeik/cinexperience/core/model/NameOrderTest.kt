package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NameOrderTest {

    private fun sorted(vararg names: String) = names.sortedWith(NameOrder)

    @Test
    fun numbersCompareByValue() {
        assertEquals(listOf("Saal 1", "Saal 2", "Saal 10"), sorted("Saal 10", "Saal 2", "Saal 1"))
    }

    @Test
    fun caseIsIgnored() {
        assertEquals(
            listOf("Babylon", "Delphi Filmpalast", "delphi LUX", "Kant Kino"),
            sorted("Kant Kino", "Delphi Filmpalast", "delphi LUX", "Babylon"),
        )
    }

    @Test
    fun numbersLongerThanALongStillCompareByValue() {
        assertEquals(
            listOf("Saal 99999999999999999999", "Saal 100000000000000000000"),
            sorted("Saal 100000000000000000000", "Saal 99999999999999999999"),
        )
    }

    @Test
    fun aShorterPrefixComesFirst() {
        assertEquals(listOf("Kino", "Kino 2"), sorted("Kino 2", "Kino"))
    }

    @Test
    fun namesThatCompareEqualStillHaveAStableOrder() {
        assertTrue(NameOrder.compare("Kino", "kino") < 0)
        assertTrue(NameOrder.compare("kino", "Kino") > 0)
        assertEquals(0, NameOrder.compare("Kino", "Kino"))
    }
}
