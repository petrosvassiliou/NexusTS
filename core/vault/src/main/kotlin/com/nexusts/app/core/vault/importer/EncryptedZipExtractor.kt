package com.nexusts.app.core.vault.importer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Inspects and extracts password-protected ZIP archives used by Royal TS Lockdown / Complete File Encryption
 * (`.rtsz` archives encrypted with WinZip AES-128/192/256 `0x9901` or Traditional PKWARE ZipCrypto).
 */
internal object EncryptedZipExtractor {

    private const val LOC_SIG = 0x04034B50
    private const val CEN_SIG = 0x02014B50
    private const val END_SIG = 0x06054B50
    private const val WINZIP_AES_EXTRA_ID = 0x9901

    data class CentralEntryMeta(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Int,
        val extra: ByteArray
    )

    fun isEncryptedZip(zipBytes: ByteArray): Boolean {
        if (zipBytes.size < 30) return false
        val buf = ByteBuffer.wrap(zipBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != LOC_SIG) return false
        val gpFlags = buf.getShort(6).toInt() and 0xFFFF
        val method = buf.getShort(8).toInt() and 0xFFFF
        if ((gpFlags and 0x0001) != 0 || method == 99) return true

        for (entry in parseCentralDirectory(zipBytes)) {
            if ((entry.flags and 0x0001) != 0 || entry.method == 99) return true
        }
        return false
    }

    fun extractDecryptedXmlEntry(zipBytes: ByteArray, password: CharArray): ByteArray {
        val entries = parseCentralDirectory(zipBytes)
        if (entries.isEmpty()) {
            throw IllegalArgumentException("Invalid or truncated encrypted Royal TS .rtsz ZIP archive.")
        }

        val candidates = entries.filterNot { it.name.endsWith("/") }.sortedBy { entry ->
            when {
                entry.name.endsWith(".rtsx", ignoreCase = true) -> 0
                entry.name.endsWith(".xml", ignoreCase = true) -> 1
                else -> 2
            }
        }
        val target = candidates.firstOrNull()
            ?: throw IllegalArgumentException("Encrypted .rtsz archive contains no document entries.")

        val buf = ByteBuffer.wrap(zipBytes).order(ByteOrder.LITTLE_ENDIAN)
        val locOff = target.localHeaderOffset
        if (locOff < 0 || locOff + 30 > zipBytes.size || buf.getInt(locOff) != LOC_SIG) {
            throw IllegalArgumentException("Corrupted local header in encrypted .rtsz archive.")
        }
        val nameLen = buf.getShort(locOff + 26).toInt() and 0xFFFF
        val extraLen = buf.getShort(locOff + 28).toInt() and 0xFFFF
        val dataStart = locOff + 30 + nameLen + extraLen
        val compSize = target.compressedSize.toInt()
        if (compSize <= 0 || dataStart + compSize > zipBytes.size) {
            throw IllegalArgumentException("Encrypted .rtsz entry bounds exceed archive size.")
        }

        val locExtra = zipBytes.copyOfRange(locOff + 30 + nameLen, dataStart)
        val combinedExtra = if (locExtra.isNotEmpty()) locExtra else target.extra

        return if (target.method == 99 || hasWinZipAesExtra(combinedExtra)) {
            decryptWinZipAesEntry(
                encryptedSlice = zipBytes.copyOfRange(dataStart, dataStart + compSize),
                extra = combinedExtra,
                uncompressedSize = target.uncompressedSize.toInt(),
                password = password
            )
        } else {
            decryptZipCryptoEntry(
                encryptedSlice = zipBytes.copyOfRange(dataStart, dataStart + compSize),
                method = target.method,
                flags = target.flags,
                crc32 = target.crc32,
                locModTime = buf.getShort(locOff + 10).toInt() and 0xFFFF,
                uncompressedSize = target.uncompressedSize.toInt(),
                password = password
            )
        }
    }

    private fun hasWinZipAesExtra(extra: ByteArray): Boolean = parseWinZipAesExtra(extra) != null

    private data class WinZipAesSpec(
        val vendorVersion: Int,
        val strengthMode: Int,
        val actualCompressionMethod: Int
    )

    private fun parseWinZipAesExtra(extra: ByteArray): WinZipAesSpec? {
        val buf = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 4 <= extra.size) {
            val headerId = buf.getShort(pos).toInt() and 0xFFFF
            val dataSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            if (pos + 4 + dataSize > extra.size) break
            if (headerId == WINZIP_AES_EXTRA_ID && dataSize >= 7) {
                val version = buf.getShort(pos + 4).toInt() and 0xFFFF
                val strength = extra[pos + 8].toInt() and 0xFF
                val actualMethod = buf.getShort(pos + 9).toInt() and 0xFFFF
                return WinZipAesSpec(version, strength, actualMethod)
            }
            pos += 4 + dataSize
        }
        return null
    }

    private fun decryptWinZipAesEntry(
        encryptedSlice: ByteArray,
        extra: ByteArray,
        uncompressedSize: Int,
        password: CharArray
    ): ByteArray {
        val spec = parseWinZipAesExtra(extra) ?: WinZipAesSpec(2, 3, 8)
        val (saltLen, keyLenBytes) = when (spec.strengthMode) {
            1 -> 8 to 16
            2 -> 12 to 24
            else -> 16 to 32
        }
        val overhead = saltLen + 2 + 10
        if (encryptedSlice.size < overhead) {
            throw IllegalArgumentException("Truncated WinZip AES payload in Royal TS .rtsz archive.")
        }

        val salt = encryptedSlice.copyOfRange(0, saltLen)
        val passVerifierExpected = encryptedSlice.copyOfRange(saltLen, saltLen + 2)
        val ciphertextLen = encryptedSlice.size - overhead
        val ciphertext = encryptedSlice.copyOfRange(saltLen + 2, saltLen + 2 + ciphertextLen)
        val authCodeExpected = encryptedSlice.copyOfRange(encryptedSlice.size - 10, encryptedSlice.size)

        val derivedBits = (keyLenBytes * 2 + 2) * 8
        val kdf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val derived = kdf.generateSecret(PBEKeySpec(password, salt, 1000, derivedBits)).encoded

        val aesKeyBytes = derived.copyOfRange(0, keyLenBytes)
        val hmacKeyBytes = derived.copyOfRange(keyLenBytes, keyLenBytes * 2)
        val passVerifierActual = derived.copyOfRange(keyLenBytes * 2, keyLenBytes * 2 + 2)

        if (!passVerifierActual.contentEquals(passVerifierExpected)) {
            throw IllegalArgumentException("Incorrect password for encrypted Royal TS (.rtsz) archive.")
        }

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(hmacKeyBytes, "HmacSHA1"))
        val fullHmac = mac.doFinal(ciphertext)
        val truncatedHmac = fullHmac.copyOfRange(0, 10)
        if (!truncatedHmac.contentEquals(authCodeExpected)) {
            throw IllegalArgumentException("Incorrect password or corrupted HMAC in encrypted Royal TS (.rtsz) archive.")
        }

        val decryptedCompressed = aesCtrLittleEndianCrypt(aesKeyBytes, ciphertext)
        return decompressEntryPayload(decryptedCompressed, spec.actualCompressionMethod, uncompressedSize)
    }

    internal fun aesCtrLittleEndianCrypt(aesKeyBytes: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"))
        val output = ByteArray(input.size)
        val counterBlock = ByteArray(16)
        var counter = 1L
        var offset = 0
        while (offset < input.size) {
            var c = counter
            for (b in 0 until 8) {
                counterBlock[b] = (c and 0xFFL).toByte()
                c = c ushr 8
            }
            val keystream = cipher.doFinal(counterBlock)
            val chunk = minOf(16, input.size - offset)
            for (i in 0 until chunk) {
                output[offset + i] = (input[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += chunk
            counter++
        }
        return output
    }

    private fun decryptZipCryptoEntry(
        encryptedSlice: ByteArray,
        method: Int,
        flags: Int,
        crc32: Long,
        locModTime: Int,
        uncompressedSize: Int,
        password: CharArray
    ): ByteArray {
        if (encryptedSlice.size < 12) {
            throw IllegalArgumentException("Truncated ZipCrypto header in Royal TS .rtsz archive.")
        }
        val keys = intArrayOf(0x12345678, 0x23456789, 0x34567890)
        fun crc32Byte(crc: Int, b: Int): Int {
            var c = crc xor (b and 0xFF)
            repeat(8) {
                c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else (c ushr 1)
            }
            return c
        }
        fun updateKeys(byteVal: Int) {
            keys[0] = crc32Byte(keys[0], byteVal)
            keys[1] = (keys[1] + (keys[0] and 0xFF)) * 134775813 + 1
            keys[2] = crc32Byte(keys[2], keys[1] ushr 24)
        }
        fun decryptByte(): Int {
            val temp = (keys[2] or 2) and 0xFFFF
            return ((temp * (temp xor 1)) ushr 8) and 0xFF
        }

        for (ch in password) {
            updateKeys(ch.code and 0xFF)
        }

        val header = ByteArray(12)
        for (i in 0 until 12) {
            val plain = (encryptedSlice[i].toInt() and 0xFF) xor decryptByte()
            updateKeys(plain)
            header[i] = plain.toByte()
        }

        val expectedCheck = if ((flags and 0x0008) != 0) {
            (locModTime ushr 8) and 0xFF
        } else {
            ((crc32 ushr 24) and 0xFFL).toInt()
        }
        if ((header[11].toInt() and 0xFF) != expectedCheck) {
            throw IllegalArgumentException("Incorrect password for ZipCrypto-protected Royal TS (.rtsz) archive.")
        }

        val body = ByteArray(encryptedSlice.size - 12)
        for (i in body.indices) {
            val plain = (encryptedSlice[12 + i].toInt() and 0xFF) xor decryptByte()
            updateKeys(plain)
            body[i] = plain.toByte()
        }

        val decompressed = decompressEntryPayload(body, method, uncompressedSize)
        if (crc32 != 0L) {
            val verifier = CRC32().apply { update(decompressed) }
            if (verifier.value != (crc32 and 0xFFFFFFFFL)) {
                throw IllegalArgumentException("Incorrect password or CRC mismatch in Royal TS (.rtsz) archive.")
            }
        }
        return decompressed
    }

    private fun decompressEntryPayload(compressed: ByteArray, method: Int, sizeHint: Int): ByteArray {
        return when (method) {
            0 -> compressed
            8 -> {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(compressed)
                    val out = ByteArrayOutputStream(sizeHint.coerceIn(1024, 16 * 1024 * 1024))
                    val buf = ByteArray(8192)
                    while (!inflater.finished()) {
                        val count = inflater.inflate(buf)
                        if (count > 0) {
                            out.write(buf, 0, count)
                        } else if (inflater.needsInput() || inflater.needsDictionary()) {
                            break
                        }
                    }
                    out.toByteArray()
                } finally {
                    inflater.end()
                }
            }
            else -> throw IllegalArgumentException("Unsupported ZIP compression method ($method) in .rtsz archive.")
        }
    }

    private fun parseCentralDirectory(zipBytes: ByteArray): List<CentralEntryMeta> {
        val buf = ByteBuffer.wrap(zipBytes).order(ByteOrder.LITTLE_ENDIAN)
        val minSearch = maxOf(0, zipBytes.size - 65557)
        var eocdOffset = -1
        for (pos in zipBytes.size - 22 downTo minSearch) {
            if (buf.getInt(pos) == END_SIG) {
                eocdOffset = pos
                break
            }
        }
        if (eocdOffset < 0) return emptyList()

        val totalEntries = buf.getShort(eocdOffset + 10).toInt() and 0xFFFF
        var cdPos = buf.getInt(eocdOffset + 16)
        val result = ArrayList<CentralEntryMeta>(totalEntries)

        for (i in 0 until totalEntries) {
            if (cdPos < 0 || cdPos + 46 > zipBytes.size || buf.getInt(cdPos) != CEN_SIG) break
            val flags = buf.getShort(cdPos + 8).toInt() and 0xFFFF
            val method = buf.getShort(cdPos + 10).toInt() and 0xFFFF
            val crc32 = buf.getInt(cdPos + 16).toLong() and 0xFFFFFFFFL
            val compSize = buf.getInt(cdPos + 20).toLong() and 0xFFFFFFFFL
            val uncompSize = buf.getInt(cdPos + 24).toLong() and 0xFFFFFFFFL
            val nameLen = buf.getShort(cdPos + 28).toInt() and 0xFFFF
            val extraLen = buf.getShort(cdPos + 30).toInt() and 0xFFFF
            val commentLen = buf.getShort(cdPos + 32).toInt() and 0xFFFF
            val locHeaderOff = buf.getInt(cdPos + 42)

            if (cdPos + 46 + nameLen + extraLen > zipBytes.size) break
            val name = String(zipBytes, cdPos + 46, nameLen, Charsets.UTF_8)
            val extra = zipBytes.copyOfRange(cdPos + 46 + nameLen, cdPos + 46 + nameLen + extraLen)

            result += CentralEntryMeta(
                name = name,
                flags = flags,
                method = method,
                crc32 = crc32,
                compressedSize = compSize,
                uncompressedSize = uncompSize,
                localHeaderOffset = locHeaderOff,
                extra = extra
            )
            cdPos += 46 + nameLen + extraLen + commentLen
        }
        return result
    }
}
