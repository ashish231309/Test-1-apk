package com.ashishkumar.nivara.data.apphide

import com.ashishkumar.nivara.domain.apphide.HiddenApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

class HiddenApplicationsCodecTest {
    @Test
    fun readableEmptyAndPackageSetsRoundTripDeterministically() {
        val empty = HiddenApplicationsCodec.encode(emptySet())
        assertEquals(emptySet<HiddenApplication>(), HiddenApplicationsCodec.decode(empty))

        val apps = setOf(HiddenApplication("com.example.zebra"), HiddenApplication("com.example.camera"))
        val encoded = HiddenApplicationsCodec.encode(apps)
        assertEquals(apps, HiddenApplicationsCodec.decode(encoded))
        assertTrue(encoded.contentEquals(HiddenApplicationsCodec.encode(apps.reversed().toSet())))
    }

    @Test
    fun decodedSnapshotDoesNotExposeAMutableSharedSet() {
        val decoded = HiddenApplicationsCodec.decode(
            HiddenApplicationsCodec.encode(setOf(HiddenApplication("com.example.camera"))),
        )!!
        assertThrows(UnsupportedOperationException::class.java) {
            (decoded as MutableSet<HiddenApplication>).add(HiddenApplication("com.example.notes"))
        }
    }

    @Test
    fun duplicatePackageEntriesAreRejected() {
        assertNull(encodeRaw(listOf("com.example.camera", "com.example.camera")).let(HiddenApplicationsCodec::decode))
    }

    @Test
    fun malformedPackagesEmptyValuesAndImpossibleLengthsAreRejected() {
        assertNull(HiddenApplicationsCodec.decode(encodeRaw(listOf("not a package"))))
        assertNull(HiddenApplicationsCodec.decode(encodeRaw(listOf(""))))
        assertNull(HiddenApplicationsCodec.decode(encodeRaw(listOf("com.example.camera"), forcedLength = 99)))
        assertNull(HiddenApplicationsCodec.decode(encodePayload { writeInt(1); writeInt(1); writeByte(0xff) }))
        assertNull(HiddenApplicationsCodec.decode(encodePayload { writeInt(0); writeInt(1) }))
    }

    @Test
    fun unsupportedVersionTruncatedTrailingBytesAndInvalidChecksumAreRejected() {
        val encoded = HiddenApplicationsCodec.encode(setOf(HiddenApplication("com.example.camera")))

        val unsupported = encoded.copyOf()
        unsupported[11] = 2
        assertNull(HiddenApplicationsCodec.decode(unsupported))

        assertNull(HiddenApplicationsCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(HiddenApplicationsCodec.decode(encoded + byteArrayOf(0)))

        val badChecksum = encoded.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x1).toByte() }
        assertNull(HiddenApplicationsCodec.decode(badChecksum))
    }

    @Test
    fun badMagicAndOutOfRangeCountAreRejected() {
        val valid = HiddenApplicationsCodec.encode(emptySet())
        val badMagic = valid.copyOf().also { it[0] = 0 }
        assertNull(HiddenApplicationsCodec.decode(badMagic))
        assertNull(HiddenApplicationsCodec.decode(encodePayload { writeInt(Int.MAX_VALUE) }))
    }

    private fun encodeRaw(packageNames: List<String>, forcedLength: Int? = null): ByteArray = encodePayload {
        writeInt(packageNames.size)
        packageNames.forEachIndexed { index, name ->
            val bytes = name.toByteArray(Charsets.UTF_8)
            writeInt(if (index == 0 && forcedLength != null) forcedLength else bytes.size)
            write(bytes)
        }
    }

    private fun encodePayload(writePayload: DataOutputStream.() -> Unit): ByteArray {
        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { output -> output.writePayload() }
        val payload = payloadBuffer.toByteArray()
        val bodyBuffer = ByteArrayOutputStream()
        DataOutputStream(bodyBuffer).use { output ->
            output.write(byteArrayOf(0x4e, 0x56, 0x48, 0x49, 0x44, 0x45, 0x4e, 0x01))
            output.writeInt(1)
            output.writeInt(payload.size)
            output.write(payload)
        }
        val body = bodyBuffer.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
}
