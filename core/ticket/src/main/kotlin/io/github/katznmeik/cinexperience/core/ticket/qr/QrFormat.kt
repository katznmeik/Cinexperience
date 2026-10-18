package io.github.katznmeik.cinexperience.core.ticket.qr

/**
 * A QR code's 15-bit format information (ISO/IEC 18004, 7.9): 5 data bits (error
 * correction level and mask pattern) and a BCH(15,5) code, XORed with the fixed mask
 * `101010000010010`. There are only 32 valid words; the reader accepts a grid only if
 * both copies are the same valid word, and then reads its codewords with that word's
 * level and mask ([QrCodewords]).
 */
internal object QrFormat {
    /** The BCH generator polynomial x^10 + x^8 + x^5 + x^4 + x^2 + x + 1. */
    private const val GENERATOR = 0b101_0011_0111
    const val MASK: Int = 0b101_0100_0001_0010

    /** The format word for 5 data bits, as it appears in the symbol. */
    fun encode(data: Int): Int {
        require(data in 0 until 32) { "Format data has 5 bits" }
        var remainder = data shl 10
        for (bit in 14 downTo 10) {
            if (remainder and (1 shl bit) != 0) remainder = remainder xor (GENERATOR shl (bit - 10))
        }
        return ((data shl 10) or remainder) xor MASK
    }

    val VALID_WORDS: Set<Int> = (0 until 32).map(::encode).toSet()

    fun isValid(word: Int): Boolean = word in VALID_WORDS

    /** The 5 data bits (level indicator, then mask pattern) of a valid [word]. */
    fun data(word: Int): Int {
        val data = (word xor MASK) shr 10
        require(encode(data) == word) { "Not a valid format word" }
        return data
    }
}
