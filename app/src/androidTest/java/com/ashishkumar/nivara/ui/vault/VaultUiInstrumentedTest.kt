package com.ashishkumar.nivara.ui.vault

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.security.session.AuthenticatedSession
import com.ashishkumar.nivara.domain.security.session.AuthenticationSource
import com.ashishkumar.nivara.domain.security.session.SessionAuthenticationCompletion
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.VaultInitializationFailure
import com.ashishkumar.nivara.domain.vault.VaultInitializationResult
import com.ashishkumar.nivara.domain.vault.VaultRepository
import com.ashishkumar.nivara.domain.vault.VaultRootSelectionResult
import com.ashishkumar.nivara.domain.vault.VaultStatus
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultIndexInitializationResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexWriteResult
import com.ashishkumar.nivara.domain.vault.content.VaultImportRepository
import com.ashishkumar.nivara.domain.vault.content.VaultImportResult
import com.ashishkumar.nivara.domain.vault.content.VaultSourceSelectionId
import com.ashishkumar.nivara.domain.vault.content.VaultSourceSelectionResult
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRepository
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRead
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationSnapshot
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumMutationResult
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumId
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.ui.navigation.AppDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun vaultAndRootConfigurationRoutesNavigateInCompose() {
        composeRule.setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "test-home") {
                composable("test-home") {
                    Button(onClick = { navController.navigate(AppDestination.Vault.route) }) {
                        Text("Open vault")
                    }
                }
                composable(AppDestination.Vault.route) {
                    Text("Vault navigation destination")
                    Button(onClick = { navController.navigate(AppDestination.VaultRootConfiguration.route) }) {
                        Text("Configure root")
                    }
                }
                composable(AppDestination.VaultRootConfiguration.route) {
                    Text("Vault root configuration destination")
                }
            }
        }
        composeRule.onNodeWithText("Open vault").performClick()
        composeRule.onNodeWithText("Vault navigation destination").assertIsDisplayed()
        composeRule.onNodeWithText("Configure root").performClick()
        composeRule.onNodeWithText("Vault root configuration destination").assertIsDisplayed()
    }

    @Test
    fun initialUnconfiguredStateOffersTheExplicitConfigurationRoute() {
        val session = FakeSessionManager(authenticated = true)
        val repository = FakeVaultRepository(VaultStatus.RootNotSelected)
        var configurationOpened = false
        composeRule.setContent {
            VaultScreen(
                repository = repository,
                indexRepository = FakeIndexRepository(),
                organizationRepository = FakeOrganizationRepository(),
                importRepository = FakeImportRepository(),
                sourceSelectionResult = null,
                onConsumeSourceSelectionResult = {},
                onChooseSource = {},
                sessionManager = session,
                rootSelectionResult = null,
                onConsumeRootSelectionResult = {},
                onConfigureRoot = { configurationOpened = true },
                onBack = {},
                onReturnHomeForAuthentication = {},
            )
        }

        composeRule.onNodeWithText("No external vault folder has been selected.").assertIsDisplayed()
        composeRule.onNodeWithText("Choose external folder").performClick()
        composeRule.runOnIdle { assertEquals(true, configurationOpened) }
        composeRule.runOnIdle {
            assertEquals(1, repository.inspectCalls)
            assertEquals(0, session.primaryAuthCalls)
            assertEquals(0, session.biometricAuthCalls)
        }
    }

    @Test
    fun readyVaultStateIsRenderedExplicitly() {
        val readySession = FakeSessionManager(authenticated = true)
        composeRule.setContent {
            VaultScreen(
                repository = FakeVaultRepository(VaultStatus.Ready(VaultId("00112233445566778899aabbccddeeff"))),
                indexRepository = FakeIndexRepository(),
                organizationRepository = FakeOrganizationRepository(),
                importRepository = FakeImportRepository(),
                sourceSelectionResult = null,
                onConsumeSourceSelectionResult = {},
                onChooseSource = {},
                sessionManager = readySession,
                rootSelectionResult = null,
                onConsumeRootSelectionResult = {},
                onConfigureRoot = {},
                onBack = {},
                onReturnHomeForAuthentication = {},
            )
        }
        composeRule.onNodeWithText("0 authenticated items").assertIsDisplayed()
    }

    @Test
    fun accessDeniedIsRenderedWithoutBecomingAnEmptyVault() {
        composeRule.setContent {
            VaultScreen(
                repository = FakeVaultRepository(VaultStatus.AccessDenied),
                indexRepository = FakeIndexRepository(),
                organizationRepository = FakeOrganizationRepository(),
                importRepository = FakeImportRepository(),
                sourceSelectionResult = null,
                onConsumeSourceSelectionResult = {},
                onChooseSource = {},
                sessionManager = FakeSessionManager(authenticated = true),
                rootSelectionResult = null,
                onConsumeRootSelectionResult = {},
                onConfigureRoot = {},
                onBack = {},
                onReturnHomeForAuthentication = {},
            )
        }
        composeRule.onNodeWithText(
            "Android access to the selected folder is missing or was revoked. The vault was not treated as empty.",
        ).assertIsDisplayed()
    }

    @Test
    fun initializationFailureIsShownWithoutBeingCollapsedIntoAnEmptyVault() {
        val repository = FakeVaultRepository(VaultStatus.NotInitialized).apply {
            initializationResult = VaultInitializationResult.Failed(VaultInitializationFailure.DIRECTORY_CREATION)
        }
        composeRule.setContent {
            VaultScreen(
                repository = repository,
                indexRepository = FakeIndexRepository(),
                organizationRepository = FakeOrganizationRepository(),
                importRepository = FakeImportRepository(),
                sourceSelectionResult = null,
                onConsumeSourceSelectionResult = {},
                onChooseSource = {},
                sessionManager = FakeSessionManager(authenticated = true),
                rootSelectionResult = null,
                onConsumeRootSelectionResult = {},
                onConfigureRoot = {},
                onBack = {},
                onReturnHomeForAuthentication = {},
            )
        }
        composeRule.onNodeWithText("Initialize vault here").performClick()
        composeRule.onNodeWithText(
            "Vault initialization failed (DIRECTORY_CREATION). The result was not reported as an empty or ready vault.",
        ).assertIsDisplayed()
    }

    @Test
    fun rootConfigurationRequiresTheExistingSessionAndAddsNoAuthenticationFlow() {
        val session = FakeSessionManager(authenticated = false)
        var pickerOpened = false
        composeRule.setContent {
            VaultRootConfigurationScreen(
                sessionManager = session,
                selectionResult = VaultRootSelectionResult.DIFFERENT_ROOT_ALREADY_SELECTED,
                onChooseRoot = { pickerOpened = true },
                onBack = {},
                onReturnHomeForAuthentication = {},
            )
        }

        composeRule.onNodeWithText("Authenticate from Nivara Home before configuring vault storage.").assertIsDisplayed()
        composeRule.onNodeWithText("A different folder is already bound; the saved location was not replaced.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Choose or reconnect folder").assertCountEquals(0)
        composeRule.runOnIdle {
            assertEquals(false, pickerOpened)
            assertEquals(0, session.primaryAuthCalls)
            assertEquals(0, session.biometricAuthCalls)
        }
    }

    private class FakeIndexRepository : VaultIndexRepository {
        override suspend fun inspect(vaultId: VaultId): VaultIndexRead = VaultIndexRead.Ready(0, emptyList())
        override suspend fun initializeEmpty(
            vaultId: VaultId,
            authorizationCheckpoint: suspend () -> Boolean,
        ) = VaultIndexInitializationResult.Initialized
        override suspend fun listItems(vaultId: VaultId): VaultIndexRead = inspect(vaultId)
        override suspend fun addItem(
            vaultId: VaultId,
            item: com.ashishkumar.nivara.domain.vault.content.VaultItem,
            authorizationCheckpoint: suspend () -> Boolean,
        ) = VaultIndexWriteResult.Failed
    }

    private class FakeOrganizationRepository : VaultOrganizationRepository {
        override suspend fun inspect(vaultId: VaultId) =
            VaultOrganizationRead.Ready(VaultOrganizationSnapshot(0, emptyList()))
        override suspend fun createAlbum(vaultId: VaultId, name: String,
            authorizationCheckpoint: suspend () -> Boolean) = VaultAlbumMutationResult.OrganizationUnavailable
        override suspend fun renameAlbum(vaultId: VaultId, albumId: VaultAlbumId, name: String,
            authorizationCheckpoint: suspend () -> Boolean) = VaultAlbumMutationResult.OrganizationUnavailable
        override suspend fun deleteAlbum(vaultId: VaultId, albumId: VaultAlbumId,
            authorizationCheckpoint: suspend () -> Boolean) = VaultAlbumMutationResult.OrganizationUnavailable
        override suspend fun addMembership(vaultId: VaultId, albumId: VaultAlbumId, itemId: VaultItemId,
            authorizationCheckpoint: suspend () -> Boolean) = VaultAlbumMutationResult.OrganizationUnavailable
        override suspend fun removeMembership(vaultId: VaultId, albumId: VaultAlbumId, itemId: VaultItemId,
            authorizationCheckpoint: suspend () -> Boolean) = VaultAlbumMutationResult.OrganizationUnavailable
    }

    private class FakeImportRepository : VaultImportRepository {
        override fun discard(sourceId: VaultSourceSelectionId) = Unit
        override suspend fun importDocument(
            sourceId: VaultSourceSelectionId,
            vaultId: VaultId,
            authorizationCheckpoint: suspend () -> Boolean,
            onProgress: (Long, Long?) -> Unit,
        ) = VaultImportResult.Failed
    }

    private class FakeVaultRepository(initialStatus: VaultStatus) : VaultRepository {
        private var status = initialStatus
        var inspectCalls = 0
        var initializationResult: VaultInitializationResult = VaultInitializationResult.AlreadyInitialized

        override suspend fun inspect(): VaultStatus {
            inspectCalls++
            return status
        }

        override suspend fun initialize(): VaultInitializationResult {
            if (initializationResult is VaultInitializationResult.Initialized ||
                initializationResult == VaultInitializationResult.AlreadyInitialized
            ) status = VaultStatus.Ready(VaultId("00112233445566778899aabbccddeeff"))
            return initializationResult
        }
    }

    private class FakeSessionManager(authenticated: Boolean) : SessionManager {
        private val mutableSession = MutableStateFlow<SessionState>(
            if (authenticated) {
                SessionState.Authenticated(AuthenticatedSession(AuthenticationSource.PRIMARY, 0, Long.MAX_VALUE))
            } else {
                SessionState.Unauthenticated
            },
        )
        override val sessionState: StateFlow<SessionState> = mutableSession
        var primaryAuthCalls = 0
        var biometricAuthCalls = 0

        override suspend fun currentState(): SessionState = mutableSession.value
        override suspend fun mayAccessSensitiveContent(): Boolean = mutableSession.value is SessionState.Authenticated
        override suspend fun lockNow() { mutableSession.value = SessionState.Unauthenticated }
        override suspend fun authenticatePrimary(
            authenticate: suspend () -> AuthenticationResult,
        ): SessionAuthenticationCompletion<AuthenticationResult> {
            primaryAuthCalls++
            return SessionAuthenticationCompletion(authenticate(), sessionEstablished = false)
        }
        override suspend fun authenticateBiometric(
            authenticate: suspend () -> BiometricAuthenticationResult,
        ): SessionAuthenticationCompletion<BiometricAuthenticationResult> {
            biometricAuthCalls++
            return SessionAuthenticationCompletion(authenticate(), sessionEstablished = false)
        }
    }
}
