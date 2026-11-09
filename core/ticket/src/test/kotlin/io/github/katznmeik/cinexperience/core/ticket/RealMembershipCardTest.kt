package io.github.katznmeik.cinexperience.core.ticket

import io.github.katznmeik.cinexperience.core.ticket.card.CardImageResult
import io.github.katznmeik.cinexperience.core.ticket.card.MembershipCardImageReader
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Optional local check against a real screenshot of the membership card, which is
 * never committed (spec §11.4 rule 4: it holds a name, a membership number and a QR
 * code; `.gitignore` ignores `cards/` and `*.card.jpg` / `*.card.png`, so keep it
 * there). Skipped unless `CINEX_MEMBERSHIP_CARD_IMAGE` names the file:
 *
 * ```
 * CINEX_MEMBERSHIP_CARD_IMAGE=/path/to/card.jpg ./gradlew :core:ticket:testDebugUnitTest \
 *     --tests '*RealMembershipCardTest*' --rerun
 * ```
 *
 * It reads the file with the app's reader (Android's decoder, on Robolectric) and
 * asserts and prints only the code's size, never its modules.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RealMembershipCardTest {

    @Test
    fun findsTheQrCodeOfARealCard() {
        val path = System.getenv("CINEX_MEMBERSHIP_CARD_IMAGE")
        assumeTrue("CINEX_MEMBERSHIP_CARD_IMAGE is not set", !path.isNullOrBlank())
        val file = File(path!!)
        assumeTrue("The file named by CINEX_MEMBERSHIP_CARD_IMAGE doesn't exist", file.isFile)

        val result = MembershipCardImageReader().read(file.readBytes())

        // Only the kind of result and the code's size, never its modules.
        assertTrue("Refused: ${(result as? CardImageResult.Refused)?.error}", result is CardImageResult.Found)
        println("QR code: ${(result as CardImageResult.Found).qrCode.size} × ${result.qrCode.size} modules")
    }
}
