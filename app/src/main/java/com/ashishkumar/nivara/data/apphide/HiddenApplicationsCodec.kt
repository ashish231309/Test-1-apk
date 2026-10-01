package com.ashishkumar.nivara.data.apphide

import com.ashishkumar.nivara.domain.apphide.HiddenApplication
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.security.MessageDigest

/** Versioned deterministic binary encoding for package identifiers only. */
internal object HiddenApplicationsCodec {
    private val MAGIC = byteArrayOf(0x4e, 0x56, 0x48, 0x49, 0x44, 0x45, 0x4e, 0x01) // NVHIDEN + marker
    private const val FORMAT_VERSION = 1
    private const val CHECKSUM_LENGTH = 32
    internal const val MAX_FILE_LENGTH = 1_048_576
    private const val MAX_APPLICATIONS = 4_096
    private const val MAX_PACKAGE_BYTES = 255
    private const val HEADER_LENGTH = 8 + Int.SIZE_BYTES + Int.SIZE_BYTES

    fun encode(applications: Set<HiddenApplication>): ByteArray {
        require(applications.size <= MAX_APPLICATIONS)
        val payloadBytes = ByteArrayOutputStream().also { payloadBuffer ->
            DataOutputStream(payloadBuffer).use { payload ->
                val ordered = applications.map(HiddenApplication::packageName).sorted()
                payload.writeInt(ordered.size)
                ordered.forEach { packageName ->
                    val encoded = packageName.toByteArray(StandardCharsets.UTF_8)
                    require(encoded.isNotEmpty() && encoded.size <= MAX_PACKAGE_BYTES)
                    payload.writeInt(encoded.size)
                    payload.write(encoded)
                }
            }
        }.toByteArray()
        val body = ByteArrayOutputStream().also { bodyBuffer ->
            DataOutputStream(bodyBuffer).use { output ->
                output.write(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(payloadBytes.size)
                output.write(payloadBytes)
            }
        }.toByteArray()
        require(body.size + CHECKSUM_LENGTH <= MAX_FILE_LENGTH)
        val checksum = MessageDigest.getInstance("SHA-256").digest(body)
        return body + checksum
    }

    /** Returns null for malformed, unsupported, truncated, duplicate, or checksum-invalid data. */
    fun decode(bytes: ByteArray): Set<HiddenApplication>? {
        if (bytes.size < HEADER_LENGTH + 4 + CHECKSUM_LENGTH || bytes.size > MAX_FILE_LENGTH) return null
        return try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return null
            if (input.readInt() != FORMAT_VERSION) return null
            val payloadLength = input.readInt()
            if (payloadLength < 4 || payloadLength != bytes.size - HEADER_LENGTH - CHECKSUM_LENGTH) return null
            val payloadBytes = ByteArray(payloadLength)
            input.readFully(payloadBytes)
            val checksum = ByteArray(CHECKSUM_LENGTH)
            input.readFully(checksum)
            if (input.available() != 0) return null

            val bodyLength = bytes.size - CHECKSUM_LENGTH
            val body = bytes.copyOfRange(0, bodyLength)
            val expectedChecksum = MessageDigest.getInstance("SHA-256").digest(body)
            if (!MessageDigest.isEqual(checksum, expectedChecksum)) return null

            val payloadInput = DataInputStream(ByteArrayInputStream(payloadBytes))
            val count = payloadInput.readInt()
            if (count < 0 || count > MAX_APPLICATIONS) return null
            val applications = LinkedHashSet<HiddenApplication>(count)
            repeat(count) {
                val length = payloadInput.readInt()
                if (length <= 0 || length > MAX_PACKAGE_BYTES || length > payloadInput.available()) return null
                val encodedPackage = ByteArray(length)
                payloadInput.readFully(encodedPackage)
                val packageName = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encodedPackage))
                    .toString()
                val application = try {
                    HiddenApplication(packageName)
                } catch (_: IllegalArgumentException) {
                    return null
                }
                if (!applications.add(application)) return null
            }
            if (payloadInput.available() != 0) return null
            Collections.unmodifiableSet(applications)
        } catch (_: Exception) {
            null
        }
    }
}
