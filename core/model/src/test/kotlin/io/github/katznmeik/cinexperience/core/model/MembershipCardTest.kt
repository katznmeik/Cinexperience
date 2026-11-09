package io.github.katznmeik.cinexperience.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipCardTest {
    private val qr = QrModules.of(21) { row, column -> (row + column) % 3 == 0 }

    @Test
    fun cleansTypedTexts() {
        val card = MembershipCard.of("  Alex​ Example\n", "\u0007 TEST-0042 ", " Yorck Unlimited ", qr)

        assertEquals("Alex Example", card.name)
        assertEquals("TEST-0042", card.memberNumber)
        assertEquals("Yorck Unlimited", card.membershipType)
    }

    @Test
    fun emptyTextsAreNone() {
        val card = MembershipCard.of("   ", "‎", null, qr)

        assertNull(card.name)
        assertNull(card.memberNumber)
        assertNull(card.membershipType)
    }

    @Test
    fun keepsTextsOfOtherScriptsAndSurrogatePairs() {
        assertEquals("Zoë 🎬 Ünal", MembershipCardText.clean(" Zoë 🎬 Ünal "))
        assertEquals("ab", MembershipCardText.clean("a\uD800b")) // a lone surrogate is dropped
    }

    @Test
    fun refusesTextsOverTheirLimits() {
        MembershipCard("x".repeat(MembershipCard.MAX_NAME_LENGTH), "1".repeat(MembershipCard.MAX_MEMBER_NUMBER_LENGTH), "y".repeat(MembershipCard.MAX_MEMBERSHIP_TYPE_LENGTH), qr)
        // Code points count, not chars: 100 emoji are 200 chars.
        MembershipCard("🎬".repeat(MembershipCard.MAX_NAME_LENGTH), null, null, qr)

        assertThrows(IllegalArgumentException::class.java) { MembershipCard("x".repeat(MembershipCard.MAX_NAME_LENGTH + 1), null, null, qr) }
        assertThrows(IllegalArgumentException::class.java) { MembershipCard(null, "1".repeat(MembershipCard.MAX_MEMBER_NUMBER_LENGTH + 1), null, qr) }
        assertThrows(IllegalArgumentException::class.java) { MembershipCard.of(null, null, "y".repeat(MembershipCard.MAX_MEMBERSHIP_TYPE_LENGTH + 1), qr) }
    }

    @Test
    fun refusesTextsThatArentClean() {
        assertThrows(IllegalArgumentException::class.java) { MembershipCard(" Alex", null, null, qr) }
        assertThrows(IllegalArgumentException::class.java) { MembershipCard(null, "", null, qr) }
        assertThrows(IllegalArgumentException::class.java) { MembershipCard(null, null, "Yorck\nUnlimited", qr) }
        assertTrue(MembershipCardText.isValid(null, 1))
        assertFalse(MembershipCardText.isValid("a‮b", 10))
        assertFalse(MembershipCardText.isValid("abc", 2))
    }

    @Test
    fun toStringShowsNoValue() {
        val text = MembershipCard.of("Alex Example", "TEST-0042", "Yorck Unlimited", qr).toString()

        assertFalse(text.contains("Alex"))
        assertFalse(text.contains("0042"))
        assertFalse(text.contains("Unlimited"))
        assertTrue(text.contains("21 × 21"))
    }
}
