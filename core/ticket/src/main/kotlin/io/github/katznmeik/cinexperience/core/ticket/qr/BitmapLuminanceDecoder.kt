package io.github.katznmeik.cinexperience.core.ticket.qr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits

/**
 * Decodes a QR code candidate with Android's [BitmapFactory]: the bounds first, and
 * the pixels only if they match the size the PDF states and are within
 * [TicketPdfLimits.MAX_IMAGE_SIDE]. The only Android code of the ticket reader.
 */
internal object BitmapLuminanceDecoder : LuminanceDecoder {
    override fun decode(jpeg: ByteArray, width: Int, height: Int): LuminanceImage? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (!fits(bounds.outWidth, bounds.outHeight, width, height)) {
            null
        } else {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)?.let(::toLuminance)
                ?.takeIf { it.width == width && it.height == height }
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun fits(actualWidth: Int, actualHeight: Int, width: Int, height: Int): Boolean =
        actualWidth == width && actualHeight == height && width in 1..TicketPdfLimits.MAX_IMAGE_SIDE && height in 1..TicketPdfLimits.MAX_IMAGE_SIDE

    private fun toLuminance(bitmap: Bitmap): LuminanceImage = try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        LuminanceImage(bitmap.width, bitmap.height, ByteArray(pixels.size) { luminanceOf(pixels[it]).toByte() })
    } finally {
        bitmap.recycle()
    }
}
