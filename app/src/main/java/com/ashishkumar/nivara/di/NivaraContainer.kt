package com.ashishkumar.nivara.di

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.datastore.core.DataStore
import androidx.fragment.app.FragmentActivity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.ashishkumar.nivara.data.credentials.DataStorePrimaryCredentialStore
import com.ashishkumar.nivara.data.applock.AndroidAppLockMonitoringController
import com.ashishkumar.nivara.data.applock.AndroidForegroundApplicationDetector
import com.ashishkumar.nivara.data.applock.AndroidOverlayCapabilityRepository
import com.ashishkumar.nivara.data.applock.AndroidAppLockPresentationController
import com.ashishkumar.nivara.data.applock.SharedPreferencesProtectedApplicationRepository
import com.ashishkumar.nivara.data.app.AndroidApplicationRepository
import com.ashishkumar.nivara.data.app.AndroidApplicationIconProvider
import com.ashishkumar.nivara.data.apphide.AndroidHiddenApplicationRepository
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationRepository
import com.ashishkumar.nivara.data.permissions.AndroidUsageAccessRepository
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.applock.AppLockMonitor
import com.ashishkumar.nivara.domain.applock.AppLockMonitoringController
import com.ashishkumar.nivara.domain.applock.DefaultAppLockMonitor
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationRepository
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.data.credentials.SystemCredentialClock
import com.ashishkumar.nivara.data.biometrics.AndroidBiometricPromptPlatform
import com.ashishkumar.nivara.data.biometrics.DataStoreBiometricStateStore
import com.ashishkumar.nivara.data.security.AndroidKeyStoreKeyManager
import com.ashishkumar.nivara.data.security.AesGcmKeyWrappingService
import com.ashishkumar.nivara.data.security.JcaAesGcmEncryption
import com.ashishkumar.nivara.data.security.JcaCredentialKeyDeriver
import com.ashishkumar.nivara.data.security.JcaSecureRandomSource
import com.ashishkumar.nivara.data.vault.AndroidVaultLocationStore
import com.ashishkumar.nivara.data.vault.DefaultVaultRepository
import com.ashishkumar.nivara.data.vault.DefaultVaultRecoveryRepository
import com.ashishkumar.nivara.data.vault.AndroidVaultKeyAccessStore
import com.ashishkumar.nivara.data.vault.SafVaultStorage
import com.ashishkumar.nivara.data.vault.SafVaultContentStorage
import com.ashishkumar.nivara.data.vault.DefaultVaultIndexRepository
import com.ashishkumar.nivara.data.vault.DefaultVaultOrganizationRepository
import com.ashishkumar.nivara.data.vault.DefaultVaultImportRepository
import com.ashishkumar.nivara.data.vault.AndroidVaultContentPresentationRepository
import com.ashishkumar.nivara.domain.vault.content.VaultContentPresentationGateway
import com.ashishkumar.nivara.data.vault.AndroidVaultSourceDocumentProvider
import com.ashishkumar.nivara.data.vault.PendingSourceDocuments
import com.ashishkumar.nivara.data.vault.VaultRootSelectionHandler
import com.ashishkumar.nivara.domain.vault.VaultRepository
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRepository
import com.ashishkumar.nivara.domain.vault.VaultRecoveryCryptography
import com.ashishkumar.nivara.domain.vault.content.VaultContentCrypto
import com.ashishkumar.nivara.domain.vault.content.VaultContentStorage
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRepository
import com.ashishkumar.nivara.domain.vault.content.VaultImportRepository
import com.ashishkumar.nivara.domain.credentials.DefaultPrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialStore
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticator
import com.ashishkumar.nivara.domain.biometrics.BiometricStateStore
import com.ashishkumar.nivara.domain.biometrics.DefaultBiometricAuthenticator
import com.ashishkumar.nivara.domain.security.AuthenticatedEncryption
import com.ashishkumar.nivara.domain.security.CredentialKeyDeriver
import com.ashishkumar.nivara.domain.security.DeviceKeyStore
import com.ashishkumar.nivara.domain.security.KeyWrappingService
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.security.session.DefaultSessionManager
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionTimeoutPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Small hand-written dependency container; no DI framework is needed for the current single-module app. */
interface NivaraContainer {
    val secureRandom: SecureRandomSource
    val encryption: AuthenticatedEncryption
    val keyWrapping: KeyWrappingService
    val credentialKeyDeriver: CredentialKeyDeriver
    val deviceKeyStore: DeviceKeyStore
    val credentialStore: PrimaryCredentialStore
    val primaryCredentialService: PrimaryCredentialService
    val sessionManager: SessionManager
    val applicationRepository: ApplicationRepository
    val applicationIconProvider: AndroidApplicationIconProvider
    val hiddenApplicationRepository: HiddenApplicationRepository
    val vaultRepository: VaultRepository
    val vaultRecoveryRepository: VaultRecoveryRepository
    val vaultIndexRepository: VaultIndexRepository
    val vaultOrganizationRepository: VaultOrganizationRepository
    val vaultImportRepository: VaultImportRepository
    val vaultContentPresentationGateway: VaultContentPresentationGateway
    val pendingVaultSources: PendingSourceDocuments
    val vaultRootSelectionHandler: VaultRootSelectionHandler
    val usageAccessRepository: UsageAccessRepository
    val overlayCapabilityRepository: OverlayCapabilityRepository
    val protectedApplicationRepository: ProtectedApplicationRepository
    val appLockMonitor: AppLockMonitor
    val appLockMonitoringController: AppLockMonitoringController
    val appLockPresentationController: AndroidAppLockPresentationController
    fun biometricAuthenticator(activity: FragmentActivity): BiometricAuthenticator
}

class DefaultNivaraContainer(context: Context) : NivaraContainer {
    private val applicationContext = context.applicationContext

    override val secureRandom: SecureRandomSource by lazy { JcaSecureRandomSource() }
    override val encryption: AuthenticatedEncryption by lazy { JcaAesGcmEncryption(secureRandom) }
    override val keyWrapping: KeyWrappingService by lazy { AesGcmKeyWrappingService(encryption) }
    override val credentialKeyDeriver: CredentialKeyDeriver by lazy { JcaCredentialKeyDeriver(secureRandom) }
    override val deviceKeyStore: DeviceKeyStore by lazy { AndroidKeyStoreKeyManager() }
    override val applicationRepository: ApplicationRepository by lazy {
        AndroidApplicationRepository(applicationContext)
    }
    override val applicationIconProvider: AndroidApplicationIconProvider by lazy {
        AndroidApplicationIconProvider(applicationContext)
    }
    override val hiddenApplicationRepository: HiddenApplicationRepository by lazy {
        AndroidHiddenApplicationRepository.create(applicationContext)
    }
    private val vaultLocationStore by lazy { AndroidVaultLocationStore(applicationContext) }
    override val vaultRootSelectionHandler: VaultRootSelectionHandler by lazy {
        VaultRootSelectionHandler(applicationContext, vaultLocationStore)
    }
    private val safVaultStorage by lazy { SafVaultStorage(applicationContext, vaultLocationStore) }
    private val vaultKeyAccessStore by lazy { AndroidVaultKeyAccessStore(applicationContext) }
    override val vaultRepository: VaultRepository by lazy {
        DefaultVaultRepository(
            storage = safVaultStorage,
            encryption = encryption,
            keyWrapping = keyWrapping,
            deviceKeyStore = deviceKeyStore,
            random = secureRandom,
            keyAccessStore = vaultKeyAccessStore,
        )
    }
    override val vaultRecoveryRepository: VaultRecoveryRepository by lazy {
        DefaultVaultRecoveryRepository(
            storage = safVaultStorage,
            cryptography = vaultRepository as VaultRecoveryCryptography,
            indexRepository = vaultIndexRepository,
            organizationRepository = vaultOrganizationRepository,
            random = secureRandom,
        )
    }
    private val safVaultContentStorage by lazy {
        SafVaultContentStorage(applicationContext, vaultLocationStore, secureRandom)
    }
    private val vaultContentCrypto by lazy { vaultRepository as VaultContentCrypto }
    override val vaultIndexRepository: VaultIndexRepository by lazy {
        DefaultVaultIndexRepository(safVaultContentStorage, vaultContentCrypto)
    }
    override val vaultOrganizationRepository: VaultOrganizationRepository by lazy {
        DefaultVaultOrganizationRepository(safVaultContentStorage, vaultContentCrypto, vaultIndexRepository, secureRandom)
    }
    override val pendingVaultSources: PendingSourceDocuments by lazy { PendingSourceDocuments(secureRandom) }
    override val vaultImportRepository: VaultImportRepository by lazy {
        DefaultVaultImportRepository(
            sources = AndroidVaultSourceDocumentProvider(applicationContext, pendingVaultSources),
            objects = safVaultContentStorage,
            crypto = vaultContentCrypto,
            index = vaultIndexRepository,
            random = secureRandom,
        )
    }
    override val vaultContentPresentationGateway: VaultContentPresentationGateway by lazy {
        AndroidVaultContentPresentationRepository(
            storage = safVaultContentStorage,
            crypto = vaultContentCrypto,
            index = vaultIndexRepository,
            sessions = sessionManager,
            random = secureRandom,
        )
    }
    override val usageAccessRepository: UsageAccessRepository by lazy {
        AndroidUsageAccessRepository(applicationContext)
    }
    override val overlayCapabilityRepository: OverlayCapabilityRepository by lazy {
        AndroidOverlayCapabilityRepository(applicationContext)
    }
    override val protectedApplicationRepository: ProtectedApplicationRepository by lazy {
        SharedPreferencesProtectedApplicationRepository(applicationContext)
    }

    private val appLockMonitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val appLockMonitor: AppLockMonitor by lazy {
        DefaultAppLockMonitor(
            usageAccessRepository = usageAccessRepository,
            protectedApplicationRepository = protectedApplicationRepository,
            foregroundDetector = AndroidForegroundApplicationDetector(applicationContext),
            sessionManager = sessionManager,
            nivaraPackageName = applicationContext.packageName,
            scope = appLockMonitorScope,
        )
    }
    override val appLockMonitoringController: AppLockMonitoringController by lazy {
        AndroidAppLockMonitoringController(
            context = applicationContext,
            usageAccessRepository = usageAccessRepository,
            overlayCapabilityRepository = overlayCapabilityRepository,
            protectedApplicationRepository = protectedApplicationRepository,
            appLockMonitor = appLockMonitor,
        )
    }
    override val appLockPresentationController: AndroidAppLockPresentationController by lazy {
        AndroidAppLockPresentationController(
            context = applicationContext,
            monitor = appLockMonitor,
            overlayCapabilityRepository = overlayCapabilityRepository,
            protectedApplicationRepository = protectedApplicationRepository,
            applicationRepository = applicationRepository,
            primaryCredentialService = primaryCredentialService,
            sessionManager = sessionManager,
        )
    }

    private val biometricPreferencesDataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(applicationContext.noBackupFilesDir, BIOMETRIC_STORE_FILE) },
        )
    }

    private val biometricStateStore: BiometricStateStore by lazy {
        DataStoreBiometricStateStore(biometricPreferencesDataStore)
    }

    private val credentialClock = SystemCredentialClock()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val sessionManager: SessionManager by lazy {
        DefaultSessionManager(
            timeProvider = credentialClock,
            timeoutPolicy = SessionTimeoutPolicy.DEFAULT,
            applicationScope = applicationScope,
        )
    }

    private val preferenceDataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = {
                File(applicationContext.noBackupFilesDir, CREDENTIAL_STORE_FILE)
            },
        )
    }

    override val credentialStore: PrimaryCredentialStore by lazy {
        DataStorePrimaryCredentialStore(preferenceDataStore)
    }

    override val primaryCredentialService: PrimaryCredentialService by lazy {
        DefaultPrimaryCredentialService(
            store = credentialStore,
            keyDeriver = credentialKeyDeriver,
            keyWrapping = keyWrapping,
            random = secureRandom,
            clock = credentialClock,
        )
    }

    override fun biometricAuthenticator(activity: FragmentActivity): BiometricAuthenticator =
        DefaultBiometricAuthenticator(
            primaryCredentials = primaryCredentialService,
            platform = AndroidBiometricPromptPlatform(
                activity = activity,
                manager = BiometricManager.from(activity),
                random = secureRandom,
            ),
            stateStore = biometricStateStore,
            clock = credentialClock,
        )

    private companion object {
        const val CREDENTIAL_STORE_FILE = "nivara_primary_credential.preferences_pb"
        const val BIOMETRIC_STORE_FILE = "nivara_biometric_state.preferences_pb"
    }
}
