package com.ashishkumar.nivara.domain.credentials

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object CredentialRules {
    const val PIN_MIN_LENGTH = 6
    const val PIN_MAX_LENGTH = 12
    const val PASSWORD_MIN_LENGTH = 10
    const val PASSWORD_MAX_LENGTH = 128

    fun rejection(type: PrimaryCredentialType, value: CharArray): CredentialRejection? = when (type) {
        PrimaryCredentialType.PIN -> validatePin(value)
        PrimaryCredentialType.PASSWORD -> validatePassword(value)
        PrimaryCredentialType.PATTERN -> PatternCanonicalizer.validate(value)
    }

    /** Confirmation is compared over UTF-8 bytes without creating immutable Strings. */
    fun securelyMatches(first: CharArray, second: CharArray): Boolean {
        val firstBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(first))
        val secondBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(second))
        val firstBytes = ByteArray(firstBuffer.remaining())
        val secondBytes = ByteArray(secondBuffer.remaining())
        firstBuffer.get(firstBytes)
        secondBuffer.get(secondBytes)
        return try {
            MessageDigest.isEqual(firstBytes, secondBytes)
        } finally {
            firstBytes.fill(0)
            secondBytes.fill(0)
            if (firstBuffer.hasArray()) firstBuffer.array().fill(0)
            if (secondBuffer.hasArray()) secondBuffer.array().fill(0)
        }
    }

    private fun validatePin(value: CharArray): CredentialRejection? {
        if (value.size < PIN_MIN_LENGTH) return CredentialRejection.TOO_SHORT
        if (value.size > PIN_MAX_LENGTH) return CredentialRejection.TOO_LONG
        if (value.any { it !in '0'..'9' }) return CredentialRejection.INVALID_FORMAT
        if (isRepeating(value) || isSequential(value)) return CredentialRejection.TOO_WEAK
        return null
    }

    private fun validatePassword(value: CharArray): CredentialRejection? {
        if (value.size < PASSWORD_MIN_LENGTH) return CredentialRejection.TOO_SHORT
        if (value.size > PASSWORD_MAX_LENGTH) return CredentialRejection.TOO_LONG
        if (value.all { it.isWhitespace() } || value.any { Character.isISOControl(it) }) {
            return CredentialRejection.INVALID_FORMAT
        }
        return null
    }

    private fun isRepeating(value: CharArray): Boolean {
        for (period in 1..minOf(3, value.size / 2)) {
            if (value.indices.all { value[it] == value[it % period] }) return true
        }
        return false
    }

    private fun isSequential(value: CharArray): Boolean {
        val ascending = value.indices.drop(1).all { value[it] == value[it - 1] + 1 }
        val descending = value.indices.drop(1).all { value[it] == value[it - 1] - 1 }
        return ascending || descending
    }
}

/**
 * Canonical pattern bytes are [format version=1, point count, ordered zero-based cell IDs].
 * KDF input is their lowercase hexadecimal ASCII encoding, preserving order but never stored visibly.
 */
object PatternCanonicalizer {
    const val MIN_POINTS = 4
    const val MAX_POINTS = 9
    private const val FORMAT_VERSION = 1
    private const val HEX = "0123456789abcdef"

    fun canonicalize(points: IntArray): CharArray {
        if (points.size < 2) throw InvalidCredentialInput(CredentialRejection.PATTERN_TOO_SHORT)
        if (points.size > MAX_POINTS || points.any { it !in 0..8 } || points.toSet().size != points.size) {
            throw InvalidCredentialInput(CredentialRejection.INVALID_PATTERN)
        }
        val normalized = ArrayList<Int>(MAX_POINTS)
        try {
            points.forEach { point ->
                if (normalized.isNotEmpty()) {
                    val previous = normalized.last()
                    val rowDelta = point / 3 - previous / 3
                    val columnDelta = point % 3 - previous % 3
                    if (rowDelta % 2 == 0 && columnDelta % 2 == 0 &&
                        (kotlin.math.abs(rowDelta) == 2 || kotlin.math.abs(columnDelta) == 2)
                    ) {
                        val midpoint = (previous + point) / 2
                        if (midpoint !in normalized) normalized += midpoint
                    }
                }
                if (point !in normalized) normalized += point
            }
            if (normalized.size < MIN_POINTS) throw InvalidCredentialInput(CredentialRejection.PATTERN_TOO_SHORT)
            if (normalized.size > MAX_POINTS) throw InvalidCredentialInput(CredentialRejection.INVALID_PATTERN)
            val bytes = ByteArray(normalized.size + 2)
            bytes[0] = FORMAT_VERSION.toByte()
            bytes[1] = normalized.size.toByte()
            normalized.forEachIndexed { index, point -> bytes[index + 2] = point.toByte() }
            return try {
                CharArray(bytes.size * 2) { index ->
                    val byte = bytes[index / 2].toInt() and 0xFF
                    if (index % 2 == 0) HEX[byte ushr 4] else HEX[byte and 0x0F]
                }
            } finally {
                bytes.fill(0)
            }
        } finally {
            normalized.fill(0)
        }
    }

    fun validate(canonical: CharArray): CredentialRejection? {
        if (canonical.size < (MIN_POINTS + 2) * 2) return CredentialRejection.PATTERN_TOO_SHORT
        if (canonical.size > (MAX_POINTS + 2) * 2 || canonical.size % 2 != 0) {
            return CredentialRejection.INVALID_PATTERN
        }
        val bytes = ByteArray(canonical.size / 2)
        return try {
            for (index in bytes.indices) {
                val high = HEX.indexOf(canonical[index * 2])
                val low = HEX.indexOf(canonical[index * 2 + 1])
                if (high < 0 || low < 0) return CredentialRejection.INVALID_PATTERN
                bytes[index] = ((high shl 4) or low).toByte()
            }
            val count = bytes[1].toInt() and 0xFF
            if ((bytes[0].toInt() and 0xFF) != FORMAT_VERSION ||
                count !in MIN_POINTS..MAX_POINTS || count + 2 != bytes.size
            ) {
                return CredentialRejection.INVALID_PATTERN
            }
            val seen = BooleanArray(9)
            for (index in 2 until bytes.size) {
                val point = bytes[index].toInt() and 0xFF
                if (point !in 0..8 || seen[point]) return CredentialRejection.INVALID_PATTERN
                if (index > 2) {
                    val previous = bytes[index - 1].toInt() and 0xFF
                    val rowDelta = point / 3 - previous / 3
                    val columnDelta = point % 3 - previous % 3
                    if (rowDelta % 2 == 0 && columnDelta % 2 == 0 &&
                        (kotlin.math.abs(rowDelta) == 2 || kotlin.math.abs(columnDelta) == 2)
                    ) {
                        val midpoint = (previous + point) / 2
                        if (!seen[midpoint]) return CredentialRejection.INVALID_PATTERN
                    }
                }
                seen[point] = true
            }
            null
        } finally {
            bytes.fill(0)
        }
    }
}

class InvalidCredentialInput(val reason: CredentialRejection) : Exception("Credential input is invalid.")
