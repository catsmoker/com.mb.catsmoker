package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Client.log decryption against the two references it was ported from —
 * `referance/gamingtools/WuWa-Config-Android-main/config/LogParser.kt` and
 * `referance/gamingtools/Mobile-WuWa-Config-main/misc/Client Log Decryptor/For Developers/wuwa_ld.py`
 * (both read in full before the port): the 3-byte magic, the parity-keyed XOR LUT and its
 * non-self-inverse mirror, the UTF-16 BOM handling, and the "plaintext is plaintext, never
 * reported as decrypted" distinction.
 *
 * The round-trips run through [WuwaLogDecryptor.encryptWuwaLog] — the reverse direction the
 * game itself uses — rather than a fixed byte vector, so a scheme change on Kuro's side
 * breaks the test loudly instead of silently decrypting garbage.
 */
class WuwaLogDecryptorTest {

    private val SAMPLE = "Log file open, [2026.06.08-06.13.33]\n" +
        "[GameThread]LogPakFile: Mounting pak files.\n" +
        "LogRHI: Initializing Vulkan RHI\n"

    @Test
    fun encryptedAndroidLogRoundTripsThroughTheExactScheme() {
        val plainBytes = SAMPLE.toByteArray(Charsets.UTF_16BE)
        val encrypted = WuwaLogDecryptor.encryptWuwaLog(plainBytes, utf16BeBom = true)

        // The encrypted form starts with the magic and shares no bytes with the plaintext.
        assertEquals(0x00, encrypted[0].toInt())
        assertEquals(0x54, encrypted[1].toInt())
        assertEquals(0x50, encrypted[2].toInt())

        val decrypted = WuwaLogDecryptor.decryptWuwaLog(encrypted)
        assertArrayEquals(plainBytes, decrypted)

        val (text, result) = WuwaLogDecryptor.decodeLogBytes(encrypted)
        assertEquals(SAMPLE, text)
        assertEquals(WuwaLogDecryptor.DecodeResult.DECRYPTED, result)
    }

    @Test
    fun encryptedUtf8LogRoundTripsWithoutABom() {
        val plainBytes = SAMPLE.toByteArray(Charsets.UTF_8)
        val encrypted = WuwaLogDecryptor.encryptWuwaLog(plainBytes, utf16BeBom = false)
        val (text, result) = WuwaLogDecryptor.decodeLogBytes(encrypted)
        assertEquals(SAMPLE, text)
        assertEquals(WuwaLogDecryptor.DecodeResult.DECRYPTED, result)
    }

    @Test
    fun wrongMagicIsNotDecryptedAndPassesThroughAsPlaintext() {
        val notALog = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        assertNull(WuwaLogDecryptor.decryptWuwaLog(notALog))
        // A plaintext log is decoded as-is — never claimed as a decryption.
        val (text, result) = WuwaLogDecryptor.decodeLogBytes(notALog)
        assertEquals(WuwaLogDecryptor.DecodeResult.PLAINTEXT, result)
        assertTrue(text.isNotEmpty())
    }

    @Test
    fun theLutIsKeyedOnCipherParityAndIsNotSelfInverse() {
        // LUT[cipher] = cipher xor 0xA5 for odd cipher, xor 0xEF for even — verbatim from
        // both references. 0x01 is odd; 0x02 is even.
        assertEquals(0xA4.toByte(), WuwaLogDecryptor.applyXorLut(byteArrayOf(0x01))[0])
        assertEquals(0xED.toByte(), WuwaLogDecryptor.applyXorLut(byteArrayOf(0x02))[0])

        // LUT(LUT(b)) = b xor 0x4A — the two references' own statement of why the encrypt
        // table is the LUT's mirror (keyed on plaintext parity) and not the LUT itself.
        val bytes = ByteArray(256) { it.toByte() }
        val twice = WuwaLogDecryptor.applyXorLut(WuwaLogDecryptor.applyXorLut(bytes))
        for (i in 0 until 256) {
            assertEquals((i xor 0x4A).toByte(), twice[i])
        }
    }

    @Test
    fun backupMagicHeaderIsHandledToo() {
        val body = "Log file open, [2026.01.01-00.00.00]".toByteArray()
        // Build the backup variant through the same encryptor, swapping the magic for EF BB BF.
        val encrypted = WuwaLogDecryptor.encryptWuwaLog(body, utf16BeBom = false)
        val backup = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            encrypted.copyOfRange(3, encrypted.size)

        assertArrayEquals(body, WuwaLogDecryptor.decryptBackupLog(backup))
        val (text, result) = WuwaLogDecryptor.decodeLogBytes(backup)
        assertEquals("Log file open, [2026.01.01-00.00.00]", text)
        assertEquals(WuwaLogDecryptor.DecodeResult.DECRYPTED, result)
    }

    @Test
    fun utf16BomsAreDetectedAndStrippedOnPlaintext() {
        val text = "Log file open, [2026.06.08-06.13.33]"
        // UTF-16LE with explicit BOM, not encrypted.
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val (leText, leResult) = WuwaLogDecryptor.decodeLogBytes(le)
        assertEquals(text, leText)
        assertEquals(WuwaLogDecryptor.DecodeResult.PLAINTEXT, leResult)

        // UTF-16BE with explicit BOM.
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, WuwaLogDecryptor.decodeLogBytes(be).first)
    }
}
