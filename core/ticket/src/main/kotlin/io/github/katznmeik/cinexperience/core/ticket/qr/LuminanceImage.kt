package io.github.katznmeik.cinexperience.core.ticket.qr

/** A greyscale image: [width] × [height] luminance values 0 (black) to 255 (white), row by row. */
internal class LuminanceImage(val width: Int, val height: Int, private val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Bad image size" }
    }

    fun luminance(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/**
 * Decodes the JPEG data of a QR code candidate image. On Android this is
 * [BitmapLuminanceDecoder]; tests use the JDK's decoder, so everything after the
 * decoding stays plain Kotlin.
 */
internal fun interface LuminanceDecoder {
    /**
     * The image, or `null` unless [jpeg] decodes to exactly [width] × [height] pixels
     * (the size the PDF states). Never throws.
     */
    fun decode(jpeg: ByteArray, width: Int, height: Int): LuminanceImage?
}

/** Rec. 601 luma of an ARGB colour, 0 to 255. */
internal fun luminanceOf(argb: Int): Int {
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF
    return (red * 299 + green * 587 + blue * 114 + 500) / 1000
}
