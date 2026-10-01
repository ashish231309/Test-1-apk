package com.ashishkumar.nivara.ui.vault

import androidx.lifecycle.ViewModelStore
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.security.session.*
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
class VaultViewModelOrganizationTest {
    private val dispatcher = StandardTestDispatcher()
    private val vaultId = VaultId("00112233445566778899aabbccddeeff")

    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun collectionSearchSortAndOrganizationStatesAreExposedSeparately() = runTest(dispatcher) {
        val session = TestSession().apply { state.value = authenticated() }
        val index = TestIndex().apply { result = VaultIndexRead.Ready(3, emptyList()) }
        val organization = TestOrganization()
        val store = ViewModelStore()
        val vm = VaultViewModel(TestVault(), index, TestImport(), session, organization).also { store.put("vault", it) }

        vm.refresh()
        advanceUntilIdle()
        assertEquals(VaultStatus.Ready(vaultId), vm.state.value.status)
        assertTrue(vm.state.value.indexState is VaultIndexRead.Ready)
        assertTrue(vm.state.value.organizationState is VaultOrganizationRead.Ready)
        assertTrue(vm.state.value.searchState is VaultSearchState.EmptyQuery)
        vm.showAlbums()
        assertEquals(VaultCollectionMode.ALBUMS, vm.state.value.collectionMode)
        vm.showSearch()
        vm.setSearchQuery("  REPORT  ")
        assertTrue(vm.state.value.searchState is VaultSearchState.NoMatches)
        vm.setItemSort(VaultItemSort(VaultItemSort.Field.NAME, VaultItemSort.Direction.DESCENDING))
        assertEquals("  REPORT  ", vm.state.value.searchQuery)
        assertEquals(VaultItemSort(VaultItemSort.Field.NAME, VaultItemSort.Direction.DESCENDING), vm.state.value.itemSort)
        assertEquals(VaultCollectionMode.SEARCH, vm.state.value.collectionMode)
        vm.setSearchQuery("x".repeat(10_000))
        assertEquals(VaultItemSearch.MAX_QUERY_CODE_POINTS * 2 + 1, vm.state.value.searchQuery.length)
        assertEquals(VaultSearchState.QueryTooLong, vm.state.value.searchState)
        vm.setSearchQuery("")
        index.result = VaultIndexRead.Corrupt
        vm.refresh()
        advanceUntilIdle()
        assertEquals(VaultSearchState.IndexUnreadable, vm.state.value.searchState)
        store.clear()
    }

    @Test fun sessionExpiryBlocksAlbumMutationAndDoesNotShowSuccess() = runTest(dispatcher) {
        val session = TestSession().apply { state.value = authenticated() }
        val org = TestOrganization()
        val store = ViewModelStore()
        val vm = VaultViewModel(TestVault(), TestIndex().apply { result = VaultIndexRead.Ready(1, emptyList()) },
            TestImport(), session, org).also { store.put("vault", it) }
        vm.refresh()
        advanceUntilIdle()
        vm.showSearch()
        vm.setSearchQuery("Private album name")
        session.state.value = SessionState.Unauthenticated
        vm.createAlbum("Private")
        advanceUntilIdle()
        assertTrue(vm.state.value.authenticationRequired)
        assertEquals(0, org.mutationCalls)
        assertTrue(vm.state.value.organizationState == null)
        assertEquals("", vm.state.value.searchQuery)
        assertEquals(VaultCollectionMode.ALL_ITEMS, vm.state.value.collectionMode)
        store.clear()
    }

    @Test fun authorizationCallbackExpiryDoesNotReportAlbumMutationAsChanged() = runTest(dispatcher) {
        val session = TestSession().apply { state.value = authenticated() }
        val org = TestOrganization().apply {
            expireDuringMutation = true
            expireSession = { session.state.value = SessionState.Unauthenticated }
        }
        val store = ViewModelStore()
        val vm = VaultViewModel(TestVault(), TestIndex().apply { result = VaultIndexRead.Ready(1, emptyList()) },
            TestImport(), session, org).also { store.put("vault", it) }
        vm.refresh()
        advanceUntilIdle()
        vm.createAlbum("Private")
        advanceUntilIdle()
        assertTrue(vm.state.value.authenticationRequired)
        assertEquals(1, org.mutationCalls)
        assertTrue(org.checkpointExpired)
        assertTrue(vm.state.value.message == null || !vm.state.value.message!!.contains("updated"))
        store.clear()
    }

    private fun authenticated() = SessionState.Authenticated(
        AuthenticatedSession(AuthenticationSource.PRIMARY, 0, Long.MAX_VALUE),
    )

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
        override suspend fun authenticatePrimary(authenticate: suspend () -> AuthenticationResult) =
            SessionAuthenticationCompletion(authenticate(), false)
        override suspend fun authenticateBiometric(authenticate: suspend () -> BiometricAuthenticationResult) =
            SessionAuthenticationCompletion(authenticate(), false)
    }

    private class TestIndex : VaultIndexRepository {
        var result: VaultIndexRead = VaultIndexRead.Missing
        override suspend fun inspect(vaultId: VaultId) = result
        override suspend fun listItems(vaultId: VaultId) = result
        override suspend fun initializeEmpty(vaultId: VaultId, authorizationCheckpoint: suspend () -> Boolean) =
            VaultIndexInitializationResult.Unavailable
        override suspend fun addItem(vaultId: VaultId, item: VaultItem, authorizationCheckpoint: suspend () -> Boolean) =
            VaultIndexWriteResult.Failed
    }

    private class TestImport : VaultImportRepository {
        override fun discard(sourceId: VaultSourceSelectionId) = Unit
        override suspend fun importDocument(
            sourceId: VaultSourceSelectionId, vaultId: VaultId,
            authorizationCheckpoint: suspend () -> Boolean, onProgress: (Long, Long?) -> Unit,
        ) = VaultImportResult.Failed
    }

    private class TestOrganization : VaultOrganizationRepository {
        var mutationCalls = 0
        var expireDuringMutation = false
        var expireSession: (() -> Unit)? = null
        var checkpointExpired = false
        private val empty = VaultOrganizationSnapshot(1, emptyList())
        override suspend fun inspect(vaultId: VaultId) = VaultOrganizationRead.Ready(VaultOrganizationSnapshot(0, emptyList()))
        override suspend fun createAlbum(vaultId: VaultId, name: String, authorizationCheckpoint: suspend () -> Boolean): VaultAlbumMutationResult {
            mutationCalls++
            if (expireDuringMutation) {
                expireSession?.invoke()
                checkpointExpired = !authorizationCheckpoint()
                return VaultAlbumMutationResult.AuthorizationExpired
            }
            return VaultAlbumMutationResult.Changed(empty)
        }
        override suspend fun renameAlbum(vaultId: VaultId, albumId: VaultAlbumId, name: String,
            authorizationCheckpoint: suspend () -> Boolean) = unsupported()
        override suspend fun deleteAlbum(vaultId: VaultId, albumId: VaultAlbumId,
            authorizationCheckpoint: suspend () -> Boolean) = unsupported()
        override suspend fun addMembership(vaultId: VaultId, albumId: VaultAlbumId, itemId: VaultItemId,
            authorizationCheckpoint: suspend () -> Boolean) = unsupported()
        override suspend fun removeMembership(vaultId: VaultId, albumId: VaultAlbumId, itemId: VaultItemId,
            authorizationCheckpoint: suspend () -> Boolean) = unsupported()
        private fun unsupported() = VaultAlbumMutationResult.WriteFailed
    }
}
