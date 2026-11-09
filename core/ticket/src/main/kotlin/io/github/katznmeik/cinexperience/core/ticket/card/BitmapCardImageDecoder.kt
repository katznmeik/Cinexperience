package io.github.katznmeik.cinexperience.core.ticket.card

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.katznmeik.cinexperience.core.ticket.qr.LuminanceImage
import io.github.katznmeik.cinexperience.core.ticket.qr.luminanceOf

/**
 * Decodes the membership card's screenshot with Android's [BitmapFactory] (spec §11.4
 * rule 1): the bounds first; the pixels only within [MembershipCardImageLimits], at
 * the power-of-two reduction [MembershipCardImageLimits.sampleSize] gives. Transparent
 * pixels count as lying on white. The only Android code of the card's reading.
 */
internal object BitmapCardImageDecoder : CardImageDecoder {
    override fun decode(bytes: ByteArray): CardImageDecoding = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        when {
            bounds.outWidth <= 0 || bounds.outHeight <= 0 -> CardImageDecoding.NotAnImage
            else -> when (val sample = MembershipCardImageLimits.sampleSize(bounds.outWidth, bounds.outHeight)) {
                null -> CardImageDecoding.TooLarge
                else -> decodePixels(bytes, sample)
            }
        }
    } catch (e: OutOfMemoryError) {
        CardImageDecoding.TooLarge
    } catch (e: RuntimeException) {
        CardImageDecoding.NotAnImage
    }

    private fun decodePixels(bytes: ByteArray, sample: Int): CardImageDecoding {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return CardImageDecoding.NotAnImage
        try {
            // A decoder that ignored the reduction must not hand over more than the limits allow.
            if (!MembershipCardImageLimits.fitsDecoded(bitmap.width, bitmap.height)) return CardImageDecoding.TooLarge
            val width = bitmap.width
            val row = IntArray(width)
            val pixels = ByteArray(width * bitmap.height)
            for (y in 0 until bitmap.height) {
                // One row at a time: no second copy of the whole image.
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) pixels[y * width + x] = onWhite(row[x]).toByte()
            }
            return CardImageDecoding.Decoded(LuminanceImage(width, bitmap.height, pixels))
        } finally {
            bitmap.recycle()
        }
    }

    /** The luminance of [argb] laid on white. */
    internal fun onWhite(argb: Int): Int {
        val alpha = (argb ushr 24) and 0xFF
        val luminance = luminanceOf(argb)
        return (luminance * alpha + 255 * (255 - alpha) + 127) / 255
    }
}
