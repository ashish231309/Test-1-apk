package com.ashishkumar.nivara.domain.applock

import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPresentationStateMachineTest {
    @Test
    fun normalLifecycleIsIdleShowingAuthenticatingDismissingThenIdle() {
        val machine = AppLockPresentationStateMachine()
        val request = machine.requestAuthentication(PackageA)!!
        assertEquals(AppLockPresentationState.Showing(request, null), machine.state)

        assertTrue(machine.setPrimaryCredentialType(request.requestId, PrimaryCredentialType.PATTERN))
        assertTrue(machine.beginAuthentication(request.requestId, AuthenticationFactor.PRIMARY))
        assertEquals(
            AppLockPresentationState.Authenticating(request, AuthenticationFactor.PRIMARY, PrimaryCredentialType.PATTERN),
            machine.state,
        )
        assertTrue(machine.showFeedback(request.requestId, AppLockOverlayFeedback.PRIMARY_CREDENTIAL_REJECTED))
        assertEquals(
            AppLockPresentationState.Showing(
                request,
                PrimaryCredentialType.PATTERN,
                AppLockOverlayFeedback.PRIMARY_CREDENTIAL_REJECTED,
            ),
            machine.state,
        )
        assertTrue(machine.clearFeedback(request.requestId))
        assertEquals(AppLockPresentationState.Showing(request, PrimaryCredentialType.PATTERN), machine.state)
        assertTrue(machine.beginDismiss(request.requestId))
        assertEquals(AppLockPresentationState.Dismissing(request), machine.state)
        assertTrue(machine.finishDismiss(request.requestId))
        assertEquals(AppLockPresentationState.Idle, machine.state)
    }

    @Test
    fun duplicateRequestsForSamePackageDoNotCreateAnotherSurface() {
        val machine = AppLockPresentationStateMachine()
        val request = machine.requestAuthentication(PackageA)!!

        assertNull(machine.requestAuthentication(PackageA))
        assertEquals(request, machine.currentRequest())
        assertEquals(AppLockPresentationState.Showing(request, null), machine.state)
    }

    @Test
    fun aNewPackageInvalidatesOldRequestAndOldAuthenticationCannotMutateIt() {
        val machine = AppLockPresentationStateMachine()
        val first = machine.requestAuthentication(PackageA)!!
        assertTrue(machine.beginAuthentication(first.requestId, AuthenticationFactor.BIOMETRIC))
        val second = machine.requestAuthentication(PackageB)!!

        assertNotEquals(first.requestId, second.requestId)
        assertEquals(PackageB, machine.currentRequest()?.packageName)
        assertFalse(machine.showFeedback(first.requestId, AppLockOverlayFeedback.BIOMETRIC_CANCELLED))
        assertFalse(machine.beginDismiss(first.requestId))
        assertTrue(machine.isCurrent(second.requestId))
        assertFalse(machine.isCurrent(first.requestId))
    }

    @Test
    fun failuresCanBeRetriedAndCleanupIsIdempotent() {
        val machine = AppLockPresentationStateMachine()
        val request = machine.requestAuthentication(PackageA)!!
        assertTrue(machine.fail(request.requestId, AppLockPresentationFailure.WINDOW_MANAGER_UNAVAILABLE))
        assertEquals(
            AppLockPresentationState.Failed(request, AppLockPresentationFailure.WINDOW_MANAGER_UNAVAILABLE),
            machine.state,
        )
        assertFalse(machine.showFeedback(request.requestId, AppLockOverlayFeedback.BIOMETRIC_CANCELLED))
        assertTrue(machine.retry(request.requestId))
        assertTrue(machine.beginDismiss(request.requestId))
        assertFalse(machine.beginDismiss(request.requestId))
        assertTrue(machine.finishDismiss(request.requestId))
        assertFalse(machine.finishDismiss(request.requestId))
        assertEquals(AppLockPresentationState.Idle, machine.state)
        assertNull(machine.currentRequest())
    }

    @Test
    fun permissionStatesRemainExplicitAndSettingsFailureIsSeparate() {
        val repository = FakeOverlayCapabilityRepository()
        OverlayCapabilityStatus.entries.forEach { expected ->
            repository.capability = expected
            assertEquals(expected, repository.status())
        }
        assertEquals(OverlaySettingsResult.OPENED, repository.openSettings())
        repository.settingsResult = OverlaySettingsResult.FAILED
        assertEquals(OverlaySettingsResult.FAILED, repository.openSettings())

        val machine = AppLockPresentationStateMachine()
        val request = machine.requestAuthentication(PackageA)!!
        assertTrue(machine.fail(request.requestId, AppLockPresentationFailure.OVERLAY_PERMISSION_NOT_GRANTED))
        assertEquals(AppLockPresentationFailure.OVERLAY_PERMISSION_NOT_GRANTED,
            (machine.state as AppLockPresentationState.Failed).reason)
        assertTrue(machine.retry(request.requestId))
        assertTrue(machine.fail(request.requestId, AppLockPresentationFailure.OVERLAY_PERMISSION_UNAVAILABLE))
        assertEquals(AppLockPresentationFailure.OVERLAY_PERMISSION_UNAVAILABLE,
            (machine.state as AppLockPresentationState.Failed).reason)
    }

    private class FakeOverlayCapabilityRepository : OverlayCapabilityRepository {
        var capability = OverlayCapabilityStatus.UNAVAILABLE
        var settingsResult = OverlaySettingsResult.OPENED
        override fun status() = capability
        override fun openSettings() = settingsResult
    }

    private companion object {
        const val PackageA = "com.example.alpha"
        const val PackageB = "com.example.beta"
    }
}
