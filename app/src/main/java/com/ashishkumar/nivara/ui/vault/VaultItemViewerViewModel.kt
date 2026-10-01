package com.ashishkumar.nivara.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ashishkumar.nivara.domain.security.session.AuthenticatedSession
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.content.VaultContentClassification
import com.ashishkumar.nivara.domain.vault.content.VaultContentPresentationGateway
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemOpenState
import com.ashishkumar.nivara.domain.vault.content.VaultPresentationHandle
import com.ashishkumar.nivara.domain.vault.content.VaultPresentationOpenResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Viewer state contains only typed status, preview kind, and an opaque token—never content or platform resources. */
data class VaultItemViewerUiState(
    val openState: VaultItemOpenState = VaultItemOpenState.Idle,
    val handle: VaultPresentationHandle? = null,
    val previewKind: VaultContentClassification.PreviewKind? = null,
)

class VaultItemViewerViewModel(
    private val vaultId: VaultId,
    item: VaultItem,
    private val gateway: VaultContentPresentationGateway,
    private val sessions: SessionManager,
) : ViewModel() {
    private val itemId = item.id
    private val mutableState = MutableStateFlow(VaultItemViewerUiState())
    val state: StateFlow<VaultItemViewerUiState> = mutableState.asStateFlow()
    private var observedSession: AuthenticatedSession? = null

    init {
        viewModelScope.launch {
            sessions.sessionState.collect { state ->
                val current = (state as? SessionState.Authenticated)?.session
                val previous = observedSession
                observedSession = current
                if (current == null || (previous != null && previous != current)) lockAndClose()
            }
        }
    }

    private var openingJob: Job? = null

    fun open() {
        if (mutableState.value.openState !is VaultItemOpenState.Idle &&
            mutableState.value.openState !is VaultItemOpenState.Closed &&
            mutableState.value.openState !is VaultItemOpenState.Locked
        ) return
        openingJob?.cancel()
        openingJob = viewModelScope.launch {
            var pendingHandle: VaultPresentationHandle? = null
            try {
                mutableState.value = VaultItemViewerUiState(openState = VaultItemOpenState.Authorizing)
                if (!authorized()) {
                    mutableState.value = VaultItemViewerUiState(openState = VaultItemOpenState.Locked)
                    return@launch
                }
                mutableState.value = VaultItemViewerUiState(openState = VaultItemOpenState.Decrypting)
                val result = try {
                    gateway.open(vaultId, itemId) { authorized() }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    VaultPresentationOpenResult.Failed
                }
                if (result is VaultPresentationOpenResult.Ready) pendingHandle = result.handle
                currentCoroutineContext().ensureActive()
                if (!authorized()) {
                    mutableState.value = VaultItemViewerUiState(openState = VaultItemOpenState.Locked)
                    return@launch
                }
                mutableState.value = when (result) {
                    is VaultPresentationOpenResult.Ready -> VaultItemViewerUiState(
                        openState = VaultItemOpenState.Rendering,
                        handle = result.handle,
                        previewKind = result.previewKind,
                    )
                    VaultPresentationOpenResult.Unsupported -> VaultItemViewerUiState(openState = VaultItemOpenState.Unsupported)
                    VaultPresentationOpenResult.Missing -> VaultItemViewerUiState(openState = VaultItemOpenState.Missing)
                    VaultPresentationOpenResult.Unreadable -> VaultItemViewerUiState(openState = VaultItemOpenState.Unreadable)
                    VaultPresentationOpenResult.AuthenticationFailed -> VaultItemViewerUiState(openState = VaultItemOpenState.AuthenticationFailed)
                    VaultPresentationOpenResult.UnsupportedVersion -> VaultItemViewerUiState(openState = VaultItemOpenState.UnsupportedVersion)
                    VaultPresentationOpenResult.AuthorizationExpired -> VaultItemViewerUiState(openState = VaultItemOpenState.Locked)
                    VaultPresentationOpenResult.CorruptFormat -> VaultItemViewerUiState(openState = VaultItemOpenState.CorruptFormat)
                    VaultPresentationOpenResult.Failed -> VaultItemViewerUiState(openState = VaultItemOpenState.Failed)
                }
                if (result is VaultPresentationOpenResult.Ready) pendingHandle = null
            } finally {
                pendingHandle?.let(gateway::close)
                if (openingJob == currentCoroutineContext()[Job]) openingJob = null
            }
        }
    }

    fun close() {
        openingJob?.cancel()
        openingJob = null
        val handle = mutableState.value.handle
        if (handle != null) gateway.close(handle)
        mutableState.value = VaultItemViewerUiState(openState = VaultItemOpenState.Closed)
    }

    fun onActivityPaused() = close()

    private suspend fun authorized(): Boolean = try {
        sessions.currentState() is SessionState.Authenticated && sessions.mayAccessSensitiveContent()
    } catch (failure: CancellationException) { throw failure } catch (_: Exception) { false }

    private fun lockAndClose() {
        openingJob?.cancel()
        openingJob = null
        mutableState.value.handle?.let(gateway::close)
        mutableState.value = VaultItemViewerUiState(openState = VaultItemOpenState.Locked)
    }

    override fun onCleared() {
        mutableState.value.handle?.let(gateway::close)
        super.onCleared()
    }

    class Factory(
        private val vaultId: VaultId,
        private val item: VaultItem,
        private val gateway: VaultContentPresentationGateway,
        private val sessions: SessionManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(VaultItemViewerViewModel::class.java))
            return VaultItemViewerViewModel(vaultId, item, gateway, sessions) as T
        }
    }
}
