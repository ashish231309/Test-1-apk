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
import com.ashishkumar.nivara.domain.vault.VaultInitializationResult
import com.ashishkumar.nivara.domain.vault.VaultRepository
import com.ashishkumar.nivara.domain.vault.VaultRootSelectionResult
import com.ashishkumar.nivara.domain.vault.VaultStatus
import com.ashishkumar.nivara.domain.vault.content.*
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
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test
    fun inspectionAndInitializationAreBlockedWithoutTheExistingSession() = runTest(dispatcher) {
        val repository = FakeVaultRepository()
        val session = FakeSessionManager()
        val store = ViewModelStore()
        val viewModel = newViewModel(repository, session).also { store.put("vault", it) }

        viewModel.refresh()
        viewModel.initialize()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.authenticationRequired)
        assertEquals(0, repository.inspectCalls)
        assertEquals(0, repository.initializeCalls)
        store.clear()
    }

    @Test
    fun sessionManagerFailureFailsClosedBeforeVaultAccess() = runTest(dispatcher) {
        val repository = FakeVaultRepository()
        val session = FakeSessionManager().apply { failSessionCheck = true }
        val store = ViewModelStore()
        val viewModel = newViewModel(repository, session).also { store.put("vault", it) }

        viewModel.refresh()
        viewModel.initialize()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.authenticationRequired)
        assertEquals(0, repository.inspectCalls)
        assertEquals(0, repository.initializeCalls)
        store.clear()
    }

    @Test
    fun existingAuthenticatedSessionUnlocksOnlyTheVaultUseCaseGate() = runTest(dispatcher) {
        val repository = FakeVaultRepository()
        val session = FakeSessionManager().apply { state.value = authenticatedSession() }
        val store = ViewModelStore()
        val viewModel = newViewModel(repository, session).also { store.put("vault", it) }

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(VaultStatus.RootNotSelected, viewModel.state.value.status)
        assertEquals(1, repository.inspectCalls)
        assertEquals(0, session.primaryAuthCalls)
        assertEquals(0, session.biometricAuthCalls)

        repository.initializeResult = VaultInitializationResult.Initialized(
            VaultId("00112233445566778899aabbccddeeff"),
        )
        viewModel.initialize()
        advanceUntilIdle()
        assertEquals(1, repository.initializeCalls)
        assertEquals(VaultStatus.RootNotSelected, viewModel.state.value.status)
        store.clear()
    }

    @Test
    fun sessionExpiryReappliesGateAndPickerOutcomeDoesNotExposeLocation() = runTest(dispatcher) {
        val repository = FakeVaultRepository()
        val session = FakeSessionManager().apply { state.value = authenticatedSession() }
        val store = ViewModelStore()
        val viewModel = newViewModel(repository, session).also { store.put("vault", it) }
        viewModel.refresh()
        advanceUntilIdle()

        session.state.value = SessionState.Unauthenticated
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.authenticationRequired)
        assertEquals(1, repository.inspectCalls)

        viewModel.onRootSelectionResult(VaultRootSelectionResult.DIFFERENT_ROOT_ALREADY_SELECTED)
        assertTrue(viewModel.state.value.message.orEmpty().contains("not replaced"))
        assertTrue(viewModel.state.value.message.orEmpty().contains("folder"))
        store.clear()
    }

    private fun newViewModel(repository: FakeVaultRepository, session: FakeSessionManager) =
        VaultViewModel(repository, MissingIndexRepository(), NoopImportRepository(), session)

    private fun authenticatedSession() = SessionState.Authenticated(
        AuthenticatedSession(AuthenticationSource.PRIMARY, 0, Long.MAX_VALUE),
    )

    private class FakeVaultRepository : VaultRepository {
        var inspectCalls = 0
        var initializeCalls = 0
        var status: VaultStatus = VaultStatus.RootNotSelected
        var initializeResult: VaultInitializationResult = VaultInitializationResult.RootNotSelected
        override suspend fun inspect(): VaultStatus {
            inspectCalls++
            return status
        }
        override suspend fun initialize(): VaultInitializationResult {
            initializeCalls++
            return initializeResult
        }
    }

    private class MissingIndexRepository : VaultIndexRepository {
        override suspend fun inspect(vaultId: VaultId): VaultIndexRead = VaultIndexRead.Missing
        override suspend fun listItems(vaultId: VaultId): VaultIndexRead = VaultIndexRead.Missing
        override suspend fun initializeEmpty(vaultId: VaultId, authorizationCheckpoint: suspend () -> Boolean) =
            VaultIndexInitializationResult.Unavailable
        override suspend fun addItem(vaultId: VaultId, item: VaultItem, authorizationCheckpoint: suspend () -> Boolean) =
            VaultIndexWriteResult.Failed
    }

    private class NoopImportRepository : VaultImportRepository {
        override fun discard(sourceId: VaultSourceSelectionId) = Unit
        override suspend fun importDocument(
            sourceId: VaultSourceSelectionId, vaultId: VaultId,
            authorizationCheckpoint: suspend () -> Boolean, onProgress: (Long, Long?) -> Unit,
        ) = VaultImportResult.Failed
    }

    private class FakeSessionManager : SessionManager {
        val state = MutableStateFlow<SessionState>(SessionState.Unauthenticated)
        override val sessionState = state
        var primaryAuthCalls = 0
        var biometricAuthCalls = 0
        var failSessionCheck = false
        override suspend fun currentState(): SessionState {
            if (failSessionCheck) throw IllegalStateException("session unavailable")
            return state.value
        }
        override suspend fun mayAccessSensitiveContent() = state.value is SessionState.Authenticated
        override suspend fun lockNow() { state.value = SessionState.Unauthenticated }
        override suspend fun authenticatePrimary(
            authenticate: suspend () -> AuthenticationResult,
        ): SessionAuthenticationCompletion<AuthenticationResult> {
            primaryAuthCalls++
            return SessionAuthenticationCompletion(authenticate(), sessionEstablished = false)
        }
        override suspend fun authenticateBiometric(
            authenticate: suspend () -> BiometricAuthenticationResult,
        ): SessionAuthenticationCompletion<BiometricAuthenticationResult> {
            biometricAuthCalls++
            return SessionAuthenticationCompletion(authenticate(), sessionEstablished = false)
        }
    }
}
