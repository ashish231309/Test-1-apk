package com.ashishkumar.nivara.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.domain.vault.VaultInitializationResult
import com.ashishkumar.nivara.domain.vault.VaultRepository
import com.ashishkumar.nivara.domain.vault.VaultRootSelectionResult
import com.ashishkumar.nivara.domain.vault.VaultStatus
import com.ashishkumar.nivara.domain.vault.content.VaultImportRepository
import com.ashishkumar.nivara.domain.vault.content.VaultImportResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexInitializationResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRead
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRepository
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumId
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumMutationResult
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultItemSort
import com.ashishkumar.nivara.domain.vault.content.VaultItemSearch
import com.ashishkumar.nivara.domain.vault.content.VaultSearchState
import com.ashishkumar.nivara.domain.vault.content.VaultSourceSelectionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state contains no Uri, path, credential, content bytes, or key material. */
enum class VaultCollectionMode { ALL_ITEMS, ALBUMS, SEARCH }

data class VaultUiState(
    val status: VaultStatus? = null,
    val checking: Boolean = true,
    val initializing: Boolean = false,
    val indexState: VaultIndexRead? = null,
    val organizationState: VaultOrganizationRead? = null,
    val collectionMode: VaultCollectionMode = VaultCollectionMode.ALL_ITEMS,
    val activeAlbumId: VaultAlbumId? = null,
    val searchQuery: String = "",
    val itemSort: VaultItemSort = VaultItemSort(),
    val indexInitializing: Boolean = false,
    val importing: Boolean = false,
    val organizationMutationBusy: Boolean = false,
    val progressBytes: Long = 0,
    val progressTotalBytes: Long? = null,
    val authenticationRequired: Boolean = false,
    val message: String? = null,
) {
    /** Null only before an index state has been inspected; failures are never mapped to no matches. */
    val searchState: VaultSearchState? get() = indexState?.let { VaultItemSearch.search(it, searchQuery) }
}

class VaultViewModel(
    private val repository: VaultRepository,
    private val indexRepository: VaultIndexRepository,
    private val importRepository: VaultImportRepository,
    private val sessionManager: SessionManager,
    private val organizationRepository: VaultOrganizationRepository? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = mutableState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val previous = mutableState.value
            val message = previous.message
            mutableState.value = VaultUiState(
                checking = true,
                collectionMode = previous.collectionMode,
                activeAlbumId = previous.activeAlbumId,
                searchQuery = previous.searchQuery,
                itemSort = previous.itemSort,
                message = message,
            )
            if (!hasValidSession()) {
                mutableState.value = VaultUiState(checking = false, authenticationRequired = true)
                return@launch
            }
            try {
                val status = repository.inspect()
                mutableState.value = VaultUiState(
                    status = status,
                    checking = false,
                    collectionMode = previous.collectionMode,
                    activeAlbumId = previous.activeAlbumId,
                    searchQuery = previous.searchQuery,
                    itemSort = previous.itemSort,
                    message = message,
                )
                if (status is VaultStatus.Ready) {
                    val indexState = indexRepository.inspect(status.vaultId)
                    val organizationState = organizationRepository?.inspect(status.vaultId)
                        ?: VaultOrganizationRead.Unavailable
                    if (!hasValidSession()) {
                        mutableState.value = VaultUiState(checking = false, authenticationRequired = true)
                    } else {
                        mutableState.value = mutableState.value.copy(
                            indexState = indexState,
                            organizationState = organizationState,
                        )
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                mutableState.value = VaultUiState(
                    status = VaultStatus.Unavailable(com.ashishkumar.nivara.domain.vault.VaultUnavailableReason.EXTERNAL_STORAGE),
                    checking = false,
                    message = "Vault state could not be read. No empty-vault state was assumed.",
                )
            }
        }
    }

    fun initialize() {
        if (mutableState.value.initializing) return
        viewModelScope.launch {
            if (!hasValidSession()) {
                mutableState.value = VaultUiState(checking = false, authenticationRequired = true)
                return@launch
            }
            mutableState.value = mutableState.value.copy(initializing = true, message = null)
            try {
                when (val result = repository.initialize()) {
                    is VaultInitializationResult.Initialized,
                    VaultInitializationResult.AlreadyInitialized -> refresh()
                    VaultInitializationResult.RootNotSelected -> updateStatus(VaultStatus.RootNotSelected)
                    VaultInitializationResult.AccessDenied -> updateStatus(VaultStatus.AccessDenied)
                    VaultInitializationResult.CorruptMetadata -> updateStatus(VaultStatus.CorruptMetadata)
                    is VaultInitializationResult.UnsupportedVersion -> updateStatus(
                        VaultStatus.UnsupportedVersion(result.component, result.version),
                    )
                    VaultInitializationResult.InvalidStructure -> updateStatus(VaultStatus.InvalidStructure)
                    is VaultInitializationResult.Unavailable -> updateStatus(VaultStatus.Unavailable(result.reason))
                    is VaultInitializationResult.Failed -> updateStatus(VaultStatus.InitializationFailed(result.reason))
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                updateStatus(VaultStatus.InitializationFailed(
                    com.ashishkumar.nivara.domain.vault.VaultInitializationFailure.POST_WRITE_VERIFICATION,
                ))
            }
        }
    }

    fun initializeIndex() {
        if (mutableState.value.indexInitializing || mutableState.value.status !is VaultStatus.Ready) return
        val vaultId = (mutableState.value.status as VaultStatus.Ready).vaultId
        viewModelScope.launch {
            if (!hasValidSession()) {
                mutableState.value = VaultUiState(checking = false, authenticationRequired = true)
                return@launch
            }
            mutableState.value = mutableState.value.copy(indexInitializing = true, message = null)
            try {
                val result = indexRepository.initializeEmpty(vaultId) { hasValidSession() }
                if (!hasValidSession()) {
                    mutableState.value = VaultUiState(checking = false, authenticationRequired = true)
                    return@launch
                }
                when (result) {
                    VaultIndexInitializationResult.Initialized,
                    VaultIndexInitializationResult.AlreadyInitialized -> {
                        val indexState = indexRepository.inspect(vaultId)
                        mutableState.value = mutableState.value.copy(indexState = indexState, indexInitializing = false)
                    }
                    VaultIndexInitializationResult.AccessDenied -> indexMessage("Android denied write access to the vault index.")
                    VaultIndexInitializationResult.Unavailable -> indexMessage("The vault index storage is unavailable.")
                    VaultIndexInitializationResult.ObjectsAlreadyPresent -> indexMessage(
                        "Encrypted objects exist without an authenticated index. They were left untouched; no index was reconstructed.",
                    )
                    VaultIndexInitializationResult.Corrupt -> indexMessage("The index directory is malformed; it was not reset.")
                    VaultIndexInitializationResult.UnsupportedVersion -> indexMessage("The existing index format is unsupported.")
                    is VaultIndexInitializationResult.VaultUnavailable -> indexMessage("The selected vault key or metadata is unavailable.")
                    VaultIndexInitializationResult.WriteFailed -> indexMessage("The empty authenticated index could not be committed.")
                    VaultIndexInitializationResult.AuthorizationExpired -> mutableState.value =
                        VaultUiState(checking = false, authenticationRequired = true)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                indexMessage("The vault index could not be initialized.")
            }
        }
    }

    fun showAllItems() {
        mutableState.value = mutableState.value.copy(collectionMode = VaultCollectionMode.ALL_ITEMS, activeAlbumId = null)
    }

    fun showAlbums() {
        mutableState.value = mutableState.value.copy(collectionMode = VaultCollectionMode.ALBUMS, activeAlbumId = null)
    }

    fun showSearch() {
        mutableState.value = mutableState.value.copy(collectionMode = VaultCollectionMode.SEARCH, activeAlbumId = null)
    }

    fun openAlbum(albumId: VaultAlbumId) {
        mutableState.value = mutableState.value.copy(collectionMode = VaultCollectionMode.ALBUMS, activeAlbumId = albumId)
    }

    fun setSearchQuery(query: String) {
        // Keep transient UI state bounded while preserving an oversized sentinel for typed validation.
        val maxUtf16Units = VaultItemSearch.MAX_QUERY_CODE_POINTS * 2
        val bounded = if (query.length > maxUtf16Units) query.take(maxUtf16Units + 1) else query
        mutableState.value = mutableState.value.copy(searchQuery = bounded)
    }
    fun setItemSort(sort: VaultItemSort) { mutableState.value = mutableState.value.copy(itemSort = sort) }

    fun createAlbum(name: String) = runOrganizationMutation { repository, vaultId, checkpoint ->
        repository.createAlbum(vaultId, name, checkpoint)
    }

    fun renameAlbum(albumId: VaultAlbumId, name: String) = runOrganizationMutation { repository, vaultId, checkpoint ->
        repository.renameAlbum(vaultId, albumId, name, checkpoint)
    }

    fun deleteAlbum(albumId: VaultAlbumId) = runOrganizationMutation { repository, vaultId, checkpoint ->
        repository.deleteAlbum(vaultId, albumId, checkpoint)
    }

    fun addMembership(albumId: VaultAlbumId, itemId: VaultItemId) = runOrganizationMutation { repository, vaultId, checkpoint ->
        repository.addMembership(vaultId, albumId, itemId, checkpoint)
    }

    fun removeMembership(albumId: VaultAlbumId, itemId: VaultItemId) = runOrganizationMutation { repository, vaultId, checkpoint ->
        repository.removeMembership(vaultId, albumId, itemId, checkpoint)
    }

    private fun runOrganizationMutation(
        action: suspend (VaultOrganizationRepository, com.ashishkumar.nivara.domain.vault.VaultId, suspend () -> Boolean) -> VaultAlbumMutationResult,
    ) {
        if (mutableState.value.organizationMutationBusy) return
        val ready = mutableState.value.status as? VaultStatus.Ready ?: return
        val repository = organizationRepository ?: run {
            mutableState.value = mutableState.value.copy(message = "Album organization storage is unavailable.")
            return
        }
        viewModelScope.launch {
            if (!hasValidSession()) {
                mutableState.value = VaultUiState(checking = false, authenticationRequired = true)
                return@launch
            }
            mutableState.value = mutableState.value.copy(organizationMutationBusy = true, message = null)
            try {
                val result = action(repository, ready.vaultId) { hasValidSession() }
                if (!hasValidSession()) {
                    mutableState.value = VaultUiState(checking = false, authenticationRequired = true)
                    return@launch
                }
                when (result) {
                    is VaultAlbumMutationResult.Changed -> {
                        val deletedActive = result.albumId != null && result.albumId == mutableState.value.activeAlbumId &&
                            result.snapshot.albums.none { it.id == result.albumId }
                        mutableState.value = mutableState.value.copy(
                            organizationState = VaultOrganizationRead.Ready(result.snapshot),
                            activeAlbumId = if (deletedActive) null else mutableState.value.activeAlbumId,
                            organizationMutationBusy = false,
                            message = if (result.oldGenerationsPruned) "Album organization updated and verified."
                                else "Album organization updated and verified; old encrypted generations could not all be pruned.",
                        )
                    }
                    else -> mutableState.value = mutableState.value.copy(
                        organizationMutationBusy = false,
                        message = organizationFailureMessage(result),
                    )
                }
            } catch (failure: CancellationException) {
                mutableState.value = mutableState.value.copy(organizationMutationBusy = false)
                throw failure
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    organizationMutationBusy = false,
                    message = "Album organization could not be changed. The vault items were not modified.",
                )
            }
        }
    }

    private fun organizationFailureMessage(result: VaultAlbumMutationResult): String = when (result) {
        is VaultAlbumMutationResult.Changed -> "Album organization updated."
        is VaultAlbumMutationResult.InvalidName -> result.reason
        VaultAlbumMutationResult.AlbumLimitReached -> "The album limit was reached."
        VaultAlbumMutationResult.MembershipLimitReached -> "The membership limit was reached."
        VaultAlbumMutationResult.AlbumNotFound -> "That album no longer exists."
        VaultAlbumMutationResult.ItemNotFound -> "That item is no longer present in the authenticated index."
        VaultAlbumMutationResult.AlreadyMember -> "This item is already in that album; no duplicate membership was added."
        VaultAlbumMutationResult.NotMember -> "That item is not a member of the selected album."
        VaultAlbumMutationResult.IndexUnavailable -> "The authenticated item index is unavailable; membership was not changed."
        VaultAlbumMutationResult.OrganizationUnavailable -> "Album metadata is unavailable; it was not treated as empty."
        VaultAlbumMutationResult.Corrupt -> "Album metadata is corrupt or failed authentication; it was not reset."
        VaultAlbumMutationResult.UnsupportedVersion -> "This album metadata version is not supported."
        VaultAlbumMutationResult.AccessDenied -> "Android denied access to album metadata."
        VaultAlbumMutationResult.VaultUnavailable -> "The vault key or metadata is unavailable."
        VaultAlbumMutationResult.WriteFailed -> "The album update could not be durably verified; existing records were retained."
        VaultAlbumMutationResult.AuthorizationExpired -> "The existing session expired; the album update was not reported as successful."
    }

    fun onSourceSelectionResult(result: VaultSourceSelectionResult) {
        when (result) {
            is VaultSourceSelectionResult.Selected -> startImport(result)
            VaultSourceSelectionResult.Cancelled -> setMessage("File selection was cancelled; no source file was changed.")
            VaultSourceSelectionResult.Unavailable -> setMessage("The selected source document is unavailable.")
            VaultSourceSelectionResult.AccessDenied -> setMessage("Android did not grant read access to the selected source document.")
        }
    }

    private fun startImport(selection: VaultSourceSelectionResult.Selected) {
        if (mutableState.value.importing) {
            importRepository.discard(selection.sourceId)
            return
        }
        val ready = mutableState.value.status as? VaultStatus.Ready ?: run {
            importRepository.discard(selection.sourceId)
            setMessage("Initialize and inspect the selected vault before importing files.")
            return
        }
        viewModelScope.launch {
            if (!hasValidSession()) {
                importRepository.discard(selection.sourceId)
                mutableState.value = VaultUiState(checking = false, authenticationRequired = true)
                return@launch
            }
            mutableState.value = mutableState.value.copy(importing = true, progressBytes = 0, progressTotalBytes = null, message = null)
            try {
                val result = importRepository.importDocument(
                    sourceId = selection.sourceId,
                    vaultId = ready.vaultId,
                    authorizationCheckpoint = { hasValidSession() },
                    onProgress = { bytes, total ->
                        mutableState.value = mutableState.value.copy(progressBytes = bytes, progressTotalBytes = total)
                    },
                )
                if (!hasValidSession()) {
                    mutableState.value = VaultUiState(checking = false, authenticationRequired = true)
                    return@launch
                }
                mutableState.value = mutableState.value.copy(importing = false)
                when (result) {
                    is VaultImportResult.Imported -> {
                        mutableState.value = mutableState.value.copy(message = "File imported and authenticated in the vault.")
                        mutableState.value = mutableState.value.copy(indexState = indexRepository.inspect(ready.vaultId))
                    }
                    VaultImportResult.SourceUnavailable -> setMessage("The source document became unavailable. No source changes were made.")
                    VaultImportResult.SourceAccessDenied -> setMessage("Read access to the selected source document was denied.")
                    VaultImportResult.SourceMetadataInvalid -> setMessage("The source filename or metadata is invalid; it was not imported.")
                    VaultImportResult.SourceSizeMismatch -> setMessage("The source size changed while reading; the temporary output was not imported.")
                    VaultImportResult.DestinationUnavailable -> setMessage("The external vault storage became unavailable.")
                    VaultImportResult.DestinationAccessDenied -> setMessage("Android denied access to the vault destination.")
                    VaultImportResult.VaultUnavailable -> setMessage("The vault metadata or content key is unavailable.")
                    VaultImportResult.IndexMissing -> setMessage("Initialize the authenticated vault index before importing.")
                    VaultImportResult.IndexUnreadable -> setMessage("The authenticated index is unreadable. No items were assumed absent.")
                    VaultImportResult.IndexUnsupported -> setMessage("The authenticated index version is not supported.")
                    VaultImportResult.IndexWriteFailed -> setMessage("The index update failed. Any finalized encrypted object remains unindexed.")
                    VaultImportResult.DuplicateId -> setMessage("A generated internal item identifier collided; nothing was overwritten.")
                    VaultImportResult.EncryptionFailed -> setMessage("The encrypted object could not be authenticated; it was not indexed.")
                    VaultImportResult.AuthorizationExpired -> {
                        mutableState.value = VaultUiState(checking = false, authenticationRequired = true)
                    }
                    VaultImportResult.Failed -> setMessage("Import failed. No source changes were made; unindexed encrypted output, if finalized, was left untouched.")
                }
            } catch (failure: CancellationException) {
                mutableState.value = mutableState.value.copy(importing = false)
                throw failure
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(importing = false, message = "Import failed without modifying the source document.")
            }
        }
    }

    private suspend fun hasValidSession(): Boolean = try {
        sessionManager.currentState() is SessionState.Authenticated && sessionManager.mayAccessSensitiveContent()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        false
    }

    private fun onRootMessage(result: VaultRootSelectionResult): String = when (result) {
        VaultRootSelectionResult.SELECTED -> "External vault folder selected."
        VaultRootSelectionResult.RECONNECTED -> "The previously selected external vault folder was reconnected."
        VaultRootSelectionResult.CANCELLED -> "Folder selection was cancelled; the current selection was not changed."
        VaultRootSelectionResult.INVALID_SELECTION -> "Choose a writable folder from the Android document picker."
        VaultRootSelectionResult.DIFFERENT_ROOT_ALREADY_SELECTED ->
            "A different folder is already bound to this vault. Reconnect to that exact folder; it was not replaced."
        VaultRootSelectionResult.ACCESS_DENIED -> "Android did not grant persistent read and write access to that folder."
        VaultRootSelectionResult.UNAVAILABLE -> "Android could not access the selected folder."
        VaultRootSelectionResult.PERSISTENCE_FAILURE -> "The folder selection could not be saved; no alternate folder was chosen."
    }

    fun onRootSelectionResult(result: VaultRootSelectionResult) {
        mutableState.value = mutableState.value.copy(message = onRootMessage(result))
        if (result == VaultRootSelectionResult.SELECTED || result == VaultRootSelectionResult.RECONNECTED) refresh()
    }

    private fun indexMessage(message: String) {
        mutableState.value = mutableState.value.copy(indexInitializing = false, message = message)
    }

    private fun setMessage(message: String) { mutableState.value = mutableState.value.copy(message = message) }

    private fun updateStatus(status: VaultStatus) {
        mutableState.value = mutableState.value.copy(status = status, checking = false, initializing = false)
    }

    class Factory(
        private val repository: VaultRepository,
        private val indexRepository: VaultIndexRepository,
        private val importRepository: VaultImportRepository,
        private val sessionManager: SessionManager,
        private val organizationRepository: VaultOrganizationRepository? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(VaultViewModel::class.java))
            return VaultViewModel(repository, indexRepository, importRepository, sessionManager, organizationRepository) as T
        }
    }
}
