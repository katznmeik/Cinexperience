package io.github.katznmeik.cinexperience.core.ticket.testing

import io.github.katznmeik.cinexperience.core.ticket.card.CardImageDecoder
import io.github.katznmeik.cinexperience.core.ticket.card.CardImageDecoding
import io.github.katznmeik.cinexperience.core.ticket.card.MembershipCardImageLimits
import io.github.katznmeik.cinexperience.core.ticket.qr.LuminanceImage
import io.github.katznmeik.cinexperience.core.ticket.qr.luminanceOf
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * INVENTED phone screenshots of a membership card for tests (spec §11.4): never a
 * real card. Like the page the owner screenshots, a dark browser bar and page, a
 * white card with text-like bars, a grey photo placeholder with a white figure and a
 * QR code ([TestQrCodes]: no content), and a dark round help button near the card's
 * corner. Greyscale; [png] and [TestQrCodes.jpeg] encode it.
 */
internal class TestScreenshot(val width: Int = 1027, val height: Int = 2000, background: Int = 80) {
    private val pixels = IntArray(width * height) { background }

    fun rect(left: Int, top: Int, right: Int, bottom: Int, value: Int) {
        for (y in maxOf(0, top) until minOf(height, bottom)) for (x in maxOf(0, left) until minOf(width, right)) pixels[y * width + x] = value
    }

    fun circle(centreX: Int, centreY: Int, radius: Int, value: Int) {
        for (y in maxOf(0, centreY - radius) until minOf(height, centreY + radius)) {
            for (x in maxOf(0, centreX - radius) until minOf(width, centreX + radius)) {
                if (hypot((x - centreX).toDouble(), (y - centreY).toDouble()) <= radius) pixels[y * width + x] = value
            }
        }
    }

    /**
     * [grid] of [size] modules with its top left at ([left], [top]), [modulePixels] px
     * per module (fractions spread the rounding), turned by [degrees] around its centre
     * (0 = upright), drawn black on what is there (the card's white).
     */
    fun code(grid: BooleanArray, size: Int, left: Int, top: Int, modulePixels: Double, degrees: Double = 0.0) {
        val side = size * modulePixels
        val centreX = left + side / 2
        val centreY = top + side / 2
        val radians = Math.toRadians(degrees)
        val reach = (side * 0.75).roundToInt() + 2
        for (y in (centreY - reach).toInt()..(centreY + reach).toInt()) for (x in (centreX - reach).toInt()..(centreX + reach).toInt()) {
            if (x !in 0 until width || y !in 0 until height) continue
            // The pixel's centre turned back into the upright code.
            val dx = x + 0.5 - centreX
            val dy = y + 0.5 - centreY
            val ux = dx * cos(-radians) - dy * sin(-radians) + side / 2
            val uy = dx * sin(-radians) + dy * cos(-radians) + side / 2
            val column = floor(ux / modulePixels).toInt()
            val row = floor(uy / modulePixels).toInt()
            if (row in 0 until size && column in 0 until size && grid[row * size + column]) pixels[y * width + x] = 0
        }
    }

    fun image(): LuminanceImage = LuminanceImage(width, height, ByteArray(pixels.size) { pixels[it].coerceIn(0, 255).toByte() })

    companion object {
        /**
         * The card page with [grid] at about the place and size of the one checked
         * (spec §11.4: 21 modules of about 33 px, quiet zone 2 modules below, the help
         * button just off the code's bottom right corner).
         */
        fun cardPage(grid: BooleanArray, size: Int = 21, modulePixels: Double = 33.0, degrees: Double = 0.0): TestScreenshot =
            TestScreenshot().apply {
                rect(0, 0, width, 170, 35) // browser bar
                rect(40, 60, 640, 120, 60) // address field
                rect(40, 286, 987, 1885, 255) // the card
                rect(80, 320, 680, 385, 30) // the name (a bar like a redacted one)
                rect(79, 428, 514, 981, 190) // photo placeholder
                circle(296, 650, 54, 255)
                rect(188, 735, 405, 813, 255)
                rect(535, 545, 855, 580, 40) // "Mitgliedsnummer"
                rect(535, 625, 905, 675, 30) // the number
                rect(535, 725, 940, 760, 40) // "Art der Mitgliedschaft"
                rect(535, 785, 830, 822, 20) // "Yorck Unlimited"
                val side = size * modulePixels
                code(grid, size, (513 - side / 2).roundToInt(), (1815 - side).roundToInt(), modulePixels, degrees)
                circle(902, 1900, 74, 10) // the help button
                rect(540, 1895, 880, 1985, 40) // a page button below the card
            }

        /** [image] as a greyscale PNG, encoded by the JDK. */
        fun png(image: LuminanceImage): ByteArray {
            val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val v = image.luminance(x, y)
                buffered.setRGB(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
            }
            return ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
        }
    }
}

/**
 * The JDK's image decoder in place of Android's BitmapFactory, for plain JVM tests,
 * with the same limits and power-of-two reduction (every [sample]-th pixel).
 */
internal object ImageIoCardImageDecoder : CardImageDecoder {
    override fun decode(bytes: ByteArray): CardImageDecoding {
        val image = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            null
        } ?: return CardImageDecoding.NotAnImage
        val sample = MembershipCardImageLimits.sampleSize(image.width, image.height) ?: return CardImageDecoding.TooLarge
        val width = (image.width + sample - 1) / sample
        val height = (image.height + sample - 1) / sample
        val pixels = ByteArray(width * height) { luminanceOf(image.getRGB(it % width * sample, it / width * sample)).toByte() }
        return CardImageDecoding.Decoded(LuminanceImage(width, height, pixels))
    }
}
