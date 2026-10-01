package com.ashishkumar.nivara.ui.vault

import androidx.lifecycle.ViewModelStore
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.security.session.AuthenticatedSession
import com.ashishkumar.nivara.domain.security.session.AuthenticationSource
import com.ashishkumar.nivara.domain.security.session.SessionAuthenticationCompletion
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.content.VaultContentClassification
import com.ashishkumar.nivara.domain.vault.content.VaultContentPresentationGateway
import com.ashishkumar.nivara.domain.vault.content.VaultImagePixels
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemOpenState
import com.ashishkumar.nivara.domain.vault.content.VaultPresentationHandle
import com.ashishkumar.nivara.domain.vault.content.VaultPresentationOpenResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentObjectCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultItemViewerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun openingRequiresExistingSessionAndNeverAuthenticatesOrRefreshesIt() = runTest(dispatcher) {
        val session = FakeSession().apply { state.value = SessionState.Unauthenticated }
        val gateway = FakeGateway()
        val store = ViewModelStore()
        val vm = VaultItemViewerViewModel(vaultId, item, gateway, session).also { store.put("viewer", it) }
        vm.open()
        advanceUntilIdle()
        assertEquals(VaultItemOpenState.Locked, vm.state.value.openState)
        assertEquals(0, gateway.openCalls)
        assertEquals(0, session.authenticateCalls)
        assertEquals(0, session.lockCalls)
        store.clear()
    }

    @Test fun quickLockClosesActiveResourceWithoutViewerAuthentication() = runTest(dispatcher) {
        val session = FakeSession().apply { state.value = authenticated() }
        val gateway = FakeGateway()
        val store = ViewModelStore()
        val vm = VaultItemViewerViewModel(vaultId, item, gateway, session).also { store.put("viewer", it) }
        vm.open()
        advanceUntilIdle()
        assertEquals(VaultItemOpenState.Rendering, vm.state.value.openState)
        assertEquals(1, gateway.openCalls)
        assertEquals(0, session.authenticateCalls)
        assertEquals(0, session.lockCalls)

        session.lockNow()
        advanceUntilIdle()
        assertEquals(VaultItemOpenState.Locked, vm.state.value.openState)
        assertEquals(listOf(handle), gateway.closed)
        assertEquals(0, session.authenticateCalls)
        assertEquals(1, session.lockCalls)
        assertTrue(session.currentStateCalls > 0)
        store.clear()
    }

    @Test fun replacedAuthenticatedSessionClosesViewerEvenWithoutObservedUnauthenticatedEmission() = runTest(dispatcher) {
        val session = FakeSession().apply { state.value = authenticated() }
        val gateway = FakeGateway()
        val store = ViewModelStore()
        val vm = VaultItemViewerViewModel(vaultId, item, gateway, session).also { store.put("viewer", it) }
        vm.open()
        advanceUntilIdle()
        session.replaceWithNewSession()
        advanceUntilIdle()
        assertEquals(VaultItemOpenState.Locked, vm.state.value.openState)
        assertEquals(listOf(handle), gateway.closed)
        store.clear()
    }

    @Test fun closeDuringOpenClosesAHandleReturnedByTheCancelledGateway() = runTest(dispatcher) {
        val session = FakeSession().apply { state.value = authenticated() }
        val gateway = FakeGateway()
        val store = ViewModelStore()
        val vm = VaultItemViewerViewModel(vaultId, item, gateway, session).also { store.put("viewer", it) }
        gateway.afterAuthorization = vm::close
        vm.open()
        advanceUntilIdle()
        assertEquals(VaultItemOpenState.Closed, vm.state.value.openState)
        assertEquals(listOf(handle), gateway.closed)
        store.clear()
    }

    @Test fun unsupportedEncryptedObjectVersionIsNotCollapsedIntoUnreadable() = runTest(dispatcher) {
        val session = FakeSession().apply { state.value = authenticated() }
        val gateway = FakeGateway().apply { openResult = VaultPresentationOpenResult.UnsupportedVersion }
        val store = ViewModelStore()
        val vm = VaultItemViewerViewModel(vaultId, item, gateway, session).also { store.put("viewer", it) }
        vm.open()
        advanceUntilIdle()
        assertEquals(VaultItemOpenState.UnsupportedVersion, vm.state.value.openState)
        assertEquals(0, session.authenticateCalls)
        store.clear()
    }

    @Test fun closeIsIdempotentAndViewerDoesNotRetainAHandleAfterClosure() = runTest(dispatcher) {
        val session = FakeSession().apply { state.value = authenticated() }
        val gateway = FakeGateway()
        val store = ViewModelStore()
        val vm = VaultItemViewerViewModel(vaultId, item, gateway, session).also { store.put("viewer", it) }
        vm.open()
        advanceUntilIdle()
        vm.close()
        vm.close()
        advanceUntilIdle()
        assertEquals(VaultItemOpenState.Closed, vm.state.value.openState)
        assertEquals(null, vm.state.value.handle)
        assertEquals(listOf(handle), gateway.closed)
        store.clear()
    }

    private class FakeSession : SessionManager {
        val state = MutableStateFlow<SessionState>(SessionState.Unauthenticated)
        override val sessionState = state
        var authenticateCalls = 0
        var lockCalls = 0
        var currentStateCalls = 0
        override suspend fun authenticatePrimary(authenticate: suspend () -> AuthenticationResult): SessionAuthenticationCompletion<AuthenticationResult> {
            authenticateCalls++; return SessionAuthenticationCompletion(AuthenticationResult.Failed, false)
        }
        override suspend fun authenticateBiometric(authenticate: suspend () -> BiometricAuthenticationResult): SessionAuthenticationCompletion<BiometricAuthenticationResult> {
            authenticateCalls++; return SessionAuthenticationCompletion(BiometricAuthenticationResult.Failed, false)
        }
        override suspend fun currentState(): SessionState { currentStateCalls++; return state.value }
        override suspend fun mayAccessSensitiveContent() = state.value is SessionState.Authenticated
        override suspend fun lockNow() { lockCalls++; state.value = SessionState.Unauthenticated }
        fun replaceWithNewSession() { state.value = authenticated(2) }
    }

    private class FakeGateway : VaultContentPresentationGateway {
        var openCalls = 0
        var openResult: VaultPresentationOpenResult? = null
        var afterAuthorization: (() -> Unit)? = null
        val closed = mutableListOf<VaultPresentationHandle>()
        override suspend fun open(vaultId: VaultId, itemId: com.ashishkumar.nivara.domain.vault.content.VaultItemId, authorizationCheckpoint: suspend () -> Boolean): VaultPresentationOpenResult {
            openCalls++
            if (!authorizationCheckpoint()) return VaultPresentationOpenResult.AuthorizationExpired
            afterAuthorization?.invoke()
            return openResult ?: VaultPresentationOpenResult.Ready(handle, VaultContentClassification.PreviewKind.IMAGE)
        }
        override suspend fun image(handle: VaultPresentationHandle): VaultImagePixels? = VaultImagePixels(1, 1, intArrayOf(0xff000000.toInt()))
        override suspend fun text(handle: VaultPresentationHandle): String? = null
        override fun close(handle: VaultPresentationHandle) { if (handle !in closed) closed += handle }
    }

    companion object {
        private val vaultId = VaultId("00112233445566778899aabbccddeeff")
        private val handle = VaultPresentationHandle("fedcba9876543210fedcba9876543210")
        private val item = VaultItem(
            id = com.ashishkumar.nivara.domain.vault.content.VaultItemId("0123456789abcdef0123456789abcdef"),
            originalFilename = "picture.png", originalMimeType = "image/png", originalSizeBytes = 8,
            importedAtEpochMillis = 1, objectFormatVersion = VaultContentObjectCodec.VERSION,
            objectSizeBytes = VaultContentObjectCodec.HEADER_BYTES + 24L, encryptedItemKey = byteArrayOf(1),
        )
        private fun authenticated(authenticatedAt: Long = 1) = SessionState.Authenticated(
            AuthenticatedSession(AuthenticationSource.PRIMARY, authenticatedAt, 10_000),
        )
    }
}
