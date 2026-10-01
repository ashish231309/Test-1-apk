package com.ashishkumar.nivara.ui.applock

import androidx.lifecycle.ViewModelStore
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.app.InstalledApplication
import com.ashishkumar.nivara.domain.applock.AppLockDetectionState
import com.ashishkumar.nivara.domain.applock.AppLockDegradedReason
import com.ashishkumar.nivara.domain.applock.AppLockMonitor
import com.ashishkumar.nivara.domain.applock.AppLockMonitoringController
import com.ashishkumar.nivara.domain.applock.MonitoringStartResult
import com.ashishkumar.nivara.domain.applock.ProtectionEvent
import com.ashishkumar.nivara.domain.applock.ProtectionDecision
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityRepository
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus
import com.ashishkumar.nivara.domain.applock.OverlaySettingsResult
import com.ashishkumar.nivara.domain.applock.ProtectedApplication
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationLookup
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationRepository
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationUpdateResult
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.credentials.CredentialChangeResult
import com.ashishkumar.nivara.domain.credentials.CredentialServiceStatus
import com.ashishkumar.nivara.domain.credentials.EnrollmentResult
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessSettingsResult
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus
import com.ashishkumar.nivara.domain.security.session.AuthenticatedSession
import com.ashishkumar.nivara.domain.security.session.AuthenticationSource
import com.ashishkumar.nivara.domain.security.session.SessionAuthenticationCompletion
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
class AppLockManagementViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialLoadExposesIndependentReadinessAndSearchAndSortUseDomainContracts() = runTest(mainDispatcher) {
        val appRepository = FakeApplicationRepository(
            ApplicationDiscoveryResult.Available(
                listOf(
                    InstalledApplication("com.example.camera", "Camera", true),
                    InstalledApplication("com.example.calendar", "Calendar", true),
                    InstalledApplication("com.example.photos", "Photos", true),
                ),
            ),
        )
        val protectedRepository = FakeProtectedRepository(setOf(ProtectedApplication("com.example.photos")))
        val harness = Harness(appRepository, protectedRepository)
        val viewModel = harness.createViewModel()
        advanceUntilIdle()

        val ready = viewModel.state.value as AppLockManagementUiState.Ready
        assertEquals(UsageAccessStatus.NOT_GRANTED, ready.content.readiness.usageAccess)
        assertEquals(OverlayCapabilityStatus.UNAVAILABLE, ready.content.readiness.overlay)
        assertEquals(AppLockManagementReadiness.DiscoveryReadiness.AVAILABLE, ready.content.readiness.discovery)

        viewModel.setQuery("  photo ")
        val searched = viewModel.state.value as AppLockManagementUiState.Ready
        assertEquals(listOf("com.example.photos"), searched.content.visibleApplications.map(InstalledApplication::packageName))
        viewModel.setQuery("no match")
        assertTrue(viewModel.state.value is AppLockManagementUiState.NoResults)
        viewModel.setQuery("")
        viewModel.setSection(AppLockApplicationSection.PROTECTED)
        assertEquals(
            listOf("com.example.photos"),
            (viewModel.state.value as AppLockManagementUiState.Ready).content.visibleApplications.map(InstalledApplication::packageName),
        )
        viewModel.setSection(AppLockApplicationSection.ALL)
        viewModel.setSort(AppLockApplicationSort.NAME_Z_TO_A)
        val sorted = viewModel.state.value as AppLockManagementUiState.Ready
        assertEquals(
            listOf("com.example.photos", "com.example.camera", "com.example.calendar"),
            sorted.content.visibleApplications.map(InstalledApplication::packageName),
        )
        assertEquals(setOf(ProtectedApplication("com.example.photos")), protectedRepository.currentPackages())
        harness.close()
    }

    @Test
    fun readinessCanRepresentBothDetectionAndPresentationAsAvailable() = runTest(mainDispatcher) {
        val harness = Harness(
            FakeApplicationRepository(ApplicationDiscoveryResult.Available(emptyList())),
            FakeProtectedRepository(),
            usageStatus = UsageAccessStatus.GRANTED,
            overlayStatus = OverlayCapabilityStatus.GRANTED,
        )
        val viewModel = harness.createViewModel()
        advanceUntilIdle()

        val content = (viewModel.state.value as AppLockManagementUiState.Empty).content
        assertEquals(UsageAccessStatus.GRANTED, content.readiness.usageAccess)
        assertEquals(OverlayCapabilityStatus.GRANTED, content.readiness.overlay)
        assertEquals(CredentialServiceStatus.Configured(PrimaryCredentialType.PIN), content.readiness.primaryCredential)
        assertEquals(AppLockMonitoringStatus.Stopped, content.monitoringStatus)
        harness.close()
    }

    @Test
    fun emptyAndUnavailableSnapshotsRemainDistinctAndNeverClearProtectedState() = runTest(mainDispatcher) {
        val protectedRepository = FakeProtectedRepository(setOf(ProtectedApplication(PACKAGE_CAMERA)))
        val appRepository = FakeApplicationRepository(ApplicationDiscoveryResult.Available(emptyList()))
        val harness = Harness(appRepository, protectedRepository)
        val viewModel = harness.createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is AppLockManagementUiState.Empty)
        assertEquals(setOf(ProtectedApplication(PACKAGE_CAMERA)), protectedRepository.currentPackages())

        appRepository.result = ApplicationDiscoveryResult.Unavailable
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is AppLockManagementUiState.Unavailable)
        assertEquals(setOf(ProtectedApplication(PACKAGE_CAMERA)), protectedRepository.currentPackages())
        assertEquals(0, protectedRepository.writeCount)
        harness.close()
    }

    @Test
    fun protectAndUnprotectUseTheRepositoryAndAvoidRedundantWrites() = runTest(mainDispatcher) {
        val app = InstalledApplication(PACKAGE_CAMERA, "Camera", true)
        val secondApp = InstalledApplication("com.example.calendar", "Calendar", true)
        val appRepository = FakeApplicationRepository(ApplicationDiscoveryResult.Available(listOf(app, secondApp)))
        val protectedRepository = FakeProtectedRepository()
        val harness = Harness(appRepository, protectedRepository)
        harness.session.authenticate()
        val viewModel = harness.createViewModel()
        advanceUntilIdle()

        viewModel.setProtection(app, protect = true)
        advanceUntilIdle()
        assertEquals(setOf(ProtectedApplication(PACKAGE_CAMERA)), protectedRepository.currentPackages())
        assertEquals(1, protectedRepository.writeCount)
        assertEquals(1, harness.monitoringController.starts)
        assertEquals(AppLockManagementNotice.CHANGES_SAVED, (viewModel.state.value as AppLockManagementUiState.Ready).content.notice)

        harness.monitor.mutableState.value = AppLockDetectionState.Monitoring(
            foregroundPackage = null,
            decision = ProtectionDecision.NoProtectionRequired,
            authenticationRequestPendingFor = null,
        )
        viewModel.setProtection(secondApp, protect = true)
        advanceUntilIdle()
        assertEquals(2, protectedRepository.writeCount)
        assertEquals(1, harness.monitoringController.starts)

        viewModel.setProtection(app, protect = true)
        advanceUntilIdle()
        assertEquals(2, protectedRepository.writeCount)
        assertEquals(AppLockManagementNotice.ALREADY_IN_STATE, (viewModel.state.value as AppLockManagementUiState.Ready).content.notice)

        viewModel.setProtection(app, protect = false)
        advanceUntilIdle()
        assertEquals(setOf(ProtectedApplication(secondApp.packageName)), protectedRepository.currentPackages())
        assertEquals(3, protectedRepository.writeCount)
        viewModel.setProtection(secondApp, protect = false)
        advanceUntilIdle()
        assertEquals(emptySet<ProtectedApplication>(), protectedRepository.currentPackages())
        assertEquals(4, protectedRepository.writeCount)
        assertEquals(1, harness.monitoringController.starts)
        harness.close()
    }

    @Test
    fun freshDiscoveryFailurePreventsProtectionAndPreservesTheSavedSet() = runTest(mainDispatcher) {
        val app = InstalledApplication(PACKAGE_CAMERA, "Camera", true)
        val appRepository = FakeApplicationRepository(ApplicationDiscoveryResult.Available(listOf(app)))
        val protectedRepository = FakeProtectedRepository(setOf(ProtectedApplication("com.example.saved")))
        val harness = Harness(appRepository, protectedRepository)
        harness.session.authenticate()
        val viewModel = harness.createViewModel()
        advanceUntilIdle()

        appRepository.failNextDiscovery = true
        viewModel.setProtection(app, protect = true)
        advanceUntilIdle()

        assertEquals(0, protectedRepository.writeCount)
        assertEquals(setOf(ProtectedApplication("com.example.saved")), protectedRepository.currentPackages())
        assertTrue(viewModel.state.value is AppLockManagementUiState.Unavailable)
        assertEquals(2, appRepository.discoveryCalls)
        harness.close()
    }

    @Test
    fun thrownRepositoryWriteIsFollowedByARepositoryRead() = runTest(mainDispatcher) {
        val app = InstalledApplication(PACKAGE_CAMERA, "Camera", true)
        val protectedRepository = FakeProtectedRepository()
        val harness = Harness(
            FakeApplicationRepository(ApplicationDiscoveryResult.Available(listOf(app))),
            protectedRepository,
        )
        harness.session.authenticate()
        val viewModel = harness.createViewModel()
        advanceUntilIdle()
        protectedRepository.throwNextWrite = true

        viewModel.setProtection(app, protect = true)
        advanceUntilIdle()

        assertEquals(3, protectedRepository.readCount)
        assertEquals(emptySet<ProtectedApplication>(), protectedRepository.currentPackages())
        assertEquals(
            AppLockManagementNotice.UPDATE_FAILED,
            (viewModel.state.value as AppLockManagementUiState.Ready).content.notice,
        )
        harness.close()
    }

    @Test
    fun noExpiredOrQuickLockedSessionCanChangeConfiguration() = runTest(mainDispatcher) {
        val app = InstalledApplication(PACKAGE_CAMERA, "Camera", true)
        val protectedRepository = FakeProtectedRepository()
        val harness = Harness(
            FakeApplicationRepository(ApplicationDiscoveryResult.Available(listOf(app))),
            protectedRepository,
        )
        val viewModel = harness.createViewModel()
        advanceUntilIdle()

        viewModel.setProtection(app, protect = true)
        advanceUntilIdle()
        assertEquals(0, protectedRepository.writeCount)
        assertEquals(
            AppLockManagementNotice.AUTHENTICATION_REQUIRED,
            (viewModel.state.value as AppLockManagementUiState.Ready).content.notice,
        )

        harness.session.authenticate()
        harness.session.expire()
        viewModel.setProtection(app, protect = true)
        advanceUntilIdle()
        assertEquals(0, protectedRepository.writeCount)

        harness.session.authenticate()
        harness.session.lockNow()
        viewModel.setProtection(app, protect = true)
        advanceUntilIdle()
        assertEquals(0, protectedRepository.writeCount)
        harness.close()
    }

    @Test
    fun staleInstalledApplicationIsNotProtectedButStaleSavedEntryCanBeRemoved() = runTest(mainDispatcher) {
        val app = InstalledApplication(PACKAGE_CAMERA, "Camera", true)
        val appRepository = FakeApplicationRepository(ApplicationDiscoveryResult.Available(listOf(app)))
        val protectedRepository = FakeProtectedRepository(setOf(ProtectedApplication("com.example.removed")))
        val harness = Harness(appRepository, protectedRepository)
        harness.session.authenticate()
        val viewModel = harness.createViewModel()
        advanceUntilIdle()

        appRepository.result = ApplicationDiscoveryResult.Available(emptyList())
        viewModel.setProtection(app, protect = true)
        advanceUntilIdle()
        assertEquals(0, protectedRepository.writeCount)
        assertEquals(setOf(ProtectedApplication("com.example.removed")), protectedRepository.currentPackages())

        viewModel.setSection(AppLockApplicationSection.PROTECTED)
        val content = when (val state = viewModel.state.value) {
            is AppLockManagementUiState.Empty -> state.content
            is AppLockManagementUiState.Ready -> state.content
            is AppLockManagementUiState.NoResults -> state.content
            is AppLockManagementUiState.Unavailable -> state.content
            AppLockManagementUiState.Loading -> error("Expected loaded state")
        }
        assertEquals(listOf("com.example.removed"), content.unavailableProtectedPackageNames)
        viewModel.unprotectUnavailable("com.example.removed")
        advanceUntilIdle()
        assertEquals(emptySet<ProtectedApplication>(), protectedRepository.currentPackages())
        assertEquals(1, protectedRepository.writeCount)
        harness.close()
    }

    @Test
    fun repositoryReadFailureDisablesChangesInsteadOfShowingAnEmptyProtectedSet() = runTest(mainDispatcher) {
        val app = InstalledApplication(PACKAGE_CAMERA, "Camera", true)
        val protectedRepository = FakeProtectedRepository()
        val harness = Harness(FakeApplicationRepository(ApplicationDiscoveryResult.Available(listOf(app))), protectedRepository)
        harness.session.authenticate()
        val viewModel = harness.createViewModel()
        advanceUntilIdle()
        protectedRepository.snapshot = ProtectedApplicationsSnapshot.Unavailable
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.state.value is AppLockManagementUiState.Unavailable)
        val writes = protectedRepository.writeCount
        viewModel.setProtection(app, protect = true)
        advanceUntilIdle()
        assertEquals(writes, protectedRepository.writeCount)
        assertEquals(ProtectedApplicationsSnapshot.Unavailable, (viewModel.state.value as AppLockManagementUiState.Unavailable).content.protectedApplications)
        harness.close()
    }

    private class Harness(
        val apps: FakeApplicationRepository,
        val protected: FakeProtectedRepository,
        private val usageStatus: UsageAccessStatus = UsageAccessStatus.NOT_GRANTED,
        private val overlayStatus: OverlayCapabilityStatus = OverlayCapabilityStatus.UNAVAILABLE,
    ) {
        val session = FakeSessionManager()
        private val viewModelStore = ViewModelStore()
        val monitor = FakeMonitor()
        val monitoringController = FakeMonitoringController(monitor)
        private val usage = object : UsageAccessRepository {
            override suspend fun status() = usageStatus
            override fun openSettings() = UsageAccessSettingsResult.OPENED
        }
        private val overlay = object : OverlayCapabilityRepository {
            override fun status() = overlayStatus
            override fun openSettings() = OverlaySettingsResult.OPENED
        }
        private val credentials = object : PrimaryCredentialService {
            override suspend fun status() = CredentialServiceStatus.Configured(PrimaryCredentialType.PIN)
            override suspend fun enroll(type: PrimaryCredentialType, credential: CharArray, confirmation: CharArray) =
                EnrollmentResult.AlreadyConfigured
            override suspend fun authenticate(credential: CharArray) = AuthenticationResult.Failed
            override suspend fun changePrimary(
                currentCredential: CharArray,
                newType: PrimaryCredentialType,
                newCredential: CharArray,
                confirmation: CharArray,
            ) = CredentialChangeResult.InvalidConfiguration
        }

        fun createViewModel() = AppLockManagementViewModel(
            apps,
            protected,
            usage,
            overlay,
            credentials,
            session,
            monitor,
            { monitoringController.start() },
        ).also { viewModelStore.put("management", it) }

        fun close() {
            viewModelStore.clear()
            session.close()
        }
    }

    private class FakeApplicationRepository(var result: ApplicationDiscoveryResult) : ApplicationRepository {
        var failNextDiscovery = false
        var discoveryCalls = 0

        override suspend fun discoverLaunchableApplications(): ApplicationDiscoveryResult {
            discoveryCalls++
            if (failNextDiscovery) {
                failNextDiscovery = false
                return ApplicationDiscoveryResult.Unavailable
            }
            return result
        }
    }

    private class FakeProtectedRepository(initial: Set<ProtectedApplication> = emptySet()) : ProtectedApplicationRepository {
        var snapshot: ProtectedApplicationsSnapshot = ProtectedApplicationsSnapshot.Available(initial)
        var writeCount = 0
            private set
        var readCount = 0
            private set
        var throwNextWrite = false

        fun currentPackages(): Set<ProtectedApplication> =
            (snapshot as? ProtectedApplicationsSnapshot.Available)?.applications.orEmpty()

        override suspend fun getProtectedApplications(): ProtectedApplicationsSnapshot {
            readCount++
            return snapshot
        }
        override suspend fun isProtected(packageName: String) = when (val value = snapshot) {
            ProtectedApplicationsSnapshot.Unavailable -> ProtectedApplicationLookup.Unavailable
            is ProtectedApplicationsSnapshot.Available -> if (value.applications.any { it.packageName == packageName }) {
                ProtectedApplicationLookup.Protected
            } else ProtectedApplicationLookup.NotProtected
        }

        override suspend fun setProtected(
            application: ProtectedApplication,
            protected: Boolean,
        ): ProtectedApplicationUpdateResult {
            if (throwNextWrite) {
                throwNextWrite = false
                error("simulated repository write failure")
            }
            val available = snapshot as? ProtectedApplicationsSnapshot.Available
                ?: return ProtectedApplicationUpdateResult.UNAVAILABLE
            writeCount++
            val updated = available.applications.toMutableSet()
            if (protected) updated.add(application) else updated.remove(application)
            snapshot = ProtectedApplicationsSnapshot.Available(updated)
            return ProtectedApplicationUpdateResult.UPDATED
        }
    }

    private class FakeMonitor : AppLockMonitor {
        val mutableState = MutableStateFlow<AppLockDetectionState>(AppLockDetectionState.Stopped)
        override val state = mutableState
        override val protectionEvents = MutableSharedFlow<ProtectionEvent>()
        override fun start() = true
        override suspend fun refreshNow() = Unit
        override suspend fun reportUnavailable(reason: AppLockDegradedReason) {
            mutableState.value = AppLockDetectionState.Degraded(reason)
        }
        override suspend fun reportNoProtectedApplications() {
            mutableState.value = AppLockDetectionState.NoProtectedApplications
        }
        override fun stop() { mutableState.value = AppLockDetectionState.Stopped }
    }

    private class FakeMonitoringController(private val monitor: FakeMonitor) : AppLockMonitoringController {
        var starts = 0
        override suspend fun start(): MonitoringStartResult {
            starts++
            monitor.mutableState.value = AppLockDetectionState.Starting
            return MonitoringStartResult.START_REQUESTED
        }
        override fun stop() = monitor.stop()
    }

    private class FakeSessionManager : SessionManager {
        private val mutableState = MutableStateFlow<SessionState>(SessionState.Unauthenticated)
        override val sessionState = mutableState
        private val now = 10L

        fun authenticate() {
            mutableState.value = SessionState.Authenticated(
                AuthenticatedSession(AuthenticationSource.PRIMARY, now, now + 100),
            )
        }

        fun expire() {
            mutableState.value = SessionState.Unauthenticated
        }

        override suspend fun authenticatePrimary(
            authenticate: suspend () -> AuthenticationResult,
        ): SessionAuthenticationCompletion<AuthenticationResult> = SessionAuthenticationCompletion(
            authenticate(), false,
        )

        override suspend fun authenticateBiometric(
            authenticate: suspend () -> BiometricAuthenticationResult,
        ): SessionAuthenticationCompletion<BiometricAuthenticationResult> = SessionAuthenticationCompletion(
            authenticate(), false,
        )

        override suspend fun currentState() = mutableState.value
        override suspend fun mayAccessSensitiveContent() = mutableState.value is SessionState.Authenticated
        override suspend fun lockNow() { mutableState.value = SessionState.Unauthenticated }
        fun close() = Unit
    }

    private companion object {
        const val PACKAGE_CAMERA = "com.example.camera"
    }
}
