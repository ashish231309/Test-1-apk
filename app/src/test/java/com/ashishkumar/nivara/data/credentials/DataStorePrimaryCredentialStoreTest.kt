package com.ashishkumar.nivara.data.credentials

import com.ashishkumar.nivara.domain.security.TimeProvider

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ashishkumar.nivara.data.security.AesGcmKeyWrappingService
import com.ashishkumar.nivara.data.security.JcaAesGcmEncryption
import com.ashishkumar.nivara.data.security.JcaCredentialKeyDeriver
import com.ashishkumar.nivara.data.security.JcaSecureRandomSource
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.credentials.CredentialServiceStatus
import com.ashishkumar.nivara.domain.credentials.CredentialChangeResult
import com.ashishkumar.nivara.domain.credentials.DefaultPrimaryCredentialService
import com.ashishkumar.nivara.domain.credentials.EnrollmentResult
import com.ashishkumar.nivara.domain.credentials.PatternCanonicalizer
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class DataStorePrimaryCredentialStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun credentialConfigurationSurvivesStoreRecreationWithoutPlaintextCredential() = runBlocking {
        val file = File(temporaryFolder.newFolder(), "credentials.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val firstService = service(store(file, firstScope))
        val password = "Persistent test passphrase 347"
        assertEquals(
            EnrollmentResult.Enrolled(PrimaryCredentialType.PASSWORD),
            firstService.enroll(
                PrimaryCredentialType.PASSWORD,
                password.toCharArray(),
                password.toCharArray(),
            ),
        )
        assertEquals(AuthenticationResult.Failed, firstService.authenticate("A wrong test passphrase".toCharArray()))
        firstJob.cancel()
        firstJob.join()

        val secondJob = SupervisorJob()
        val secondScope = CoroutineScope(secondJob + Dispatchers.IO)
        try {
            val secondStore = store(file, secondScope)
            val secondService = service(secondStore)
            assertEquals(
                CredentialServiceStatus.Configured(PrimaryCredentialType.PASSWORD),
                secondService.status(),
            )
            val restored = requireNotNull(secondStore.load())
            assertEquals(1, restored.kdf.version)
            assertEquals(600_000, restored.kdf.iterations)
            val restoredSalt = restored.salt
            try {
                assertEquals(16, restoredSalt.size)
            } finally {
                restoredSalt.fill(0)
            }
            assertEquals(1, secondStore.attempts().failedAttempts)
            assertEquals(
                AuthenticationResult.Authenticated(PrimaryCredentialType.PASSWORD),
                secondService.authenticate(password.toCharArray()),
            )
            assertEquals(0, secondStore.attempts().failedAttempts)
            val persisted = file.readBytes()
            assertFalse(contains(persisted, password.toByteArray(StandardCharsets.UTF_8)))
            assertTrue(persisted.isNotEmpty())
        } finally {
            secondJob.cancel()
            secondJob.join()
        }
    }

    @Test
    fun credentialReplacementPersistsOnlyOneActiveType() = runBlocking {
        val file = File(temporaryFolder.newFolder(), "replace.preferences_pb")
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val store = store(file, scope)
            val service = service(store)
            service.enroll(PrimaryCredentialType.PIN, "482951".toCharArray(), "482951".toCharArray())
            val old = requireNotNull(store.load())
            assertEquals(1, old.kdf.version)
            assertEquals(600_000, old.kdf.iterations)
            val salt = old.salt
            assertEquals(16, salt.size)

            assertEquals(
                CredentialChangeResult.Changed(PrimaryCredentialType.PATTERN),
                service.changePrimary(
                    "482951".toCharArray(),
                    PrimaryCredentialType.PATTERN,
                    PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 4, 7)),
                    PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 4, 7)),
                ),
            )
            assertEquals(CredentialServiceStatus.Configured(PrimaryCredentialType.PATTERN), service.status())
            val newSalt = requireNotNull(store.load()).salt
            try {
                assertFalse(salt.contentEquals(newSalt))
            } finally {
                salt.fill(0)
                newSalt.fill(0)
            }
            assertEquals(AuthenticationResult.Failed, service.authenticate("482951".toCharArray()))
            assertEquals(
                AuthenticationResult.Authenticated(PrimaryCredentialType.PATTERN),
                service.authenticate(PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 4, 7))),
            )
            val persisted = file.readBytes()
            assertFalse(contains(persisted, "482951".toByteArray(StandardCharsets.UTF_8)))
            assertFalse(contains(persisted, "010400010407".toByteArray(StandardCharsets.US_ASCII)))
            assertFalse(contains(persisted, byteArrayOf(0, 1, 4, 7)))
        } finally {
            job.cancel()
            job.join()
        }
    }

    private fun store(file: File, scope: CoroutineScope): DataStorePrimaryCredentialStore =
        DataStorePrimaryCredentialStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        )

    private fun service(store: DataStorePrimaryCredentialStore): DefaultPrimaryCredentialService {
        val random = JcaSecureRandomSource()
        return DefaultPrimaryCredentialService(
            store = store,
            keyDeriver = JcaCredentialKeyDeriver(random),
            keyWrapping = AesGcmKeyWrappingService(JcaAesGcmEncryption(random)),
            random = random,
            clock = TimeProvider { 100_000L },
        )
    }

    private fun contains(source: ByteArray, value: ByteArray): Boolean {
        if (value.isEmpty() || value.size > source.size) return false
        return (0..source.size - value.size).any { start ->
            value.indices.all { offset -> source[start + offset] == value[offset] }
        }
    }
}
