package io.github.katznmeik.cinexperience.core.ticket.qr

import io.github.katznmeik.cinexperience.core.ticket.testing.ImageIoLuminanceDecoder
import io.github.katznmeik.cinexperience.core.ticket.testing.TestQrCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The Reed–Solomon check of the module grid (ISO/IEC 18004, versions 1 to 4), against
 * codes from an independent encoder: it accepts exactly the encoded grids and refuses
 * any grid with a module changed. It reads no content.
 */
class QrCodewordsTest {

    /** One code for the invented text "TEST123", at [version] and [level], its modules row by row as hex (`1` dark). */
    private class EncodedCode(val version: Int, val level: Char, hex: String) {
        val size = 17 + 4 * version
        val grid = TestQrCodes.fromHex(hex, size)
        val name = "version $version-$level"
    }

    /** Generated once with zxing-cpp outside the app (an analysis tool, not a dependency); each decodes to the text. */
    private val encoded = listOf(
        EncodedCode(1, 'L', "fefbfc15506ea0bb75e5dba0aec16907faafe00f00ce517de0c82a9cfd090d9f76e7807717f8b1f0572bbad3bdd1c0ee8ce905cc5feae38"),
        EncodedCode(1, 'M', "fea3fc14906e86bb75e5dba72ec12d07faafe01b00b71a5845f0ea9c1f08a73cbe9600692ffa64b05037ba4fcdd695aeaad1048b4fee490"),
        EncodedCode(1, 'Q', "fe43fc16506eaabb7435dba0aec12107faafe00a007650306af0c4d21fe9873df79600452ff9e4b05737ba67cdd595aeaad1054b4fe2490"),
        EncodedCode(1, 'H', "fea3fc11906eb6bb7435dbadaec12107faafe01a000612afe3aca9d5458bf65ee53b80511bf9ba705586ba34a5d2202e896704439fe0120"),
        EncodedCode(2, 'L', "feaf3fc13e506eb82bb75d65dba9aaec11ad07faaafe005300f2bd4e8097b2d9ff2ec70c165e6eb441cad6958f56750b65f412ebf90041c5bf91ead04ed11ba26fa5d50196ea882d053dc9fed0848"),
        EncodedCode(2, 'M', "fe44bfc179d06e93abb74de5dbaba2ec10a107faaafe001d00aa5b095e618a01fb163a872eb4ef8ca1c5ee765fee96d29d1793d3fa8079463f992b5046319baa8fadd3e21aea6ba3041e47feb30a8"),
        EncodedCode(2, 'Q', "fed4bfc117906ea32bb751e5dba36aec147107faaafe01e400578976ca0d8a008432ae28679a378ca20aaae6a94fb2809917da19f8807a46bfbe2b5059f1dba0efbdd5621ae9b1eb057ed5fe630a8"),
        EncodedCode(2, 'H', "fee33fc16c106e822bb754d5dbab52ec14d107faaafe00d80012499d96e04a10e872a623c791624ca34b7ae6ea37b2893917f683f8805446bf9bab5045f1dba62fbdd5b21ae991eb04a6d5fe590a8"),
        EncodedCode(3, 'L', "fe2223fc1222106ea444bb749995dba4ccaec11ddd07faaaafe0122200ef888e228eeee794aeee9332ddd1723b333a96d998a18c9117328622ebf6488ca225eee569e6ee86f89dd17af933fa80599463fb912bd0522319bac88fedd3ee21aeaeeba3055de47feb330a8"),
        EncodedCode(3, 'M', "fecf73fc1388906ea110bb740335dba699aec1577507faaaafe0088800a325d92da0444d1d8fbbc6e9077bd572e66f84733209d9644276088841ca35d9f0a9c44ffdc1bbd243b77bdef5e6ff807b3c4bfae46a90458913ba65dfbdd1448b2ead3ef7049f4edfecc65f8"),
        EncodedCode(3, 'Q', "fede8bfc174ad06eb562bb75e7a5dba8742ec1075107faaaafe011d1006b751afaa1e8e545f2f707803d1d70fb014a82bc2ab3ae5e8c2c378e284694a3e842af8a2087e8fcf381e2996132fe80739c6bfb292a5045e11bba881fb5d298e9aeb295c30566257fe269028"),
        EncodedCode(3, 'H', "fe8783fc1405506ead80bb742c45dba6dcaec1412907faaaafe01c6f003abc173d87d96b56d48c8860647ef03087a92ca342513915b4cb2a881086852dd567fc0424f0eb731200811b2f2afd0055344bf907aab04e8b13ba9d9f8dd6b767aebac64d04fc46ffe5cbe10"),
        EncodedCode(4, 'L', "fee85ebfc1706b106ead60cbb75a7ca5dba06b02ec14f94d07faaaaafe0094f900ce17a117b8742f51e0e7ca7ae896b067b5aac1a8352a3583ed88035858e314f9850f77a173fe042f515edfca7a0032b06798bec1a875cd3583e06b83585c0bd4f985fbb7a1fb80442fc57f87ca6a705eb071bbacc1affdd3358faee8835d790514fad5feb7a1438"),
        EncodedCode(4, 'M', "fe4b06bfc138d6106ea2c1abb7582495dba9fca2ec14329d07faaaaafe01f9f200be0a17be2aaaf42884ad94e010a59f2a6e7429f1646449266d4e56b1e56b220d591e7dac72686983453f8beb1e68a560d550aa124e3daa2e509a39c94e1f3abdf2a7834e17fe005af444bf8c94ab1050df31abac29ffa5d41920a2eb76b541041e0f19feb5acd20"),
        EncodedCode(4, 'Q', "fea486bfc11c16106e9d81abb749d495dbab8ca2ec15aa9d07faaaaafe0073f2007f09979882893428b0bd94e00c0a7f2a6d2be1f162e897266c393031e5726b8d59464e8c723e54234508ff1b1e67b2c8d559783c4e3f81ac509a9da7ce1f79d9f2a7b32ab7fe0055b444bfb2c4ab105c4731aba81fffa5d61c20a2ebfe3541057acf19fe25ecd20"),
        EncodedCode(4, 'H', "fe85873fc1395cd06ea669cbb7435d75dbaa4c22ec12803d07faaaaafe01843a000612672aeab1f156da88cb1e0b3b2454a0fcfbf8f9eb2c1019ab45161f63cd22d6ef7aad9664d1076d09a83c450b0592b7d62770665cedfe156ebba3b0812a42b5cac6b9ffff804d8f453f85a8abf050d0f14ba7617ffdd0ca63a2e9e9d1f7044eb719fe4da0da0"),
    )

    /** The level indicator's bits (L 01, M 00, Q 11, H 10). */
    private val levelBits = mapOf('L' to 0b01, 'M' to 0b00, 'Q' to 0b11, 'H' to 0b10)

    private fun formatData(code: EncodedCode): Int {
        val (first, second) = QrGridReader.formatWords(code.grid, code.size)
        assertEquals(code.name, first, second)
        return QrFormat.data(first)
    }

    @Test
    fun acceptsEveryVersionAndLevelOfAnIndependentEncoder() {
        assertEquals(16, encoded.size)
        for (code in encoded) {
            assertEquals(code.name, levelBits.getValue(code.level), formatData(code) shr 3)
            assertTrue(code.name, QrGridReader.isValid(code.grid, code.size))
            val image = TestQrCodes.render(code.grid, code.size, modulePixels = 4.0)
            val jpeg = ImageIoLuminanceDecoder().decode(TestQrCodes.jpeg(image, 0.75f), image.width, image.height)!!
            assertTrue(code.name, QrGridReader.read(jpeg)?.toBooleanArray()?.contentEquals(code.grid) == true)
        }
    }

    @Test
    fun anyOneChangedModuleButARemainderBitIsRefused() {
        for (code in encoded) {
            val remainder = TestQrCodes.remainderModules(code.size)
            for (index in code.grid.indices) {
                if (index in remainder) continue
                val changed = code.grid.copyOf().also { it[index] = !it[index] }
                assertFalse("${code.name}, module ${index / code.size}/${index % code.size}", QrGridReader.isValid(changed, code.size))
            }
        }
    }

    @Test
    fun manyChangedDataModulesAreRefused() {
        val random = Random(5)
        for (code in encoded) {
            val remainder = TestQrCodes.remainderModules(code.size)
            val data = (0 until code.size * code.size).filter { !QrCodewords.isFunction(code.size, it / code.size, it % code.size) && it !in remainder }
            repeat(200) {
                val changed = code.grid.copyOf()
                for (index in data.shuffled(random).take(random.nextInt(2, 17))) changed[index] = !changed[index]
                assertFalse(code.name, QrCodewords.isValid(changed, code.size, formatData(code)))
            }
        }
    }

    @Test
    fun theDataModulesHoldTheCodewordsAndTheRemainderBits() {
        // 26, 44, 70 and 100 codewords; versions 2 to 4 have 7 remainder bits.
        for ((size, codewords, remainder) in listOf(Triple(21, 26, 0), Triple(25, 44, 7), Triple(29, 70, 7), Triple(33, 100, 7))) {
            var modules = 0
            QrCodewords.forEachDataModule(size) { _, _ -> modules++ }
            assertEquals("$size modules", codewords * 8 + remainder, modules)
            for (level in 0 until 4) assertEquals(codewords, QrCodewords.blocks(size, level shl 3).total)
        }
    }

    @Test
    fun theTestEncoderWritesTheIndependentEncodersCodes() {
        // Its data codewords, written again with the test encoder: the same grid, bit for bit.
        for (code in encoded) {
            val format = formatData(code)
            val blocks = QrCodewords.blocks(code.size, format)
            val codewords = QrCodewords.read(code.grid, code.size, format and 0b111, blocks.total)!!
            val data = IntArray(blocks.count * blocks.dataCodewords) { i -> codewords[i % blocks.dataCodewords * blocks.count + i / blocks.dataCodewords] }

            assertTrue(code.name, TestQrCodes.grid(code.size, formatData = format, dataCodewords = data).contentEquals(code.grid))
        }
    }

    @Test
    fun theRemainderBitsDontMatter() {
        // Scanners ignore them; an encoder that doesn't write them as 0 still makes a valid code.
        assertEquals(emptySet<Int>(), TestQrCodes.remainderModules(21))
        for (code in encoded.filter { it.version > 1 }) {
            val remainder = TestQrCodes.remainderModules(code.size)
            assertEquals(code.name, 7, remainder.size)
            val changed = code.grid.copyOf().also { grid -> for (index in remainder) grid[index] = !grid[index] }

            assertTrue(code.name, QrGridReader.isValid(changed, code.size))
            assertTrue(code.name, TestQrCodes.sameCode(changed, code.grid, code.size))
        }
    }
}
