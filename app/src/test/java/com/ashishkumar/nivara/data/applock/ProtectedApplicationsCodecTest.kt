package com.ashishkumar.nivara.data.applock

import com.ashishkumar.nivara.domain.applock.ProtectedApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedApplicationsCodecTest {
    @Test
    fun emptySetHasAnUnambiguousEncoding() {
        assertEquals("", ProtectedApplicationsCodec.encode(emptySet()))
        assertEquals(emptySet<ProtectedApplication>(), ProtectedApplicationsCodec.decode(""))
    }

    @Test
    fun packageIdentifiersRoundTripInDeterministicOrder() {
        val packages = setOf(
            ProtectedApplication("com.example.zeta"),
            ProtectedApplication("com.example.alpha"),
        )

        assertEquals("com.example.alpha\ncom.example.zeta", ProtectedApplicationsCodec.encode(packages))
        assertEquals(packages, ProtectedApplicationsCodec.decode(ProtectedApplicationsCodec.encode(packages)))
    }

    @Test
    fun duplicateIdentifiersAreCollapsedAndMalformedPayloadIsRejected() {
        assertEquals(
            setOf(ProtectedApplication("com.example.app")),
            ProtectedApplicationsCodec.decode("com.example.app\ncom.example.app"),
        )
        assertNull(ProtectedApplicationsCodec.decode("com.example.app\n"))
        assertNull(ProtectedApplicationsCodec.decode("not a package"))
    }

    @Test
    fun packageNameAloneDefinesProtectedIdentity() {
        val application = ProtectedApplication("com.example.app")
        assertEquals("com.example.app", application.packageName)
        assertTrue(runCatching { ProtectedApplication(" com.example.app ") }.isFailure)
        assertFalse(runCatching { ProtectedApplication("") }.isSuccess)
    }
}
