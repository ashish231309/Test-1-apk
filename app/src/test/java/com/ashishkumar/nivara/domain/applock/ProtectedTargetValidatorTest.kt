package com.ashishkumar.nivara.domain.applock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedTargetValidatorTest {
    @Test
    fun currentPackageMustMatchTheRequestExactlyAndRemainConfiguredAndLaunchable() {
        val request = ProtectionRequest(1, PackageA)
        val protected = setOf(ProtectedApplication(PackageA))
        val launchable = setOf(PackageA, PackageB)

        assertTrue(ProtectedTargetValidator.isCurrent(request, PackageA, NivaraPackage, protected, launchable))
        assertFalse(ProtectedTargetValidator.isCurrent(request, NivaraPackage, NivaraPackage, protected, launchable))
        assertTrue(ProtectedTargetValidator.isCurrent(
            request, NivaraPackage, NivaraPackage, protected, launchable, allowNivaraForeground = true,
        ))
        assertFalse(ProtectedTargetValidator.isCurrent(request, PackageB, NivaraPackage, protected, launchable))
        assertFalse(ProtectedTargetValidator.isCurrent(request, null, NivaraPackage, protected, launchable))
        assertFalse(ProtectedTargetValidator.isCurrent(request, PackageA, NivaraPackage, emptySet(), launchable))
        assertFalse(ProtectedTargetValidator.isCurrent(request, PackageA, NivaraPackage, protected, setOf(PackageB)))
    }

    private companion object {
        const val PackageA = "com.example.alpha"
        const val PackageB = "com.example.beta"
        const val NivaraPackage = "com.example.nivara"
    }
}
