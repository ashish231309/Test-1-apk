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
class VaultViewModelTrashTest {
    private val dispatcher = StandardTestDispatcher()
    private val vaultId = VaultId("00112233445566778899aabbccddeeff")
    private val active = item(1, "active report.pdf", VaultItemLifecycle.ACTIVE)
    private val trashed = item(2, "archived report.pdf", VaultItemLifecycle.TRASHED, 1234)

    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun activeBrowsingSearchAndTrashSearchAreDisjointAndTrashSortIsStable() = runTest(dispatcher) {
        val session = TestSession().apply { state.value = authenticated() }
        val index = TestIndex(listOf(active, trashed))
        val store = ViewModelStore()
        val vm = VaultViewModel(TestVault(), index, TestImport(), session).also { store.put("vault", it) }
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.searchState is VaultSearchState.EmptyQuery)
        assertEquals(listOf(active.id), (vm.state.value.searchState as VaultSearchState.EmptyQuery).items.map { it.id })
        vm.showSearch(); vm.setSearchQuery("archived")
        assertEquals(VaultSearchState.NoMatches("archived"), vm.state.value.searchState)
        vm.showTrash(); vm.setTrashSearchQuery("  ARCHIVED ")
        assertEquals(listOf(trashed.id), (vm.state.value.trashSearchState as VaultSearchState.Matches).items.map { it.id })
        assertTrue(vm.state.value.trashState is VaultTrashState.Populated)
        assertEquals(VaultCollectionMode.TRASH, vm.state.value.collectionMode)
        store.clear()
    }

    @Test fun restoreAndTrashUpdateTheAuthenticatedSnapshotWithoutRescanningOrChangingIdentity() = runTest(dispatcher) {
        val session = TestSession().apply { state.value = authenticated() }
        val diagnostics = VaultContentDiagnostics(1, 0, 0, setOf(trashed.id))
        val index = TestIndex(listOf(active, trashed), diagnostics)
        val store = ViewModelStore()
        val vm = VaultViewModel(TestVault(), index, TestImport(), session).also { store.put("vault", it) }
        vm.refresh(); advanceUntilIdle()
        val inspectionsBefore = index.inspectCalls
        vm.restoreFromTrash(trashed.id); advanceUntilIdle()
        val restored = (vm.state.value.indexState as VaultIndexRead.Ready).items.first { it.id == trashed.id }
        assertEquals(VaultItemLifecycle.ACTIVE, restored.lifecycle)
        assertEquals(trashed.id, restored.id)
        assertEquals(diagnostics, (vm.state.value.indexState as VaultIndexRead.Ready).contentDiagnostics)
        assertTrue(vm.state.value.message.orEmpty().contains("restored"))
        vm.moveToTrash(active.id); advanceUntilIdle()
        assertEquals(VaultItemLifecycle.TRASHED,
            (vm.state.value.indexState as VaultIndexRead.Ready).items.first { it.id == active.id }.lifecycle)
        assertEquals(inspectionsBefore, index.inspectCalls)
        assertEquals(2, index.mutationCalls)
        store.clear()
    }

    @Test fun sessionExpiryAfterDurableMutationClearsUiAndNextAuthorizedRefreshObservesIt() = runTest(dispatcher) {
        val session = TestSession().apply { state.value = authenticated() }
        val index = TestIndex(listOf(active))
        val store = ViewModelStore()
        val vm = VaultViewModel(TestVault(), index, TestImport(), session).also { store.put("vault", it) }
        vm.refresh(); advanceUntilIdle()
        index.afterMutation = { session.state.value = SessionState.Unauthenticated }
        vm.moveToTrash(active.id); advanceUntilIdle()
        assertTrue(vm.state.value.authenticationRequired)
        assertEquals(null, vm.state.value.indexState)
        assertEquals(VaultItemLifecycle.TRASHED, (index.result as VaultIndexRead.Ready).items.single().lifecycle)
        session.state.value = authenticated()
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.trashState is VaultTrashState.Populated)
        store.clear()
    }

    @Test fun unauthorizedMutationIsNotForwardedAndUnreadableOrEmptyRemainDistinct() = runTest(dispatcher) {
        val session = TestSession()
        val index = TestIndex(emptyList())
        val store = ViewModelStore()
        val vm = VaultViewModel(TestVault(), index, TestImport(), session).also { store.put("vault", it) }
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.authenticationRequired)
        assertEquals(VaultTrashState.Locked, vm.state.value.trashState)
        assertEquals(0, index.mutationCalls)

        session.state.value = authenticated()
        index.result = VaultIndexRead.Ready(4, emptyList())
        vm.refresh(); advanceUntilIdle()
        assertEquals(VaultTrashState.Empty, vm.state.value.trashState)
        index.result = VaultIndexRead.Corrupt
        vm.refresh(); advanceUntilIdle()
        assertEquals(VaultTrashState.Unreadable, vm.state.value.trashState)
        index.result = VaultIndexRead.Unavailable
        vm.refresh(); advanceUntilIdle()
        assertEquals(VaultTrashState.Unavailable, vm.state.value.trashState)
        index.result = VaultIndexRead.AccessDenied
        vm.refresh(); advanceUntilIdle()
        assertEquals(VaultTrashState.AccessDenied, vm.state.value.trashState)
        index.result = VaultIndexRead.VaultUnavailable(VaultStatus.AccessDenied)
        vm.refresh(); advanceUntilIdle()
        assertEquals(VaultTrashState.VaultUnavailable, vm.state.value.trashState)
        index.result = VaultIndexRead.UnsupportedVersion(9)
        vm.refresh(); advanceUntilIdle()
        assertEquals(VaultTrashState.UnsupportedVersion(9), vm.state.value.trashState)
        store.clear()
    }

    private fun authenticated() = SessionState.Authenticated(AuthenticatedSession(AuthenticationSource.PRIMARY, 0, Long.MAX_VALUE))

    private class TestVault : VaultRepository {
        override suspend fun inspect(): VaultStatus = VaultStatus.Ready(VaultId("00112233445566778899aabbccddeeff"))
        override suspend fun initialize(): VaultInitializationResult = VaultInitializationResult.AlreadyInitialized
    }

    private class TestSession : SessionManager {
        val state = MutableStateFlow<SessionState>(SessionState.Unauthenticated)
        override val sessionState = state
        override suspend fun currentState() = state.value
        override suspend fun mayAccessSensitiveContent() = state.value is SessionState.Authenticated
        override suspend fun lockNow() { state.value = SessionState.Unauthenticated }
        override suspend fun authenticatePrimary(authenticate: suspend () -> AuthenticationResult) = SessionAuthenticationCompletion(authenticate(), false)
        override suspend fun authenticateBiometric(authenticate: suspend () -> BiometricAuthenticationResult) = SessionAuthenticationCompletion(authenticate(), false)
    }

    private class TestIndex(initial: List<VaultItem>, diagnostics: VaultContentDiagnostics? = null) : VaultIndexRepository {
        var result: VaultIndexRead = VaultIndexRead.Ready(5, initial, diagnostics)
        var inspectCalls = 0
        var mutationCalls = 0
        var afterMutation: (() -> Unit)? = null
        override suspend fun inspect(vaultId: VaultId): VaultIndexRead { inspectCalls++; return result }
        override suspend fun listItems(vaultId: VaultId) = inspect(vaultId)
        override suspend fun initializeEmpty(vaultId: VaultId, authorizationCheckpoint: suspend () -> Boolean) = VaultIndexInitializationResult.Unavailable
        override suspend fun addItem(vaultId: VaultId, item: VaultItem, authorizationCheckpoint: suspend () -> Boolean) = VaultIndexWriteResult.Failed
        override suspend fun moveToTrash(vaultId: VaultId, itemId: VaultItemId, authorizationCheckpoint: suspend () -> Boolean): VaultItemStateMutationResult =
            update(vaultId, itemId, VaultItemLifecycle.TRASHED, authorizationCheckpoint)
        override suspend fun restoreFromTrash(vaultId: VaultId, itemId: VaultItemId, authorizationCheckpoint: suspend () -> Boolean): VaultItemStateMutationResult =
            update(vaultId, itemId, VaultItemLifecycle.ACTIVE, authorizationCheckpoint)
        private suspend fun update(vaultId: VaultId, itemId: VaultItemId, lifecycle: VaultItemLifecycle,
            authorizationCheckpoint: suspend () -> Boolean): VaultItemStateMutationResult {
            mutationCalls++
            if (!authorizationCheckpoint()) return VaultItemStateMutationResult.AuthorizationExpired
            val current = result as? VaultIndexRead.Ready ?: return VaultItemStateMutationResult.Corrupt
            val old = current.items.firstOrNull { it.id == itemId } ?: return VaultItemStateMutationResult.ItemNotFound
            if (old.lifecycle == lifecycle) return if (lifecycle == VaultItemLifecycle.ACTIVE)
                VaultItemStateMutationResult.AlreadyActive else VaultItemStateMutationResult.AlreadyTrashed
            val changed = old.withLifecycle(lifecycle, if (lifecycle == VaultItemLifecycle.TRASHED) 777L else null)
            val items = current.items.map { if (it.id == itemId) changed else it }
            val next = current.copy(generation = current.generation + 1, items = items)
            result = next
            afterMutation?.invoke()
            return VaultItemStateMutationResult.Changed(changed, next.generation, next.items, true)
        }
    }

    private class TestImport : VaultImportRepository {
        override fun discard(sourceId: VaultSourceSelectionId) = Unit
        override suspend fun importDocument(sourceId: VaultSourceSelectionId, vaultId: VaultId,
            authorizationCheckpoint: suspend () -> Boolean, onProgress: (Long, Long?) -> Unit) = VaultImportResult.Failed
    }

    private fun item(number: Int, name: String, lifecycle: VaultItemLifecycle, trashedAt: Long? = null) = VaultItem(
        id = VaultItemId(number.toString(16).padStart(32, '0')),
        originalFilename = name,
        originalMimeType = "application/pdf",
        originalSizeBytes = 42,
        importedAtEpochMillis = 10,
        objectFormatVersion = VaultContentObjectCodec.VERSION,
        objectSizeBytes = VaultContentObjectCodec.HEADER_BYTES + 42 + 16L,
        encryptedItemKey = byteArrayOf(1, 2, 3),
        lifecycle = lifecycle,
        trashedAtEpochMillis = trashedAt,
        contentDigestSha256 = ByteArray(VaultItem.SHA_256_BYTES) { number.toByte() },
    )
}
