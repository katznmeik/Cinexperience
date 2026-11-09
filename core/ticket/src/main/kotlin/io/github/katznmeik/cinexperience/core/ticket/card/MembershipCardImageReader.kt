package io.github.katznmeik.cinexperience.core.ticket.card

import io.github.katznmeik.cinexperience.core.ticket.QrModuleGrid
import io.github.katznmeik.cinexperience.core.ticket.qr.LuminanceImage

/** The limits of the membership card's screenshot (spec §11.4 rule 1). */
object MembershipCardImageLimits {
    /** The file as picked. */
    const val MAX_FILE_BYTES: Int = 20 * 1024 * 1024

    /** Pixels the file states (width × height) before anything is decoded. */
    const val MAX_SOURCE_PIXELS: Long = 100_000_000L

    /** The decoded image's longer side: larger images are decoded at a reduced resolution. */
    const val MAX_DECODED_SIDE: Int = 4096

    /** The decoded image's pixels. */
    const val MAX_DECODED_PIXELS: Long = 12_000_000L

    /**
     * The power-of-two reduction that brings a [width] × [height] image within
     * [MAX_DECODED_SIDE] and [MAX_DECODED_PIXELS] (1 = full size); `null` for an image
     * without pixels or with more than [MAX_SOURCE_PIXELS].
     */
    fun sampleSize(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) return null
        var sample = 1
        while (!fitsDecoded(ceilDiv(width, sample), ceilDiv(height, sample))) sample *= 2
        return sample
    }

    /** Whether a decoded image of [width] × [height] is within the limits. */
    fun fitsDecoded(width: Int, height: Int): Boolean =
        width in 1..MAX_DECODED_SIDE && height in 1..MAX_DECODED_SIDE && width.toLong() * height <= MAX_DECODED_PIXELS

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}

/** Why no QR code was taken from the screenshot. */
enum class CardImageError {
    /** Over [MembershipCardImageLimits]. */
    TOO_LARGE,

    /** Not an image Android can decode, or damaged. */
    NOT_AN_IMAGE,

    /** No QR code that reads and checks out (also a slanted or partly hidden one). */
    NO_QR_CODE,

    /** QR codes in more than one place: which one is the card's can't be told. */
    SEVERAL_QR_CODES,
}

/** What [MembershipCardImageReader] made of an image. */
sealed interface CardImageResult {
    /** The card's QR code: its checked module grid (never its content). */
    data class Found(val qrCode: QrModuleGrid) : CardImageResult

    data class Refused(val error: CardImageError) : CardImageResult
}

/** What [CardImageDecoder] made of the bytes. */
internal sealed interface CardImageDecoding {
    data class Decoded(val image: LuminanceImage) : CardImageDecoding

    data object TooLarge : CardImageDecoding

    data object NotAnImage : CardImageDecoding
}

/**
 * Decodes the screenshot within [MembershipCardImageLimits]: [BitmapCardImageDecoder]
 * on Android, the JDK's decoder in plain JVM tests. Never throws.
 */
internal fun interface CardImageDecoder {
    fun decode(bytes: ByteArray): CardImageDecoding
}

/**
 * Reads the QR code of the membership card's screenshot (spec §11.4): the image is
 * untrusted; it is decoded within [MembershipCardImageLimits] and searched by
 * [QrLocator], whose grid is checked like the ticket's. Only the module grid comes
 * back: no content is decoded and no text is read. The bytes are only read, never
 * kept. [read] never throws for any content.
 */
class MembershipCardImageReader internal constructor(private val decoder: CardImageDecoder) {

    constructor() : this(BitmapCardImageDecoder)

    fun read(bytes: ByteArray): CardImageResult {
        if (bytes.size > MembershipCardImageLimits.MAX_FILE_BYTES) return CardImageResult.Refused(CardImageError.TOO_LARGE)
        return try {
            when (val decoded = decoder.decode(bytes)) {
                CardImageDecoding.TooLarge -> CardImageResult.Refused(CardImageError.TOO_LARGE)
                CardImageDecoding.NotAnImage -> CardImageResult.Refused(CardImageError.NOT_AN_IMAGE)
                is CardImageDecoding.Decoded -> when (val located = QrLocator.find(decoded.image)) {
                    is QrLocator.Located.Found -> CardImageResult.Found(located.grid)
                    QrLocator.Located.None -> CardImageResult.Refused(CardImageError.NO_QR_CODE)
                    QrLocator.Located.Several -> CardImageResult.Refused(CardImageError.SEVERAL_QR_CODES)
                }
            }
        } catch (e: OutOfMemoryError) {
            CardImageResult.Refused(CardImageError.TOO_LARGE)
        } catch (e: RuntimeException) {
            CardImageResult.Refused(CardImageError.NOT_AN_IMAGE)
        }
    }
}
