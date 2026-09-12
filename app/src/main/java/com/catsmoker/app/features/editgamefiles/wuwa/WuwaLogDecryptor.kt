package com.catsmoker.app.features.editgamefiles.wuwa

import java.nio.charset.Charset

/**
 * Decrypts the game's encrypted `Client.log`.
 *
 * Ported from **two** references that were read in full and agree exactly on the scheme —
 * `referance/gamingtools/WuWa-Config-Android-main/config/LogParser.kt` (the Kotlin side this
 * app's shape follows) and `referance/gamingtools/Mobile-WuWa-Config-main/misc/Client Log
 * Decryptor/For Developers/wuwa_ld.py` (an independent reverse-engineering of the same
 * format, confirmed on v3.4 / 2026 sessions):
 *
 *  - 3-byte header `00 54 50` (NUL + "TP");
 *  - every body byte is XOR-obfuscated by a 256-entry LUT keyed on the *ciphertext* byte's
 *    own parity — odd → `xor 0xA5`, even → `xor 0xEF`. On the encryption side the key was
 *    keyed on the plaintext's parity instead; because both keys are odd they flip the LSB,
 *    so cipher and plain parities are always complementary and the direction is unambiguous
 *    with no key state. (wuwa_ld.py documents this; LogParser phrases it as "the LUT is not
 *    self-inverse: LUT(LUT(b)) = b xor 0x4A" — same fact.)
 *  - the Android game writes UTF-16 with a BOM; the desktop decryptor's samples are UTF-8
 *    with a UTF-8 BOM. [decodeLogBytes] handles both plus a BOM-less heuristic, exactly as
 *    LogParser does, and reports whether decryption actually happened — a plaintext log is
 *    decoded as-is, never reported as decrypted.
 *
 * The scheme is reverse-engineered and Kuro may change it (wuwa_ld.py's README says so
 * explicitly); a wrong magic returns null rather than decrypting garbage.
 */
object WuwaLogDecryptor {

    enum class DecodeResult { DECRYPTED, PLAINTEXT }

    private val HEADER_MAGIC = byteArrayOf(0x00, 0x54, 0x50)
    private val BACKUP_MAGIC = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** LUT[cipher] = cipher xor 0xA5 when cipher is odd, xor 0xEF when even — both references, verbatim. */
    private val XOR_LUT = ByteArray(256) { i -> (if (i % 2 == 1) (i xor 0xA5) else (i xor 0xEF)).toByte() }

    /** The encryption table, keyed on the *plaintext* parity — the LUT's mirror. Tests use it to round-trip. */
    private val ENC_LUT = ByteArray(256) { i -> (if (i % 2 == 1) (i xor 0xEF) else (i xor 0xA5)).toByte() }

    /** The encrypted Android Client.log: magic + LUT'd body, minus a UTF-16BE BOM if present. */
    fun decryptWuwaLog(data: ByteArray): ByteArray? {
        if (data.size < 3) return null
        if (!data.copyOfRange(0, 3).contentEquals(HEADER_MAGIC)) return null
        val body = applyXorLut(data.copyOfRange(3, data.size))
        var bom = 0
        if (body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte()) bom = 2
        return body.copyOfRange(bom, body.size)
    }

    /** The backup-log variant the reference also handles: UTF-8-BOM-looking header + LUT body. */
    fun decryptBackupLog(data: ByteArray): ByteArray? {
        if (data.size < 3) return null
        if (!data.copyOfRange(0, 3).contentEquals(BACKUP_MAGIC)) return null
        return applyXorLut(data.copyOfRange(3, data.size))
    }

    fun applyXorLut(data: ByteArray): ByteArray {
        val result = data.copyOf()
        for (i in result.indices) {
            result[i] = XOR_LUT[result[i].toInt() and 0xFF]
        }
        return result
    }

    /**
     * The inverse used to *produce* an encrypted log from plaintext — the game's own write
     * direction. Not used by the app at runtime; it exists so tests (and anyone inspecting a
     * log) can round-trip through the exact scheme instead of trusting a fixed vector.
     */
    fun encryptWuwaLog(plaintext: ByteArray, utf16BeBom: Boolean): ByteArray {
        val body = if (utf16BeBom) {
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + plaintext
        } else {
            plaintext
        }
        val encrypted = ByteArray(body.size)
        for (i in body.indices) {
            encrypted[i] = ENC_LUT[body[i].toInt() and 0xFF]
        }
        return HEADER_MAGIC + encrypted
    }

    /**
     * Decodes raw log bytes (encrypted or not) to text, with the reference's charset
     * detection: explicit UTF-16 BOMs first, then the zero-byte heuristics, then UTF-8.
     *
     * @return the text (BOM stripped) and whether decryption was applied.
     */
    fun decodeLogBytes(data: ByteArray): Pair<String, DecodeResult> {
        val decrypted = decryptWuwaLog(data)
        val backupDecrypted = if (decrypted == null) decryptBackupLog(data) else null
        val payload = decrypted ?: backupDecrypted ?: data
        val text = when {
            payload.size >= 2 && payload[0] == 0xFE.toByte() && payload[1] == 0xFF.toByte() ->
                payload.copyOfRange(2, payload.size).toString(Charset.forName("UTF-16BE"))
            payload.size >= 2 && payload[0] == 0xFF.toByte() && payload[1] == 0xFE.toByte() ->
                payload.copyOfRange(2, payload.size).toString(Charset.forName("UTF-16LE"))
            looksUtf16Be(payload) -> payload.toString(Charset.forName("UTF-16BE"))
            looksUtf16Le(payload) -> payload.toString(Charset.forName("UTF-16LE"))
            else -> payload.toString(Charsets.UTF_8)
        }
        return text.trimStart(BOM_CHAR) to
            (if (decrypted != null || backupDecrypted != null) DecodeResult.DECRYPTED else DecodeResult.PLAINTEXT)
    }

    /** U+FEFF authored as code, not as an invisible literal in the source. */
    private val BOM_CHAR = 65279.toChar()

    private fun looksUtf16Be(data: ByteArray): Boolean {
        if (data.size < 8) return false
        var zeroes = 0
        val samples = minOf(data.size, 200)
        for (i in 0 until samples step 2) {
            if (data[i] == 0.toByte()) zeroes++
        }
        return zeroes > samples / 5
    }

    private fun looksUtf16Le(data: ByteArray): Boolean {
        if (data.size < 8) return false
        var zeroes = 0
        val samples = minOf(data.size, 200)
        for (i in 1 until samples step 2) {
            if (data[i] == 0.toByte()) zeroes++
        }
        return zeroes > samples / 5
    }
}
