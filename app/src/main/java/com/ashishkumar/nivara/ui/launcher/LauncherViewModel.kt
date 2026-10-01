package com.ashishkumar.nivara.ui.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.app.InstalledApplication
import com.ashishkumar.nivara.domain.app.InstalledApplicationOrdering
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationRepository
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationsSnapshot
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LauncherUiState {
    data object Loading : LauncherUiState
    data class Ready(
        val applications: List<InstalledApplication>,
        val hiddenApplicationCount: Int,
        val hiddenApplicationsRevealed: Boolean,
    ) : LauncherUiState
    data object NoApplications : LauncherUiState
    data class EmptyVisibleCatalogue(val hiddenApplicationCount: Int) : LauncherUiState
    data class HiddenStateUnavailable(val unreadable: Boolean) : LauncherUiState
    data object ApplicationDiscoveryUnavailable : LauncherUiState
    data class DiscoveryAndHiddenStateUnavailable(val hiddenStateUnreadable: Boolean) : LauncherUiState
}

enum class RevealRequestResult {
    REVEALED,
    AUTHENTICATION_REQUIRED,
    SESSION_UNAVAILABLE,
    NO_HIDDEN_APPLICATIONS,
    HIDDEN_STATE_UNAVAILABLE,
    APPLICATION_DISCOVERY_UNAVAILABLE,
}

/**
 * Presentation snapshot for Nivara's own launcher. It never owns persisted hidden configuration: every
 * refresh and reveal decision reads both established repositories, and temporary reveal exists only here.
 */
class LauncherViewModel(
    private val applicationRepository: ApplicationRepository,
    private val hiddenApplicationRepository: HiddenApplicationRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow<LauncherUiState>(LauncherUiState.Loading)
    val state: StateFlow<LauncherUiState> = mutableState.asStateFlow()

    private val operationMutex = Mutex()
    private var revealedForSession: SessionState.Authenticated? = null

    init {
        viewModelScope.launch {
            sessionManager.sessionState.collect { observed ->
                if (revealedForSession != null && observed !== revealedForSession) {
                    operationMutex.withLock {
                        revealedForSession = null
                        refreshLocked()
                    }
                }
            }
        }
    }

    suspend fun refresh() = operationMutex.withLock { refreshLocked() }

    /** Explicit reveal only; it never changes the hidden repository. */
    suspend fun revealHiddenApplications(): RevealRequestResult = operationMutex.withLock {
        revealedForSession = null
        mutableState.value = LauncherUiState.Loading
        val snapshot = readRepositories()
        val current = safeCurrentSession()
        if (snapshot.hidden is HiddenApplicationsSnapshot.Unreadable ||
            snapshot.hidden is HiddenApplicationsSnapshot.Unavailable
        ) {
            publish(snapshot, revealSession = null)
            return@withLock RevealRequestResult.HIDDEN_STATE_UNAVAILABLE
        }
        if (snapshot.discovery !is ApplicationDiscoveryResult.Available) {
            publish(snapshot, revealSession = null)
            return@withLock RevealRequestResult.APPLICATION_DISCOVERY_UNAVAILABLE
        }

        val apps = launchable(snapshot.discovery)
        val hiddenSet = (snapshot.hidden as HiddenApplicationsSnapshot.Available).applications
        val hiddenCount = apps.count { app -> hiddenSet.any { it.packageName == app.packageName } }
        if (hiddenCount == 0) {
            publish(snapshot, revealSession = null)
            return@withLock RevealRequestResult.NO_HIDDEN_APPLICATIONS
        }
        if (current == null) {
            publish(snapshot, revealSession = null)
            return@withLock RevealRequestResult.SESSION_UNAVAILABLE
        }
        if (current !is SessionState.Authenticated) {
            publish(snapshot, revealSession = null)
            return@withLock RevealRequestResult.AUTHENTICATION_REQUIRED
        }

        // SessionManager.currentState() is checked after the fresh repository reads, immediately before reveal.
        val finalSession = safeCurrentSession()
        if (finalSession == null) {
            publish(snapshot, revealSession = null)
            return@withLock RevealRequestResult.SESSION_UNAVAILABLE
        }
        if (finalSession !is SessionState.Authenticated) {
            publish(snapshot, revealSession = null)
            return@withLock RevealRequestResult.AUTHENTICATION_REQUIRED
        }

        revealedForSession = finalSession
        publish(snapshot, revealSession = finalSession)
        RevealRequestResult.REVEALED
    }

    suspend fun hideRevealedApplications() = operationMutex.withLock {
        revealedForSession = null
        refreshLocked()
    }

    /** Delegates Quick Lock exclusively to the process-scoped SessionManager. */
    suspend fun quickLock(): Boolean {
        val locked = try {
            sessionManager.lockNow()
            safeCurrentSession() == SessionState.Unauthenticated
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
        operationMutex.withLock {
            revealedForSession = null
            refreshLocked()
        }
        return locked
    }

    private suspend fun refreshLocked() {
        mutableState.value = LauncherUiState.Loading
        val snapshot = readRepositories()
        val current = safeCurrentSession()
        val activeSession = current as? SessionState.Authenticated
        if (activeSession == null || activeSession !== revealedForSession) revealedForSession = null
        publish(snapshot, revealSession = revealedForSession)
    }

    private suspend fun readRepositories(): RepositorySnapshot {
        val discovery = try {
            applicationRepository.discoverLaunchableApplications()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            ApplicationDiscoveryResult.Unavailable
        }
        val hidden = try {
            hiddenApplicationRepository.getHiddenApplications()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            HiddenApplicationsSnapshot.Unavailable
        }
        return RepositorySnapshot(discovery, hidden)
    }

    private suspend fun safeCurrentSession(): SessionState? = try {
        sessionManager.currentState()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }

    private fun publish(snapshot: RepositorySnapshot, revealSession: SessionState.Authenticated?) {
        val revealAuthorized = revealSession != null && sessionManager.sessionState.value === revealSession
        if (revealSession != null && !revealAuthorized) revealedForSession = null
        val hiddenFailure = when (val hidden = snapshot.hidden) {
            HiddenApplicationsSnapshot.Unreadable -> true
            HiddenApplicationsSnapshot.Unavailable -> false
            is HiddenApplicationsSnapshot.Available -> null
        }
        val discoveryAvailable = snapshot.discovery is ApplicationDiscoveryResult.Available
        if (hiddenFailure != null && !discoveryAvailable) {
            mutableState.value = LauncherUiState.DiscoveryAndHiddenStateUnavailable(hiddenFailure)
            return
        }
        if (hiddenFailure != null) {
            mutableState.value = LauncherUiState.HiddenStateUnavailable(hiddenFailure)
            return
        }
        if (!discoveryAvailable) {
            mutableState.value = LauncherUiState.ApplicationDiscoveryUnavailable
            return
        }

        val discovered = launchable(snapshot.discovery as ApplicationDiscoveryResult.Available)
        if (discovered.isEmpty()) {
            mutableState.value = LauncherUiState.NoApplications
            return
        }
        val hiddenApplications = (snapshot.hidden as HiddenApplicationsSnapshot.Available).applications
        val hiddenCount = discovered.count { app -> hiddenApplications.any { it.packageName == app.packageName } }
        val visible = if (revealAuthorized) discovered else discovered.filterNot { app ->
            hiddenApplications.any { it.packageName == app.packageName }
        }
        mutableState.value = if (visible.isEmpty()) {
            LauncherUiState.EmptyVisibleCatalogue(hiddenCount)
        } else {
            LauncherUiState.Ready(
                applications = visible,
                hiddenApplicationCount = hiddenCount,
                hiddenApplicationsRevealed = revealAuthorized && hiddenCount > 0,
            )
        }
    }

    private fun launchable(result: ApplicationDiscoveryResult.Available): List<InstalledApplication> =
        InstalledApplicationOrdering.deterministic(result.applications.filter(InstalledApplication::isLaunchable))

    private data class RepositorySnapshot(
        val discovery: ApplicationDiscoveryResult,
        val hidden: HiddenApplicationsSnapshot,
    )

    class Factory(
        private val applicationRepository: ApplicationRepository,
        private val hiddenApplicationRepository: HiddenApplicationRepository,
        private val sessionManager: SessionManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LauncherViewModel::class.java))
            return LauncherViewModel(applicationRepository, hiddenApplicationRepository, sessionManager) as T
        }
    }
}
