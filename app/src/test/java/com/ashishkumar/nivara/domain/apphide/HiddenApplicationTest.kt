package com.ashishkumar.nivara.domain.apphide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HiddenApplicationTest {
    @Test
    fun exactPackageNameIsTheOnlyIdentity() {
        val original = HiddenApplication("com.example.camera")
        val same = HiddenApplication("com.example.camera")
        val differentCase = HiddenApplication("com.example.Camera")

        assertEquals(original, same)
        assertEquals(original.hashCode(), same.hashCode())
        assertNotEquals(original, differentCase)
    }

    @Test
    fun malformedOrEmptyPackageNamesAreRejected() {
        val maximumLength = "com." + "a".repeat(251)
        assertEquals(255, HiddenApplication(maximumLength).packageName.length)
        val tooLong = "com." + "a".repeat(252)
        listOf("", " ", " com.example.camera", "com.example.camera ", "com", "com..camera", "com.1camera", tooLong)
            .forEach { packageName -> assertThrows(IllegalArgumentException::class.java) { HiddenApplication(packageName) } }
    }
}
