package com.ashishkumar.nivara.di

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.datastore.core.DataStore
import androidx.fragment.app.FragmentActivity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.ashishkumar.nivara.data.credentials.DataStorePrimaryCredentialStore
import com.ashishkumar.nivara.data.app.AndroidApplicationRepository
import com.ashishkumar.nivara.data.permissions.AndroidUsageAccessRepository
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.data.credentials.SystemCredentialClock
import com.ashishkumar.nivara.data.biometrics.AndroidBiometricPromptPlatform
import com.ashishkumar.nivara.data.biometrics.DataStoreBiometricStateStore
import com.ashishkumar.nivara.data.security.AndroidKeyStoreKeyManager
import com.ashishkumar.nivara.data.security.AesGcmKeyWrappingService
import com.ashishkumar.nivara.data.security.JcaAesGcmEncryption
import com.ashishkumar.nivara.data.security.JcaCredentialKeyDeriver
import com.ashishkumar.nivara.data.security.JcaSecureRandomSource
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
    val usageAccessRepository: UsageAccessRepository
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
    override val usageAccessRepository: UsageAccessRepository by lazy {
        AndroidUsageAccessRepository(applicationContext)
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
