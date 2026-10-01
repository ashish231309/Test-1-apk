package com.ashishkumar.nivara.ui.applock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.app.InstalledApplication
import com.ashishkumar.nivara.domain.app.InstalledApplicationOrdering
import com.ashishkumar.nivara.domain.app.InstalledApplicationSearch
import com.ashishkumar.nivara.domain.applock.AppLockDegradedReason
import com.ashishkumar.nivara.domain.applock.AppLockDetectionState
import com.ashishkumar.nivara.domain.applock.AppLockMonitor
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityRepository
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus
import com.ashishkumar.nivara.domain.applock.ProtectedApplication
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationRepository
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationUpdateResult
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot
import com.ashishkumar.nivara.domain.credentials.CredentialServiceStatus
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** User-selectable sections for the one repository-backed application list. */
enum class AppLockApplicationSection { ALL, PROTECTED }

enum class AppLockApplicationSort { NAME_A_TO_Z, NAME_Z_TO_A }

sealed interface AppLockMonitoringStatus {
    data object Stopped : AppLockMonitoringStatus
    data object Starting : AppLockMonitoringStatus
    data object NoProtectedApplications : AppLockMonitoringStatus
    data object Active : AppLockMonitoringStatus
    data class Degraded(val reason: AppLockDegradedReason) : AppLockMonitoringStatus
}

enum class AppLockManagementNotice {
    CHANGES_SAVED,
    ALREADY_IN_STATE,
    AUTHENTICATION_REQUIRED,
    SESSION_UNAVAILABLE,
    APPLICATION_NO_LONGER_AVAILABLE,
    DISCOVERY_UNAVAILABLE,
    PROTECTED_STATE_UNAVAILABLE,
    UPDATE_FAILED,
}

/** Detailed prerequisites are kept separate; no single flag claims that runtime enforcement is active. */
data class AppLockManagementReadiness(
    val discovery: DiscoveryReadiness,
    val protectedSet: ProtectedSetReadiness,
    val usageAccess: UsageAccessStatus,
    val overlay: OverlayCapabilityStatus,
    val primaryCredential: CredentialServiceStatus,
) {
    enum class DiscoveryReadiness { AVAILABLE, UNAVAILABLE }
    enum class ProtectedSetReadiness { AVAILABLE, UNAVAILABLE }
}

data class AppLockManagementContent(
    val discovery: ApplicationDiscoveryResult,
    val protectedApplications: ProtectedApplicationsSnapshot,
    val readiness: AppLockManagementReadiness,
    val monitoringStatus: AppLockMonitoringStatus,
    val sessionState: SessionState?,
    val query: String,
    val section: AppLockApplicationSection,
    val sort: AppLockApplicationSort,
    val visibleApplications: List<InstalledApplication>,
    /** Package identifiers remain internal to this local UI state and are never shown as row labels. */
    val unavailableProtectedPackageNames: List<String>,
    val pendingPackageNames: Set<String>,
    val notice: AppLockManagementNotice?,
)

sealed interface AppLockManagementUiState {
    data object Loading : AppLockManagementUiState
    data class Ready(val content: AppLockManagementContent) : AppLockManagementUiState
    data class Empty(val content: AppLockManagementContent) : AppLockManagementUiState
    data class NoResults(val content: AppLockManagementContent) : AppLockManagementUiState
    data class Unavailable(val content: AppLockManagementContent) : AppLockManagementUiState
}

/**
 * Coordinates discovery, the one protected-package repository, readiness contracts, and display state.
 * All visible protection state is reloaded from ProtectedApplicationRepository after mutations/refreshes.
 */
class AppLockManagementViewModel(
    private val applicationRepository: ApplicationRepository,
    private val protectedApplicationRepository: ProtectedApplicationRepository,
    private val usageAccessRepository: UsageAccessRepository,
    private val overlayCapabilityRepository: OverlayCapabilityRepository,
    private val primaryCredentialService: PrimaryCredentialService,
    private val sessionManager: SessionManager,
    private val appLockMonitor: AppLockMonitor,
    private val requestMonitoringStart: suspend () -> Unit,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AppLockManagementUiState>(AppLockManagementUiState.Loading)
    val state = mutableState.asStateFlow()

    private val operations = Mutex()
    private var loaded: BaseData? = null
    private var refreshJob: Job? = null
    private var refreshAgainRequested = false

    init {
        refresh()
        viewModelScope.launch {
            sessionManager.sessionState.collect {
                operations.withLock {
                    val current = loaded ?: return@withLock
                    val validated = currentSessionOrNull()
                    loaded = current.copy(sessionState = validated)
                    publish()
                }
            }
        }
        viewModelScope.launch {
            appLockMonitor.state.collect { detection ->
                operations.withLock {
                    val current = loaded ?: return@withLock
                    val status = monitoringStatus(detection)
                    if (current.monitoringStatus != status) {
                        loaded = current.copy(monitoringStatus = status)
                        publish()
                    }
                }
            }
        }
    }

    /** Refreshes once on entry/resume and after every repository mutation; it does not poll. */
    fun refresh() {
        if (refreshJob?.isActive == true) {
            refreshAgainRequested = true
            return
        }
        refreshJob = viewModelScope.launch {
            try {
                do {
                    refreshAgainRequested = false
                    operations.withLock {
                        if (loaded == null) mutableState.value = AppLockManagementUiState.Loading
                        loaded = readSnapshot(loaded, notice = null)
                        publish()
                    }
                } while (refreshAgainRequested)
            } finally {
                refreshJob = null
            }
        }
    }

    fun setQuery(query: String) {
        val current = loaded ?: return
        loaded = current.copy(query = query, notice = null)
        publish()
    }

    fun setSection(section: AppLockApplicationSection) {
        val current = loaded ?: return
        loaded = current.copy(section = section)
        publish()
    }

    fun setSort(sort: AppLockApplicationSort) {
        val current = loaded ?: return
        loaded = current.copy(sort = sort)
        publish()
    }

    fun setProtection(application: InstalledApplication, protect: Boolean) {
        launchMutation(application.packageName, protect)
    }

    /** Stale protected entries remain in the authoritative set until the user explicitly removes them. */
    fun unprotectUnavailable(packageName: String) {
        launchMutation(packageName, protect = false)
    }

    private fun launchMutation(packageName: String, protect: Boolean) {
        if (packageName.isBlank()) return
        viewModelScope.launch {
            operations.withLock {
                val before = loaded ?: return@withLock
                if (packageName in before.pendingPackageNames) return@withLock
                loaded = before.copy(pendingPackageNames = before.pendingPackageNames + packageName, notice = null)
                publish()
                try {
                    val liveSession = currentSessionOrNull()
                    val current = loaded ?: return@withLock
                    loaded = current.copy(sessionState = liveSession)
                    if (liveSession !is SessionState.Authenticated) {
                        loaded = loaded?.copy(
                            notice = if (liveSession == null) AppLockManagementNotice.SESSION_UNAVAILABLE
                            else AppLockManagementNotice.AUTHENTICATION_REQUIRED,
                        )
                        return@withLock
                    }
                    if (current.discovery !is ApplicationDiscoveryResult.Available) {
                        loaded = loaded?.copy(notice = AppLockManagementNotice.DISCOVERY_UNAVAILABLE)
                        return@withLock
                    }
                    if (current.protectedApplications !is ProtectedApplicationsSnapshot.Available) {
                        loaded = loaded?.copy(notice = AppLockManagementNotice.PROTECTED_STATE_UNAVAILABLE)
                        return@withLock
                    }

                    val freshDiscovery = if (protect) safeDiscovery() else current.discovery
                    if (freshDiscovery !is ApplicationDiscoveryResult.Available) {
                        loaded = loaded?.copy(
                            discovery = ApplicationDiscoveryResult.Unavailable,
                            readiness = current.readiness.copy(discovery = AppLockManagementReadiness.DiscoveryReadiness.UNAVAILABLE),
                            notice = AppLockManagementNotice.DISCOVERY_UNAVAILABLE,
                        )
                        return@withLock
                    }
                    if (protect && freshDiscovery.applications.none {
                            it.packageName == packageName && it.isLaunchable
                        }
                    ) {
                        loaded = current.copy(
                            discovery = freshDiscovery,
                            notice = AppLockManagementNotice.APPLICATION_NO_LONGER_AVAILABLE,
                        )
                        return@withLock
                    }

                    val freshProtected = safeProtectedSnapshot()
                    if (freshProtected !is ProtectedApplicationsSnapshot.Available) {
                        loaded = loaded?.copy(
                            protectedApplications = ProtectedApplicationsSnapshot.Unavailable,
                            readiness = current.readiness.copy(
                                protectedSet = AppLockManagementReadiness.ProtectedSetReadiness.UNAVAILABLE,
                            ),
                            notice = AppLockManagementNotice.PROTECTED_STATE_UNAVAILABLE,
                        )
                        return@withLock
                    }
                    val model = try {
                        ProtectedApplication(packageName)
                    } catch (_: IllegalArgumentException) {
                        loaded = current.copy(notice = AppLockManagementNotice.APPLICATION_NO_LONGER_AVAILABLE)
                        return@withLock
                    }
                    val alreadyInState = (model in freshProtected.applications) == protect
                    if (alreadyInState) {
                        loaded = current.copy(
                            discovery = freshDiscovery,
                            protectedApplications = freshProtected,
                            notice = AppLockManagementNotice.ALREADY_IN_STATE,
                        )
                        return@withLock
                    }

                    val result = try {
                        if (protect) {
                            protectedApplicationRepository.protect(model)
                        } else {
                            protectedApplicationRepository.unprotect(model)
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        val refreshedProtected = safeProtectedSnapshot()
                        val latest = loaded ?: current
                        loaded = latest.copy(
                            protectedApplications = refreshedProtected,
                            readiness = latest.readiness.copy(
                                protectedSet = if (refreshedProtected is ProtectedApplicationsSnapshot.Available) {
                                    AppLockManagementReadiness.ProtectedSetReadiness.AVAILABLE
                                } else AppLockManagementReadiness.ProtectedSetReadiness.UNAVAILABLE,
                            ),
                            notice = AppLockManagementNotice.UPDATE_FAILED,
                        )
                        return@withLock
                    }
                    val notice = if (result == ProtectedApplicationUpdateResult.UPDATED) {
                        AppLockManagementNotice.CHANGES_SAVED
                    } else {
                        AppLockManagementNotice.UPDATE_FAILED
                    }
                    loaded = readSnapshot(loaded, notice)
                    val saved = loaded?.protectedApplications as? ProtectedApplicationsSnapshot.Available
                    if (result == ProtectedApplicationUpdateResult.UPDATED &&
                        saved?.applications?.isNotEmpty() == true &&
                        appLockMonitor.state.value in setOf(
                            AppLockDetectionState.Stopped,
                            AppLockDetectionState.NoProtectedApplications,
                        )
                    ) {
                        try {
                            requestMonitoringStart()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            // The readiness/status surface remains authoritative; monitor startup is retried on resume.
                        }
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    val current = loaded
                    if (current != null) {
                        loaded = current.copy(
                            protectedApplications = ProtectedApplicationsSnapshot.Unavailable,
                            readiness = current.readiness.copy(
                                protectedSet = AppLockManagementReadiness.ProtectedSetReadiness.UNAVAILABLE,
                            ),
                            notice = AppLockManagementNotice.UPDATE_FAILED,
                        )
                    }
                } finally {
                    loaded = loaded?.let { current ->
                        current.copy(pendingPackageNames = current.pendingPackageNames - packageName)
                    }
                    publish()
                }
            }
        }
    }

    private suspend fun readSnapshot(previous: BaseData?, notice: AppLockManagementNotice?): BaseData {
        // Platform/data adapters move expensive work off the main thread; the ViewModel preserves call ordering.
        val discovery = safeDiscovery()
        val protected = safeProtectedSnapshot()
        val usage = safeUsageStatus()
        val overlay = safeOverlayStatus()
        val credential = safeCredentialStatus()
        val session = currentSessionOrNull()
        val latest = loaded ?: previous
        val readiness = AppLockManagementReadiness(
            discovery = if (discovery is ApplicationDiscoveryResult.Available) {
                AppLockManagementReadiness.DiscoveryReadiness.AVAILABLE
            } else AppLockManagementReadiness.DiscoveryReadiness.UNAVAILABLE,
            protectedSet = if (protected is ProtectedApplicationsSnapshot.Available) {
                AppLockManagementReadiness.ProtectedSetReadiness.AVAILABLE
            } else AppLockManagementReadiness.ProtectedSetReadiness.UNAVAILABLE,
            usageAccess = usage,
            overlay = overlay,
            primaryCredential = credential,
        )
        return BaseData(
            discovery = discovery,
            protectedApplications = protected,
            readiness = readiness,
            monitoringStatus = monitoringStatus(appLockMonitor.state.value),
            sessionState = session,
            query = latest?.query.orEmpty(),
            section = latest?.section ?: AppLockApplicationSection.ALL,
            sort = latest?.sort ?: AppLockApplicationSort.NAME_A_TO_Z,
            pendingPackageNames = latest?.pendingPackageNames.orEmpty(),
            notice = notice,
        )
    }

    private suspend fun safeDiscovery(): ApplicationDiscoveryResult = try {
        when (val result = applicationRepository.discoverLaunchableApplications()) {
            is ApplicationDiscoveryResult.Available -> ApplicationDiscoveryResult.Available(
                InstalledApplicationOrdering.deterministic(
                    result.applications.filter(InstalledApplication::isLaunchable).distinctBy(InstalledApplication::packageName),
                ),
            )
            ApplicationDiscoveryResult.Unavailable -> ApplicationDiscoveryResult.Unavailable
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        ApplicationDiscoveryResult.Unavailable
    }

    private suspend fun safeProtectedSnapshot(): ProtectedApplicationsSnapshot = try {
        when (val result = protectedApplicationRepository.getProtectedApplications()) {
            is ProtectedApplicationsSnapshot.Available -> ProtectedApplicationsSnapshot.Available(result.applications.toSet())
            ProtectedApplicationsSnapshot.Unavailable -> ProtectedApplicationsSnapshot.Unavailable
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        ProtectedApplicationsSnapshot.Unavailable
    }

    private suspend fun safeUsageStatus(): UsageAccessStatus = try {
        usageAccessRepository.status()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        UsageAccessStatus.UNAVAILABLE
    }

    private fun safeOverlayStatus(): OverlayCapabilityStatus = try {
        overlayCapabilityRepository.status()
    } catch (_: RuntimeException) {
        OverlayCapabilityStatus.UNAVAILABLE
    }

    private suspend fun safeCredentialStatus(): CredentialServiceStatus = try {
        primaryCredentialService.status()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        CredentialServiceStatus.InvalidConfiguration
    }

    private suspend fun currentSessionOrNull(): SessionState? = try {
        sessionManager.currentState()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }

    private fun monitoringStatus(state: AppLockDetectionState): AppLockMonitoringStatus = when (state) {
        AppLockDetectionState.Stopped -> AppLockMonitoringStatus.Stopped
        AppLockDetectionState.Starting -> AppLockMonitoringStatus.Starting
        AppLockDetectionState.NoProtectedApplications -> AppLockMonitoringStatus.NoProtectedApplications
        is AppLockDetectionState.Monitoring -> AppLockMonitoringStatus.Active
        is AppLockDetectionState.Degraded -> AppLockMonitoringStatus.Degraded(state.reason)
    }

    private fun publish() {
        val current = loaded ?: return
        val protectedSnapshot = current.protectedApplications as? ProtectedApplicationsSnapshot.Available
        val protectedPackages = protectedSnapshot?.applications.orEmpty().mapTo(HashSet()) { it.packageName }
        val discovered = current.discovery as? ApplicationDiscoveryResult.Available
        val allApplications = discovered?.applications.orEmpty()
        val filtered = allApplications.asSequence()
            .filter { current.section == AppLockApplicationSection.ALL || it.packageName in protectedPackages }
            .filter { InstalledApplicationSearch.matches(it, current.query) }
            .toList()
        val sorted = when (current.sort) {
            AppLockApplicationSort.NAME_A_TO_Z -> InstalledApplicationOrdering.deterministic(filtered)
            AppLockApplicationSort.NAME_Z_TO_A -> InstalledApplicationOrdering.reverseAlphabetical(filtered)
        }
        val discoveredPackages = allApplications.mapTo(HashSet(), InstalledApplication::packageName)
        val staleProtected = if (discovered != null && protectedSnapshot != null) {
            protectedPackages.asSequence()
                .filterNot { it in discoveredPackages }
                .sortedWith(compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it })
                .toList()
        } else emptyList()
        val content = AppLockManagementContent(
            discovery = current.discovery,
            protectedApplications = current.protectedApplications,
            readiness = current.readiness,
            monitoringStatus = current.monitoringStatus,
            sessionState = current.sessionState,
            query = current.query,
            section = current.section,
            sort = current.sort,
            visibleApplications = sorted,
            unavailableProtectedPackageNames = staleProtected,
            pendingPackageNames = current.pendingPackageNames,
            notice = current.notice,
        )
        mutableState.value = when {
            current.discovery == ApplicationDiscoveryResult.Unavailable ||
                current.protectedApplications == ProtectedApplicationsSnapshot.Unavailable ->
                AppLockManagementUiState.Unavailable(content)
            allApplications.isEmpty() -> AppLockManagementUiState.Empty(content)
            current.query.isNotBlank() && sorted.isEmpty() &&
                !(current.section == AppLockApplicationSection.PROTECTED && staleProtected.isNotEmpty()) ->
                AppLockManagementUiState.NoResults(content)
            else -> AppLockManagementUiState.Ready(content)
        }
    }

    private data class BaseData(
        val discovery: ApplicationDiscoveryResult,
        val protectedApplications: ProtectedApplicationsSnapshot,
        val readiness: AppLockManagementReadiness,
        val monitoringStatus: AppLockMonitoringStatus,
        val sessionState: SessionState?,
        val query: String,
        val section: AppLockApplicationSection,
        val sort: AppLockApplicationSort,
        val pendingPackageNames: Set<String>,
        val notice: AppLockManagementNotice?,
    )

    class Factory(
        private val applicationRepository: ApplicationRepository,
        private val protectedApplicationRepository: ProtectedApplicationRepository,
        private val usageAccessRepository: UsageAccessRepository,
        private val overlayCapabilityRepository: OverlayCapabilityRepository,
        private val primaryCredentialService: PrimaryCredentialService,
        private val sessionManager: SessionManager,
        private val appLockMonitor: AppLockMonitor,
        private val requestMonitoringStart: suspend () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppLockManagementViewModel::class.java))
            return AppLockManagementViewModel(
                applicationRepository,
                protectedApplicationRepository,
                usageAccessRepository,
                overlayCapabilityRepository,
                primaryCredentialService,
                sessionManager,
                appLockMonitor,
                requestMonitoringStart,
            ) as T
        }
    }
}
