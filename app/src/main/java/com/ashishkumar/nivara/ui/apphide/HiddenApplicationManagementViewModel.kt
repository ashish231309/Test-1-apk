package com.ashishkumar.nivara.ui.apphide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.app.InstalledApplication
import com.ashishkumar.nivara.domain.app.InstalledApplicationOrdering
import com.ashishkumar.nivara.domain.app.InstalledApplicationSearch
import com.ashishkumar.nivara.domain.apphide.HiddenApplication
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationRepository
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationUpdateResult
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationsSnapshot
import com.ashishkumar.nivara.domain.applock.ProtectedApplication
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationRepository
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot
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

/** The catalogue view is independent of App Lock's protected membership. */
enum class HiddenApplicationSection { ALL, HIDDEN }
enum class HiddenApplicationSort { NAME_A_TO_Z, NAME_Z_TO_A }

enum class HiddenManagementNotice {
    CHANGES_SAVED,
    ALREADY_IN_STATE,
    AUTHENTICATION_REQUIRED,
    SESSION_UNAVAILABLE,
    DISCOVERY_UNAVAILABLE,
    APPLICATION_NO_LONGER_AVAILABLE,
    HIDDEN_STATE_UNREADABLE,
    HIDDEN_STATE_UNAVAILABLE,
    UPDATE_FAILED,
}

data class ManagedApplicationState(
    val application: InstalledApplication,
    /** Null means the hidden repository could not be read; it never means visible. */
    val hidden: Boolean?,
    /** Null means App Lock state is unavailable; it does not affect hiding controls. */
    val protected: Boolean?,
)

data class HiddenApplicationManagementContent(
    val discovery: ApplicationDiscoveryResult,
    /** Last successful launcher snapshot, retained for display if a later refresh fails. */
    val applications: List<InstalledApplication>,
    val hiddenApplications: HiddenApplicationsSnapshot,
    val protectedApplications: ProtectedApplicationsSnapshot,
    val sessionState: SessionState?,
    val query: String,
    val section: HiddenApplicationSection,
    val sort: HiddenApplicationSort,
    val visibleApplications: List<ManagedApplicationState>,
    val staleHiddenPackageNames: List<String>,
    val pendingPackageNames: Set<String>,
    val notice: HiddenManagementNotice?,
)

sealed interface HiddenApplicationManagementState {
    data object Loading : HiddenApplicationManagementState
    data class Ready(val content: HiddenApplicationManagementContent) : HiddenApplicationManagementState
    data class Empty(val content: HiddenApplicationManagementContent) : HiddenApplicationManagementState
    data class NoResults(val content: HiddenApplicationManagementContent) : HiddenApplicationManagementState
    data class Unavailable(val content: HiddenApplicationManagementContent) : HiddenApplicationManagementState
}

/** Repository-backed management coordinator; only HiddenApplicationRepository owns hidden truth. */
class HiddenApplicationManagementViewModel(
    private val applicationRepository: ApplicationRepository,
    private val hiddenApplicationRepository: HiddenApplicationRepository,
    private val protectedApplicationRepository: ProtectedApplicationRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow<HiddenApplicationManagementState>(HiddenApplicationManagementState.Loading)
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
                    loaded = current.copy(sessionState = safeSessionState())
                    publish()
                }
            }
        }
    }

    /** Entry/resume refresh; no package-manager polling or independent UsageEvents loop is introduced. */
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
                        if (loaded == null) mutableState.value = HiddenApplicationManagementState.Loading
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
        loaded = loaded?.copy(query = query, notice = null)
        publish()
    }

    fun setSection(section: HiddenApplicationSection) {
        loaded = loaded?.copy(section = section)
        publish()
    }

    fun setSort(sort: HiddenApplicationSort) {
        loaded = loaded?.copy(sort = sort)
        publish()
    }

    fun hide(application: InstalledApplication) = launchMutation(application.packageName, hide = true, staleEntry = false)

    fun unhide(application: InstalledApplication) = launchMutation(application.packageName, hide = false, staleEntry = false)

    /** Explicitly removes only a saved preference for an app absent from a successful current catalogue. */
    fun removeStaleHiddenPreference(packageName: String) = launchMutation(
        packageName = packageName,
        hide = false,
        staleEntry = true,
    )

    private fun launchMutation(packageName: String, hide: Boolean, staleEntry: Boolean) {
        if (packageName.isBlank()) return
        viewModelScope.launch {
            operations.withLock {
                val before = loaded ?: return@withLock
                if (packageName in before.pendingPackageNames) return@withLock
                loaded = before.copy(pendingPackageNames = before.pendingPackageNames + packageName, notice = null)
                publish()
                try {
                    val current = loaded ?: return@withLock
                    val session = safeSessionState()
                    loaded = current.copy(sessionState = session)
                    if (session !is SessionState.Authenticated) {
                        loaded = loaded?.copy(
                            notice = if (session == null) HiddenManagementNotice.SESSION_UNAVAILABLE
                            else HiddenManagementNotice.AUTHENTICATION_REQUIRED,
                        )
                        return@withLock
                    }
                    if (current.discovery !is ApplicationDiscoveryResult.Available) {
                        loaded = loaded?.copy(notice = HiddenManagementNotice.DISCOVERY_UNAVAILABLE)
                        return@withLock
                    }
                    if (current.hiddenApplications !is HiddenApplicationsSnapshot.Available) {
                        loaded = loaded?.copy(
                            notice = when (current.hiddenApplications) {
                                HiddenApplicationsSnapshot.Unreadable -> HiddenManagementNotice.HIDDEN_STATE_UNREADABLE
                                HiddenApplicationsSnapshot.Unavailable -> HiddenManagementNotice.HIDDEN_STATE_UNAVAILABLE
                                is HiddenApplicationsSnapshot.Available -> error("unreachable")
                            },
                        )
                        return@withLock
                    }

                    // Re-discover immediately before either operation. Discovery failure never mutates saved state.
                    val freshDiscovery = safeDiscovery()
                    if (freshDiscovery !is ApplicationDiscoveryResult.Available) {
                        loaded = loaded?.copy(
                            discovery = ApplicationDiscoveryResult.Unavailable,
                            notice = HiddenManagementNotice.DISCOVERY_UNAVAILABLE,
                        )
                        return@withLock
                    }
                    val isCurrentlyDiscovered = freshDiscovery.applications.any {
                        it.packageName == packageName && it.isLaunchable
                    }
                    if (hide && !isCurrentlyDiscovered) {
                        loaded = loaded?.copy(
                            discovery = freshDiscovery,
                            applications = freshDiscovery.applications,
                            notice = HiddenManagementNotice.APPLICATION_NO_LONGER_AVAILABLE,
                        )
                        return@withLock
                    }
                    if (!hide && !staleEntry && !isCurrentlyDiscovered) {
                        loaded = loaded?.copy(
                            discovery = freshDiscovery,
                            applications = freshDiscovery.applications,
                            notice = HiddenManagementNotice.APPLICATION_NO_LONGER_AVAILABLE,
                        )
                        return@withLock
                    }
                    // A stale-row action explicitly removes only this exact saved package preference. It does
                    // not claim the app is installed or alter Android package-manager state.

                    val freshHidden = safeHiddenSnapshot()
                    if (freshHidden !is HiddenApplicationsSnapshot.Available) {
                        loaded = loaded?.copy(
                            hiddenApplications = freshHidden,
                            notice = when (freshHidden) {
                                HiddenApplicationsSnapshot.Unreadable -> HiddenManagementNotice.HIDDEN_STATE_UNREADABLE
                                HiddenApplicationsSnapshot.Unavailable -> HiddenManagementNotice.HIDDEN_STATE_UNAVAILABLE
                                is HiddenApplicationsSnapshot.Available -> error("unreachable")
                            },
                        )
                        return@withLock
                    }
                    val model = try {
                        HiddenApplication(packageName)
                    } catch (_: IllegalArgumentException) {
                        loaded = loaded?.copy(notice = HiddenManagementNotice.APPLICATION_NO_LONGER_AVAILABLE)
                        return@withLock
                    }
                    if ((model in freshHidden.applications) == hide) {
                        loaded = loaded?.copy(
                            discovery = freshDiscovery,
                            hiddenApplications = freshHidden,
                            notice = HiddenManagementNotice.ALREADY_IN_STATE,
                        )
                        return@withLock
                    }

                    // Re-check after repository/discovery reads so a session that expired while waiting cannot write.
                    val mutationSession = safeSessionState()
                    loaded = loaded?.copy(sessionState = mutationSession)
                    if (mutationSession !is SessionState.Authenticated) {
                        loaded = loaded?.copy(
                            notice = if (mutationSession == null) HiddenManagementNotice.SESSION_UNAVAILABLE
                            else HiddenManagementNotice.AUTHENTICATION_REQUIRED,
                        )
                        return@withLock
                    }

                    val result = try {
                        if (hide) hiddenApplicationRepository.hide(model)
                        else hiddenApplicationRepository.unhide(model)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        val afterFailure = safeHiddenSnapshot()
                        loaded = (loaded ?: current).copy(
                            hiddenApplications = afterFailure,
                            notice = HiddenManagementNotice.UPDATE_FAILED,
                        )
                        return@withLock
                    }
                    val notice = when (result) {
                        HiddenApplicationUpdateResult.UPDATED -> HiddenManagementNotice.CHANGES_SAVED
                        HiddenApplicationUpdateResult.ALREADY_IN_STATE -> HiddenManagementNotice.ALREADY_IN_STATE
                        HiddenApplicationUpdateResult.UNREADABLE -> HiddenManagementNotice.HIDDEN_STATE_UNREADABLE
                        HiddenApplicationUpdateResult.UNAVAILABLE -> HiddenManagementNotice.UPDATE_FAILED
                    }
                    // The post-write snapshot, not the requested action, determines the rendered row state.
                    loaded = readSnapshot(loaded, notice)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    val current = loaded
                    if (current != null) {
                        loaded = current.copy(
                            hiddenApplications = HiddenApplicationsSnapshot.Unavailable,
                            notice = HiddenManagementNotice.UPDATE_FAILED,
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

    private suspend fun readSnapshot(previous: BaseData?, notice: HiddenManagementNotice?): BaseData {
        val discovery = safeDiscovery()
        val hidden = safeHiddenSnapshot()
        val protected = safeProtectedSnapshot()
        val session = safeSessionState()
        val latest = loaded ?: previous
        val retainedApplications = when (discovery) {
            is ApplicationDiscoveryResult.Available -> discovery.applications
            ApplicationDiscoveryResult.Unavailable -> latest?.applications.orEmpty()
        }
        return BaseData(
            discovery = discovery,
            applications = retainedApplications,
            hiddenApplications = hidden,
            protectedApplications = protected,
            sessionState = session,
            query = latest?.query.orEmpty(),
            section = latest?.section ?: HiddenApplicationSection.ALL,
            sort = latest?.sort ?: HiddenApplicationSort.NAME_A_TO_Z,
            pendingPackageNames = latest?.pendingPackageNames.orEmpty(),
            notice = notice,
        )
    }

    private suspend fun safeDiscovery(): ApplicationDiscoveryResult = try {
        when (val result = applicationRepository.discoverLaunchableApplications()) {
            is ApplicationDiscoveryResult.Available -> ApplicationDiscoveryResult.Available(
                InstalledApplicationOrdering.deterministic(
                    result.applications.filter(InstalledApplication::isLaunchable)
                        .distinctBy(InstalledApplication::packageName),
                ),
            )
            ApplicationDiscoveryResult.Unavailable -> ApplicationDiscoveryResult.Unavailable
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        ApplicationDiscoveryResult.Unavailable
    }

    private suspend fun safeHiddenSnapshot(): HiddenApplicationsSnapshot = try {
        when (val result = hiddenApplicationRepository.getHiddenApplications()) {
            is HiddenApplicationsSnapshot.Available -> HiddenApplicationsSnapshot.Available(result.applications.toSet())
            HiddenApplicationsSnapshot.Unreadable -> HiddenApplicationsSnapshot.Unreadable
            HiddenApplicationsSnapshot.Unavailable -> HiddenApplicationsSnapshot.Unavailable
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        HiddenApplicationsSnapshot.Unavailable
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

    private suspend fun safeSessionState(): SessionState? = try {
        sessionManager.currentState()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }

    private fun publish() {
        val current = loaded ?: return
        val hidden = current.hiddenApplications as? HiddenApplicationsSnapshot.Available
        val hiddenPackages = hidden?.applications.orEmpty().mapTo(HashSet()) { it.packageName }
        val protected = (current.protectedApplications as? ProtectedApplicationsSnapshot.Available)
            ?.applications?.mapTo(HashSet()) { it.packageName }
        val filtered = current.applications.asSequence()
            .filter { current.section == HiddenApplicationSection.ALL || it.packageName in hiddenPackages }
            .filter { InstalledApplicationSearch.matches(it, current.query) }
            .map { application ->
                ManagedApplicationState(
                    application = application,
                    hidden = hidden?.let { application.packageName in hiddenPackages },
                    protected = protected?.contains(application.packageName),
                )
            }
            .toList()
        val rowByPackage = filtered.associateBy { it.application.packageName }
        val sorted = when (current.sort) {
            HiddenApplicationSort.NAME_A_TO_Z -> InstalledApplicationOrdering.deterministic(filtered.map { it.application })
            HiddenApplicationSort.NAME_Z_TO_A -> InstalledApplicationOrdering.reverseAlphabetical(filtered.map { it.application })
        }.mapNotNull { rowByPackage[it.packageName] }
        val appPackages = current.applications.mapTo(HashSet(), InstalledApplication::packageName)
        val staleHidden = if (current.discovery is ApplicationDiscoveryResult.Available && hidden != null) {
            hiddenPackages.asSequence()
                .filterNot { it in appPackages }
                .sortedWith(compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it })
                .toList()
        } else emptyList()
        val content = HiddenApplicationManagementContent(
            discovery = current.discovery,
            applications = current.applications,
            hiddenApplications = current.hiddenApplications,
            protectedApplications = current.protectedApplications,
            sessionState = current.sessionState,
            query = current.query,
            section = current.section,
            sort = current.sort,
            visibleApplications = sorted,
            staleHiddenPackageNames = staleHidden,
            pendingPackageNames = current.pendingPackageNames,
            notice = current.notice,
        )
        mutableState.value = when {
            current.discovery == ApplicationDiscoveryResult.Unavailable ||
                current.hiddenApplications !is HiddenApplicationsSnapshot.Available ->
                HiddenApplicationManagementState.Unavailable(content)
            current.applications.isEmpty() -> HiddenApplicationManagementState.Empty(content)
            current.query.isNotBlank() && sorted.isEmpty() &&
                !(current.section == HiddenApplicationSection.HIDDEN && staleHidden.isNotEmpty()) ->
                HiddenApplicationManagementState.NoResults(content)
            else -> HiddenApplicationManagementState.Ready(content)
        }
    }

    private data class BaseData(
        val discovery: ApplicationDiscoveryResult,
        val applications: List<InstalledApplication>,
        val hiddenApplications: HiddenApplicationsSnapshot,
        val protectedApplications: ProtectedApplicationsSnapshot,
        val sessionState: SessionState?,
        val query: String,
        val section: HiddenApplicationSection,
        val sort: HiddenApplicationSort,
        val pendingPackageNames: Set<String>,
        val notice: HiddenManagementNotice?,
    )

    class Factory(
        private val applicationRepository: ApplicationRepository,
        private val hiddenApplicationRepository: HiddenApplicationRepository,
        private val protectedApplicationRepository: ProtectedApplicationRepository,
        private val sessionManager: SessionManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HiddenApplicationManagementViewModel::class.java))
            return HiddenApplicationManagementViewModel(
                applicationRepository,
                hiddenApplicationRepository,
                protectedApplicationRepository,
                sessionManager,
            ) as T
        }
    }
}
