package com.ashishkumar.nivara.ui.launcher

import androidx.lifecycle.ViewModelStore
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.app.InstalledApplication
import com.ashishkumar.nivara.domain.apphide.HiddenApplication
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationRepository
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationUpdateResult
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationsSnapshot
import com.ashishkumar.nivara.domain.applock.ProtectedApplication
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.security.session.AuthenticatedSession
import com.ashishkumar.nivara.domain.security.session.AuthenticationSource
import com.ashishkumar.nivara.domain.security.session.SessionAuthenticationCompletion
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before fun setMain() { Dispatchers.setMain(mainDispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test
    fun visibleAppsAreOrderedAndHiddenPackagesNeverEnterNormalDrawer() = runTest(mainDispatcher) {
        val apps = listOf(app("hidden", "Hidden"), app("zebra", "Zebra"), app("alpha", "Alpha"))
        val hidden = FakeHiddenRepository(setOf(hiddenApp("hidden")))
        val harness = Harness(apps, hidden)
        val viewModel = harness.create()
        assertEquals(LauncherUiState.Loading, viewModel.state.value)
        viewModel.refresh()
        advanceUntilIdle()

        val ready = viewModel.state.value as LauncherUiState.Ready
        assertEquals(listOf("com.example.alpha", "com.example.zebra"), ready.applications.map { it.packageName })
        assertEquals(1, ready.hiddenApplicationCount)
        assertFalse(ready.hiddenApplicationsRevealed)
        harness.close()
    }

    @Test
    fun allHiddenAndNoAppsAreDistinctAndRevealNeedsTheExistingSession() = runTest(mainDispatcher) {
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera"), hiddenApp("notes")))
        val harness = Harness(listOf(app("camera", "Camera"), app("notes", "Notes")), hidden)
        val viewModel = harness.create()
        viewModel.refresh()
        assertEquals(LauncherUiState.EmptyVisibleCatalogue(2), viewModel.state.value)

        assertEquals(RevealRequestResult.AUTHENTICATION_REQUIRED, viewModel.revealHiddenApplications())
        assertEquals(LauncherUiState.EmptyVisibleCatalogue(2), viewModel.state.value)
        assertEquals(0, hidden.writeCount)

        harness.session.authenticate()
        assertEquals(RevealRequestResult.REVEALED, viewModel.revealHiddenApplications())
        val revealed = viewModel.state.value as LauncherUiState.Ready
        assertEquals(2, revealed.applications.size)
        assertTrue(revealed.hiddenApplicationsRevealed)
        assertEquals(0, hidden.writeCount)
        assertEquals(0, harness.session.primaryAuthenticationCalls)
        assertEquals(0, harness.session.biometricAuthenticationCalls)

        val emptyHarness = Harness(emptyList(), FakeHiddenRepository())
        val empty = emptyHarness.create()
        empty.refresh()
        assertEquals(LauncherUiState.NoApplications, empty.state.value)
        emptyHarness.close()
        harness.close()
    }

    @Test
    fun revealWithNoHiddenAppsDoesNotRequireAuthenticationOrCreateSession() = runTest(mainDispatcher) {
        val harness = Harness(listOf(app("camera", "Camera")), FakeHiddenRepository())
        val viewModel = harness.create()
        viewModel.refresh()

        assertEquals(RevealRequestResult.NO_HIDDEN_APPLICATIONS, viewModel.revealHiddenApplications())
        assertTrue(viewModel.state.value is LauncherUiState.Ready)
        assertEquals(0, harness.session.primaryAuthenticationCalls)
        assertEquals(0, harness.session.biometricAuthenticationCalls)
        harness.close()
    }

    @Test
    fun unreadableOrUnavailableHiddenStateNeverBecomesVisibleAll() = runTest(mainDispatcher) {
        val apps = listOf(app("camera", "Camera"), app("notes", "Notes"))
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera")))
        val harness = Harness(apps, hidden)
        val viewModel = harness.create()

        hidden.snapshot = HiddenApplicationsSnapshot.Unreadable
        viewModel.refresh()
        assertEquals(LauncherUiState.HiddenStateUnavailable(unreadable = true), viewModel.state.value)
        assertEquals(RevealRequestResult.HIDDEN_STATE_UNAVAILABLE, viewModel.revealHiddenApplications())
        assertTrue(viewModel.state.value !is LauncherUiState.Ready)

        hidden.snapshot = HiddenApplicationsSnapshot.Unavailable
        viewModel.refresh()
        assertEquals(LauncherUiState.HiddenStateUnavailable(unreadable = false), viewModel.state.value)
        assertTrue(viewModel.state.value !is LauncherUiState.Ready)
        harness.close()
    }

    @Test
    fun discoveryFailureAndCombinedFailureHaveExplicitSafeStates() = runTest(mainDispatcher) {
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera")))
        val apps = FakeApplicationRepository(listOf(app("camera", "Camera"), app("notes", "Notes")))
        val harness = Harness(apps, hidden)
        val viewModel = harness.create()
        viewModel.refresh()

        apps.result = ApplicationDiscoveryResult.Unavailable
        viewModel.refresh()
        assertEquals(LauncherUiState.ApplicationDiscoveryUnavailable, viewModel.state.value)
        assertTrue(viewModel.state.value !is LauncherUiState.Ready)

        hidden.snapshot = HiddenApplicationsSnapshot.Unreadable
        viewModel.refresh()
        assertEquals(LauncherUiState.DiscoveryAndHiddenStateUnavailable(hiddenStateUnreadable = true), viewModel.state.value)
        assertEquals(RevealRequestResult.HIDDEN_STATE_UNAVAILABLE, viewModel.revealHiddenApplications())
        assertTrue(viewModel.state.value !is LauncherUiState.Ready)
        harness.close()
    }

    @Test
    fun authenticatedRevealSurvivesRefreshButExpiryRemovesHiddenRows() = runTest(mainDispatcher) {
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera")))
        val harness = Harness(listOf(app("camera", "Camera"), app("notes", "Notes")), hidden)
        harness.session.authenticate()
        val viewModel = harness.create()
        viewModel.refresh()
        assertEquals(RevealRequestResult.REVEALED, viewModel.revealHiddenApplications())
        viewModel.refresh()
        assertTrue((viewModel.state.value as LauncherUiState.Ready).hiddenApplicationsRevealed)
        val readsBeforeExpiry = hidden.reads

        harness.session.expire()
        advanceUntilIdle()
        assertTrue(hidden.reads > readsBeforeExpiry)
        assertEquals(LauncherUiState.Ready(listOf(app("notes", "Notes")), 1, false), viewModel.state.value)
        assertFalse((viewModel.state.value as LauncherUiState.Ready).applications.any { it.packageName == "com.example.camera" })
        assertEquals(0, hidden.writeCount)
        harness.close()
    }

    @Test
    fun aNewSessionCannotInheritAnOldTemporaryRevealEvenIfExpiryEventsAreConflated() = runTest(mainDispatcher) {
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera")))
        val harness = Harness(listOf(app("camera", "Camera"), app("notes", "Notes")), hidden)
        harness.session.authenticate()
        val viewModel = harness.create()
        viewModel.refresh()
        assertEquals(RevealRequestResult.REVEALED, viewModel.revealHiddenApplications())

        harness.session.expire()
        harness.session.authenticate()
        viewModel.refresh()

        assertEquals(listOf("com.example.notes"),
            (viewModel.state.value as LauncherUiState.Ready).applications.map { it.packageName })
        assertFalse((viewModel.state.value as LauncherUiState.Ready).hiddenApplicationsRevealed)
        assertEquals(0, hidden.writeCount)
        harness.close()
    }

    @Test
    fun quickLockUsesOnlySessionManagerAndClearsTemporaryReveal() = runTest(mainDispatcher) {
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera")))
        val harness = Harness(listOf(app("camera", "Camera"), app("notes", "Notes")), hidden)
        harness.session.authenticate()
        val viewModel = harness.create()
        viewModel.refresh()
        assertEquals(RevealRequestResult.REVEALED, viewModel.revealHiddenApplications())

        assertTrue(viewModel.quickLock())
        advanceUntilIdle()

        assertEquals(1, harness.session.lockNowCalls)
        assertEquals(LauncherUiState.Ready(listOf(app("notes", "Notes")), 1, false), viewModel.state.value)
        assertEquals(0, hidden.writeCount)
        harness.close()
    }

    @Test
    fun processRecreationDoesNotRestorePresentationReveal() = runTest(mainDispatcher) {
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera")))
        val apps = listOf(app("camera", "Camera"), app("notes", "Notes"))
        val first = Harness(apps, hidden)
        first.session.authenticate()
        val firstViewModel = first.create()
        firstViewModel.refresh()
        assertEquals(RevealRequestResult.REVEALED, firstViewModel.revealHiddenApplications())
        first.close()

        val recreated = Harness(apps, hidden)
        val nextViewModel = recreated.create()
        nextViewModel.refresh()
        assertEquals(LauncherUiState.Ready(listOf(app("notes", "Notes")), 1, false), nextViewModel.state.value)
        assertEquals(0, hidden.writeCount)
        recreated.close()
    }

    @Test
    fun repositoryChangesAreObservedOnRefreshAndProtectedStateIsNeverTouched() = runTest(mainDispatcher) {
        val apps = listOf(
            app("normal", "Normal"), app("protected", "Protected"),
            app("hidden", "Hidden"), app("both", "Both"),
        )
        val hidden = FakeHiddenRepository(setOf(hiddenApp("hidden"), hiddenApp("both")))
        val protected = setOf(ProtectedApplication("com.example.protected"), ProtectedApplication("com.example.both"))
        val harness = Harness(apps, hidden)
        val viewModel = harness.create()
        viewModel.refresh()
        assertEquals(listOf("com.example.normal", "com.example.protected"),
            (viewModel.state.value as LauncherUiState.Ready).applications.map { it.packageName })
        harness.session.authenticate()
        assertEquals(RevealRequestResult.REVEALED, viewModel.revealHiddenApplications())
        assertEquals(listOf("com.example.both", "com.example.hidden", "com.example.normal", "com.example.protected"),
            (viewModel.state.value as LauncherUiState.Ready).applications.map { it.packageName })

        hidden.snapshot = HiddenApplicationsSnapshot.Available(setOf(hiddenApp("protected")))
        viewModel.hideRevealedApplications()
        assertEquals(listOf("com.example.both", "com.example.hidden", "com.example.normal"),
            (viewModel.state.value as LauncherUiState.Ready).applications.map { it.packageName })
        assertEquals(setOf(hiddenApp("protected")), hidden.current())
        assertEquals(setOf(ProtectedApplication("com.example.protected"), ProtectedApplication("com.example.both")), protected)
        assertEquals(0, hidden.writeCount)
        harness.close()
    }

    @Test
    fun sessionExpiringDuringRepositoryReadPreventsReveal() = runTest(mainDispatcher) {
        val session = FakeSessionManager().apply { authenticate() }
        val hidden = FakeHiddenRepository(setOf(hiddenApp("camera"))).apply { onRead = { session.expire() } }
        val harness = Harness(listOf(app("camera", "Camera")), hidden, session)
        val viewModel = harness.create()
        assertEquals(RevealRequestResult.AUTHENTICATION_REQUIRED, viewModel.revealHiddenApplications())
        assertEquals(LauncherUiState.EmptyVisibleCatalogue(1), viewModel.state.value)
        assertEquals(0, hidden.writeCount)
        harness.close()
    }

    private class Harness(
        private val appRepository: FakeApplicationRepository,
        val hidden: FakeHiddenRepository,
        val session: FakeSessionManager = FakeSessionManager(),
    ) {
        constructor(applications: List<InstalledApplication>, hidden: FakeHiddenRepository) :
            this(FakeApplicationRepository(applications), hidden)
        constructor(applications: List<InstalledApplication>, hidden: FakeHiddenRepository, session: FakeSessionManager) :
            this(FakeApplicationRepository(applications), hidden, session)

        private val store = ViewModelStore()
        fun create() = LauncherViewModel(appRepository, hidden, session).also { store.put("launcher", it) }
        fun close() = store.clear()
    }

    private class FakeApplicationRepository(var result: ApplicationDiscoveryResult) : ApplicationRepository {
        constructor(applications: List<InstalledApplication>) : this(ApplicationDiscoveryResult.Available(applications))
        override suspend fun discoverLaunchableApplications() = result
    }

    private class FakeHiddenRepository(initial: Set<HiddenApplication> = emptySet()) : HiddenApplicationRepository {
        var snapshot: HiddenApplicationsSnapshot = HiddenApplicationsSnapshot.Available(initial)
        var reads = 0
        var writeCount = 0
        var onRead: (() -> Unit)? = null
        fun current() = (snapshot as? HiddenApplicationsSnapshot.Available)?.applications.orEmpty()
        override suspend fun getHiddenApplications(): HiddenApplicationsSnapshot {
            reads++
            onRead?.invoke()
            return snapshot
        }
        override suspend fun hide(application: HiddenApplication) = HiddenApplicationUpdateResult.UNAVAILABLE
        override suspend fun unhide(application: HiddenApplication) = HiddenApplicationUpdateResult.UNAVAILABLE
    }

    private class FakeSessionManager : SessionManager {
        private val mutableState = MutableStateFlow<SessionState>(SessionState.Unauthenticated)
        override val sessionState = mutableState
        var lockNowCalls = 0
        var primaryAuthenticationCalls = 0
        var biometricAuthenticationCalls = 0
        override suspend fun currentState() = mutableState.value
        override suspend fun mayAccessSensitiveContent() = mutableState.value is SessionState.Authenticated
        override suspend fun lockNow() {
            lockNowCalls++
            mutableState.value = SessionState.Unauthenticated
        }
        fun authenticate() {
            mutableState.value = SessionState.Authenticated(
                AuthenticatedSession(AuthenticationSource.PRIMARY, 100, 10_000),
            )
        }
        fun expire() { mutableState.value = SessionState.Unauthenticated }
        override suspend fun authenticatePrimary(authenticate: suspend () -> AuthenticationResult): SessionAuthenticationCompletion<AuthenticationResult> {
            primaryAuthenticationCalls++
            return SessionAuthenticationCompletion(authenticate(), false)
        }
        override suspend fun authenticateBiometric(authenticate: suspend () -> BiometricAuthenticationResult): SessionAuthenticationCompletion<BiometricAuthenticationResult> {
            biometricAuthenticationCalls++
            return SessionAuthenticationCompletion(authenticate(), false)
        }
    }

    private fun app(suffix: String, label: String) = InstalledApplication("com.example.$suffix", label, true)
    private fun hiddenApp(suffix: String) = HiddenApplication("com.example.$suffix")
}
