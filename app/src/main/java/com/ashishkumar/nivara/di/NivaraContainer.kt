package com.ashishkumar.nivara.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.ashishkumar.nivara.data.credentials.DataStorePrimaryCredentialStore
import com.ashishkumar.nivara.data.credentials.SystemCredentialClock
import com.ashishkumar.nivara.data.security.AndroidKeyStoreKeyManager
import com.ashishkumar.nivara.data.security.AesGcmKeyWrappingService
import com.ashishkumar.nivara.data.security.JcaAesGcmEncryption
import com.ashishkumar.nivara.data.security.JcaCredentialKeyDeriver
import com.ashishkumar.nivara.data.security.JcaSecureRandomSource
import com.ashishkumar.nivara.domain.credentials.DefaultPrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialStore
import com.ashishkumar.nivara.domain.security.AuthenticatedEncryption
import com.ashishkumar.nivara.domain.security.CredentialKeyDeriver
import com.ashishkumar.nivara.domain.security.DeviceKeyStore
import com.ashishkumar.nivara.domain.security.KeyWrappingService
import com.ashishkumar.nivara.domain.security.SecureRandomSource
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
}

class DefaultNivaraContainer(context: Context) : NivaraContainer {
    private val applicationContext = context.applicationContext

    override val secureRandom: SecureRandomSource by lazy { JcaSecureRandomSource() }
    override val encryption: AuthenticatedEncryption by lazy { JcaAesGcmEncryption(secureRandom) }
    override val keyWrapping: KeyWrappingService by lazy { AesGcmKeyWrappingService(encryption) }
    override val credentialKeyDeriver: CredentialKeyDeriver by lazy { JcaCredentialKeyDeriver(secureRandom) }
    override val deviceKeyStore: DeviceKeyStore by lazy { AndroidKeyStoreKeyManager() }

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
            clock = SystemCredentialClock(),
        )
    }

    private companion object {
        const val CREDENTIAL_STORE_FILE = "nivara_primary_credential.preferences_pb"
    }
}
