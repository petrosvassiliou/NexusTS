package com.nexusts.app.core.vault.credentials

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

data class TotpSnapshot(
    val code: String,
    val secondsRemaining: Int,
    val periodSeconds: Int = 30
)

/**
 * RFC 6238 Time-Based One-Time Password (TOTP) & RFC 4226 HOTP Generator.
 * Powers live 6-digit 2FA codes in the Credential Studio, Contextual Property Inspector,
 * and `$EffectiveTOTP$` macro auto-type tokens in [com.nexusts.app.core.vault.automation.AutomationTaskEngine].
 */
object TotpCodeGenerator {
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun generateTotp(
        base32Secret: String,
        epochMillis: Long = System.currentTimeMillis(),
        periodSeconds: Int = 30,
        digits: Int = 6
    ): TotpSnapshot? {
        val secretBytes = decodeBase32(base32Secret)
        if (secretBytes.isEmpty()) return null

        val epochSeconds = (epochMillis / 1000L).coerceAtLeast(0L)
        val counter = epochSeconds / periodSeconds
        val secondsRemaining = (periodSeconds - (epochSeconds % periodSeconds).toInt()).coerceIn(1, periodSeconds)

        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)

        val offset = hash[hash.lastIndex].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val modulus = 10.0.pow(digits).toInt()
        val otp = binary % modulus
        return TotpSnapshot(
            code = otp.toString().padStart(digits, '0'),
            secondsRemaining = secondsRemaining,
            periodSeconds = periodSeconds
        )
    }

    fun decodeBase32(raw: String): ByteArray {
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                if (ch == ' ' || ch == '-' || ch == '=') continue
                append(ch.uppercaseChar())
            }
        }
        if (cleaned.isEmpty()) return ByteArray(0)

        val out = ByteArray((cleaned.length * 5) / 8)
        var buffer = 0
        var bitsLeft = 0
        var outIndex = 0

        for (ch in cleaned) {
            val value = BASE32_ALPHABET.indexOf(ch)
            if (value < 0) return ByteArray(0)
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                if (outIndex < out.size) {
                    out[outIndex++] = ((buffer shr bitsLeft) and 0xFF).toByte()
                }
            }
        }
        return if (outIndex == out.size) out else out.copyOf(outIndex)
    }
}
