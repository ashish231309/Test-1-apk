package com.ashishkumar.nivara.ui.apphide

import androidx.lifecycle.ViewModelStore
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.app.InstalledApplication
import com.ashishkumar.nivara.domain.apphide.HiddenApplication
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationRepository
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationUpdateResult
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationsSnapshot
import com.ashishkumar.nivara.domain.applock.ProtectedApplication
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationLookup
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationRepository
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationUpdateResult
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.security.session.AuthenticatedSession
import com.ashishkumar.nivara.domain.security.session.AuthenticationSource
import com.ashishkumar.nivara.domain.security.session.SessionAuthenticationCompletion
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HiddenApplicationManagementViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before fun setMain() { Dispatchers.setMain(mainDispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test
    fun catalogueProjectsAllFourIndependentAppLockAndHiddenCombinations() = runTest(mainDispatcher) {
        val apps = listOf(
            app("normal", "Normal"),
            app("protected", "Protected"),
            app("hidden", "Hidden"),
            app("both", "Both"),
        )
        val hidden = FakeHiddenRepository(setOf(hiddenApp("hidden"), hiddenApp("both")))
        val protected = FakeProtectedRepository(setOf(protected("protected"), protected("both")))
        val harness = Harness(apps, hidden, protected)
        val viewModel = harness.create()
        assertEquals(HiddenApplicationManagementState.Loading, viewModel.state.value)
        advanceUntilIdle()

        val rows = (viewModel.state.value as HiddenApplicationManagementState.Ready).content.visibleApplications
            .associateBy { it.application.packageName }
        assertEquals(false, rows["com.example.normal"]?.hidden)
        assertEquals(false, rows["com.example.normal"]?.protected)
        assertEquals(false, rows["com.example.protected"]?.hidden)
        assertEquals(true, rows["com.example.protected"]?.protected)
        assertEquals(true, rows["com.example.hidden"]?.hidden)
        assertEquals(false, rows["com.example.hidden"]?.protected)
        assertEquals(true, rows["com.example.both"]?.hidden)
        assertEquals(true, rows["com.example.both"]?.protected)
        harness.close()
    }

    @Test
    fun hideAndUnhideChangeOnlyHiddenMembershipForProtectedApps() = runTest(mainDispatcher) {
        val protectedSet = setOf(protected("camera"), protected("notes"))
        val protectedRepository = FakeProtectedRepository(protectedSet)
        val hiddenRepository = FakeHiddenRepository(setOf(hiddenApp("camera")))
        val camera = app("camera", "Camera")
        val notes = app("notes", "Notes")
        val harness = Harness(listOf(camera, notes), hiddenRepository, protectedRepository)
        harness.session.authenticate()
        val viewModel = harness.create()
        advanceUntilIdle()

        viewModel.unhide(camera)
        advanceUntilIdle()
        assertTrue(hiddenRepository.current().isEmpty())
        assertEquals(protectedSet, protectedRepository.currentPackages())
        assertEquals(0, protectedRepository.writeCount)

        viewModel.hide(notes)
        advanceUntilIdle()
        assertEquals(setOf(hiddenApp("notes")), hiddenRepository.current())
        assertEquals(protectedSet, protectedRepository.currentPackages())
        assertEquals(0, protectedRepository.writeCount)
        harness.close()
    }

    @Test
    fun hiddenSectionSearchSortAndRefreshPreservePresentationSettings() = runTest(mainDispatcher) {
        val apps = listOf(app("camera", "Camera"), app("calendar", "Calendar"), app("notes", "Notes"))
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera"), hiddenApp("notes")))
        val harness = Harness(apps, hidden, FakeProtectedRepository())
        val viewModel = harness.create()
        advanceUntilIdle()

        viewModel.setSection(HiddenApplicationSection.HIDDEN)
        viewModel.setQuery("com.example.missing")
        assertTrue(viewModel.state.value is HiddenApplicationManagementState.NoResults)
        viewModel.setQuery("notes")
        assertEquals(listOf("com.example.notes"), ready(viewModel).visibleApplications.map { it.application.packageName })
        viewModel.setQuery("")
        viewModel.setSort(HiddenApplicationSort.NAME_Z_TO_A)
        assertEquals(
            listOf("com.example.notes", "com.example.camera"),
            ready(viewModel).visibleApplications.map { it.application.packageName },
        )
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(HiddenApplicationSection.HIDDEN, ready(viewModel).section)
        assertEquals(HiddenApplicationSort.NAME_Z_TO_A, ready(viewModel).sort)
        assertEquals("", ready(viewModel).query)
        assertEquals(0, hidden.writes)
        harness.close()
    }

    @Test
    fun emptyCatalogueAndNothingHiddenAreSeparateReadableStates() = runTest(mainDispatcher) {
        val harness = Harness(emptyList(), FakeHiddenRepository(), FakeProtectedRepository())
        val viewModel = harness.create()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is HiddenApplicationManagementState.Empty)
        assertEquals(emptySet<HiddenApplication>(), (content(viewModel).hiddenApplications as HiddenApplicationsSnapshot.Available).applications)
        harness.close()
    }

    @Test
    fun corruptAndUnavailableHiddenStateNeverBecomeAnEmptyHiddenList() = runTest(mainDispatcher) {
        val hidden = FakeHiddenRepository()
        val harness = Harness(listOf(app("camera", "Camera")), hidden, FakeProtectedRepository())
        val viewModel = harness.create()
        advanceUntilIdle()
        assertEquals(emptySet<HiddenApplication>(), ready(viewModel).hiddenApplications.let {
            (it as HiddenApplicationsSnapshot.Available).applications
        })

        hidden.snapshot = HiddenApplicationsSnapshot.Unreadable
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.setSection(HiddenApplicationSection.HIDDEN)
        val unreadable = content(viewModel)
        assertEquals(HiddenApplicationsSnapshot.Unreadable, unreadable.hiddenApplications)
        assertTrue(viewModel.state.value is HiddenApplicationManagementState.Unavailable)

        hidden.snapshot = HiddenApplicationsSnapshot.Unavailable
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(HiddenApplicationsSnapshot.Unavailable, content(viewModel).hiddenApplications)
        harness.close()
    }

    @Test
    fun discoveryFailureRetainsLastCatalogueAndNeverChangesSavedHiddenState() = runTest(mainDispatcher) {
        val app = app("camera", "Camera")
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera")))
        val apps = FakeApplicationRepository(listOf(app))
        val harness = Harness(apps, hidden, FakeProtectedRepository())
        val viewModel = harness.create()
        advanceUntilIdle()
        harness.session.authenticate()
        advanceUntilIdle()

        apps.result = ApplicationDiscoveryResult.Unavailable
        viewModel.refresh()
        advanceUntilIdle()
        val unavailable = viewModel.state.value as HiddenApplicationManagementState.Unavailable
        assertEquals(listOf(app), unavailable.content.applications)
        viewModel.unhide(app)
        advanceUntilIdle()
        assertEquals(setOf(hiddenApp("camera")), hidden.current())
        assertEquals(0, hidden.writes)
        harness.close()
    }

    @Test
    fun hideAndUnhideRequireTheLiveExistingSessionAndReReadRepository() = runTest(mainDispatcher) {
        val app = app("camera", "Camera")
        val hidden = FakeHiddenRepository()
        val harness = Harness(listOf(app), hidden, FakeProtectedRepository())
        val viewModel = harness.create()
        advanceUntilIdle()

        viewModel.hide(app)
        advanceUntilIdle()
        assertEquals(0, hidden.writes)
        assertEquals(HiddenManagementNotice.AUTHENTICATION_REQUIRED, content(viewModel).notice)

        harness.session.authenticate()
        viewModel.hide(app)
        advanceUntilIdle()
        assertEquals(setOf(hiddenApp("camera")), hidden.current())
        assertEquals(1, hidden.writes)
        assertEquals(HiddenManagementNotice.CHANGES_SAVED, content(viewModel).notice)
        assertTrue(content(viewModel).visibleApplications.single().hidden == true)

        harness.session.lockNow()
        viewModel.unhide(app)
        advanceUntilIdle()
        assertEquals(1, hidden.writes)
        assertEquals(setOf(hiddenApp("camera")), hidden.current())
        harness.close()
    }

    @Test
    fun sessionExpiringDuringFreshDiscoveryIsRecheckedBeforeTheWrite() = runTest(mainDispatcher) {
        val app = app("camera", "Camera")
        val apps = FakeApplicationRepository(listOf(app))
        val hidden = FakeHiddenRepository()
        val harness = Harness(apps, hidden, FakeProtectedRepository())
        harness.session.authenticate()
        val viewModel = harness.create()
        advanceUntilIdle()
        apps.onDiscovery = { call -> if (call == 2) harness.session.expire() }

        viewModel.hide(app)
        advanceUntilIdle()

        assertEquals(0, hidden.writes)
        assertTrue(hidden.current().isEmpty())
        assertEquals(HiddenManagementNotice.AUTHENTICATION_REQUIRED, content(viewModel).notice)
        harness.close()
    }

    @Test
    fun expiredSessionRefusesMutationWithoutCreatingHiddenAuthenticationState() = runTest(mainDispatcher) {
        val app = app("camera", "Camera")
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera")))
        val harness = Harness(listOf(app), hidden, FakeProtectedRepository())
        val viewModel = harness.create()
        advanceUntilIdle()
        harness.session.authenticate()
        harness.session.expire()

        viewModel.unhide(app)
        advanceUntilIdle()

        assertEquals(setOf(hiddenApp("camera")), hidden.current())
        assertEquals(0, hidden.writes)
        assertEquals(HiddenManagementNotice.AUTHENTICATION_REQUIRED, content(viewModel).notice)
        harness.close()
    }

    @Test
    fun freshDiscoveryRejectsStaleHideButExplicitStalePreferenceRemovalIsSafe() = runTest(mainDispatcher) {
        val app = app("camera", "Camera")
        val apps = FakeApplicationRepository(listOf(app))
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera"), hiddenApp("removed")))
        val harness = Harness(apps, hidden, FakeProtectedRepository())
        harness.session.authenticate()
        val viewModel = harness.create()
        advanceUntilIdle()

        apps.result = ApplicationDiscoveryResult.Available(emptyList())
        viewModel.hide(app)
        advanceUntilIdle()
        assertEquals(setOf(hiddenApp("camera"), hiddenApp("removed")), hidden.current())
        assertEquals(0, hidden.writes)

        viewModel.setSection(HiddenApplicationSection.HIDDEN)
        assertEquals(listOf("com.example.camera", "com.example.removed"), content(viewModel).staleHiddenPackageNames)
        viewModel.removeStaleHiddenPreference("com.example.removed")
        advanceUntilIdle()
        assertEquals(setOf(hiddenApp("camera")), hidden.current())
        assertEquals(1, hidden.writes)
        harness.close()
    }

    @Test
    fun failedWriteIsReadBackWithoutOptimisticHiddenStateAndQuickLockBlocksWrites() = runTest(mainDispatcher) {
        val app = app("camera", "Camera")
        val hidden = FakeHiddenRepository().apply { nextResult = HiddenApplicationUpdateResult.UNAVAILABLE }
        val harness = Harness(listOf(app), hidden, FakeProtectedRepository())
        harness.session.authenticate()
        val viewModel = harness.create()
        advanceUntilIdle()
        val readsBefore = hidden.reads

        viewModel.hide(app)
        advanceUntilIdle()
        assertEquals(readsBefore + 2, hidden.reads) // fresh pre-write read, then post-result read
        assertTrue(hidden.current().isEmpty())
        assertEquals(false, content(viewModel).visibleApplications.single().hidden)
        assertEquals(HiddenManagementNotice.UPDATE_FAILED, content(viewModel).notice)

        harness.session.lockNow()
        viewModel.hide(app)
        advanceUntilIdle()
        assertEquals(0, hidden.writes)
        harness.close()
    }

    @Test
    fun concurrentDuplicateActionsAreSerializedAndDoNotWriteTwice() = runTest(mainDispatcher) {
        val app = app("camera", "Camera")
        val hidden = FakeHiddenRepository().apply { gate = CompletableDeferred<Unit>() }
        val harness = Harness(listOf(app), hidden, FakeProtectedRepository())
        harness.session.authenticate()
        val viewModel = harness.create()
        advanceUntilIdle()

        viewModel.hide(app)
        runCurrent()
        assertEquals(0, hidden.writes)
        viewModel.hide(app)
        runCurrent()
        hidden.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, hidden.writes)
        assertEquals(setOf(hiddenApp("camera")), hidden.current())
        harness.close()
    }

    private class Harness(
        private val appRepository: FakeApplicationRepository,
        val hidden: FakeHiddenRepository,
        val protected: FakeProtectedRepository,
    ) {
        constructor(applications: List<InstalledApplication>, hidden: FakeHiddenRepository, protected: FakeProtectedRepository) :
            this(FakeApplicationRepository(applications), hidden, protected)

        private val viewModelStore = ViewModelStore()
        val session = FakeSessionManager()
        fun create() = HiddenApplicationManagementViewModel(appRepository, hidden, protected, session)
            .also { viewModelStore.put("hidden-management", it) }
        fun close() = viewModelStore.clear()
    }

    private class FakeApplicationRepository(var result: ApplicationDiscoveryResult) : ApplicationRepository {
        constructor(applications: List<InstalledApplication>) : this(ApplicationDiscoveryResult.Available(applications))
        var discoveryCalls = 0
        var onDiscovery: ((Int) -> Unit)? = null
        override suspend fun discoverLaunchableApplications(): ApplicationDiscoveryResult {
            discoveryCalls++
            onDiscovery?.invoke(discoveryCalls)
            return result
        }
    }

    private class FakeHiddenRepository(initial: Set<HiddenApplication> = emptySet()) : HiddenApplicationRepository {
        var snapshot: HiddenApplicationsSnapshot = HiddenApplicationsSnapshot.Available(initial)
        var reads = 0
        var writes = 0
        var nextResult: HiddenApplicationUpdateResult? = null
        var gate: CompletableDeferred<Unit>? = null
        fun current() = (snapshot as? HiddenApplicationsSnapshot.Available)?.applications.orEmpty()
        override suspend fun getHiddenApplications(): HiddenApplicationsSnapshot { reads++; return snapshot }
        override suspend fun hide(application: HiddenApplication) = update(application, true)
        override suspend fun unhide(application: HiddenApplication) = update(application, false)
        private suspend fun update(application: HiddenApplication, hide: Boolean): HiddenApplicationUpdateResult {
            gate?.await()
            val outcome = nextResult.also { nextResult = null }
            if (outcome != null) return outcome
            val value = snapshot as? HiddenApplicationsSnapshot.Available ?: return when (snapshot) {
                HiddenApplicationsSnapshot.Unreadable -> HiddenApplicationUpdateResult.UNREADABLE
                HiddenApplicationsSnapshot.Unavailable -> HiddenApplicationUpdateResult.UNAVAILABLE
                is HiddenApplicationsSnapshot.Available -> error("unreachable")
            }
            val updated = value.applications.toMutableSet()
            val changed = if (hide) updated.add(application) else updated.remove(application)
            if (!changed) return HiddenApplicationUpdateResult.ALREADY_IN_STATE
            writes++
            snapshot = HiddenApplicationsSnapshot.Available(updated)
            return HiddenApplicationUpdateResult.UPDATED
        }
    }

    private class FakeProtectedRepository(initial: Set<ProtectedApplication> = emptySet()) : ProtectedApplicationRepository {
        private var snapshot: ProtectedApplicationsSnapshot = ProtectedApplicationsSnapshot.Available(initial)
        var writeCount = 0
            private set
        fun currentPackages() = (snapshot as? ProtectedApplicationsSnapshot.Available)?.applications.orEmpty()
        override suspend fun getProtectedApplications() = snapshot
        override suspend fun isProtected(packageName: String) = when (val value = snapshot) {
            ProtectedApplicationsSnapshot.Unavailable -> ProtectedApplicationLookup.Unavailable
            is ProtectedApplicationsSnapshot.Available -> if (value.applications.any { it.packageName == packageName }) {
                ProtectedApplicationLookup.Protected
            } else ProtectedApplicationLookup.NotProtected
        }
        override suspend fun setProtected(application: ProtectedApplication, protected: Boolean): ProtectedApplicationUpdateResult {
            val available = snapshot as? ProtectedApplicationsSnapshot.Available
                ?: return ProtectedApplicationUpdateResult.UNAVAILABLE
            val updated = available.applications.toMutableSet()
            if (protected) updated.add(application) else updated.remove(application)
            writeCount++
            snapshot = ProtectedApplicationsSnapshot.Available(updated)
            return ProtectedApplicationUpdateResult.UPDATED
        }
    }

    private class FakeSessionManager : SessionManager {
        private val mutableState = MutableStateFlow<SessionState>(SessionState.Unauthenticated)
        override val sessionState = mutableState
        fun authenticate() {
            mutableState.value = SessionState.Authenticated(AuthenticatedSession(AuthenticationSource.PRIMARY, 10, 100))
        }
        fun expire() { mutableState.value = SessionState.Unauthenticated }
        override suspend fun authenticatePrimary(authenticate: suspend () -> AuthenticationResult) =
            SessionAuthenticationCompletion(authenticate(), false)
        override suspend fun authenticateBiometric(authenticate: suspend () -> BiometricAuthenticationResult) =
            SessionAuthenticationCompletion(authenticate(), false)
        override suspend fun currentState() = mutableState.value
        override suspend fun mayAccessSensitiveContent() = mutableState.value is SessionState.Authenticated
        override suspend fun lockNow() { mutableState.value = SessionState.Unauthenticated }
    }

    private fun ready(viewModel: HiddenApplicationManagementViewModel) =
        (viewModel.state.value as HiddenApplicationManagementState.Ready).content

    private fun content(viewModel: HiddenApplicationManagementViewModel): HiddenApplicationManagementContent =
        when (val state = viewModel.state.value) {
            is HiddenApplicationManagementState.Ready -> state.content
            is HiddenApplicationManagementState.Empty -> state.content
            is HiddenApplicationManagementState.NoResults -> state.content
            is HiddenApplicationManagementState.Unavailable -> state.content
            HiddenApplicationManagementState.Loading -> error("Expected loaded state")
        }

    private fun app(suffix: String, label: String) = InstalledApplication("com.example.$suffix", label, true)
    private fun hiddenApp(suffix: String) = HiddenApplication("com.example.$suffix")
    private fun protected(suffix: String) = ProtectedApplication("com.example.$suffix")
}
