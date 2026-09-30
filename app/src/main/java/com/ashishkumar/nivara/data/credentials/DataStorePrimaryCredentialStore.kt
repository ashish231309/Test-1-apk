package com.ashishkumar.nivara.data.credentials

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ashishkumar.nivara.domain.credentials.AttemptState
import com.ashishkumar.nivara.domain.credentials.AttemptThrottlePolicy
import com.ashishkumar.nivara.domain.credentials.CredentialPersistenceFailure
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialStore
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import com.ashishkumar.nivara.domain.credentials.StoredPrimaryCredential
import com.ashishkumar.nivara.domain.security.KeyProtection
import com.ashishkumar.nivara.domain.security.KdfParameters
import com.ashishkumar.nivara.domain.security.SecurityFailure
import com.ashishkumar.nivara.domain.security.WrappedKeyEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.Base64

/** Preferences DataStore persists only the selected type, KDF metadata/salt, wrapped verifier and attempt state. */
class DataStorePrimaryCredentialStore(
    private val dataStore: DataStore<Preferences>,
) : PrimaryCredentialStore {
    override suspend fun load(): StoredPrimaryCredential? = try {
        readCredential(dataStore.data.first())
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        if (failure is CredentialPersistenceFailure) throw failure
        throw CredentialPersistenceFailure()
    }

    override suspend fun installIfAbsent(credential: StoredPrimaryCredential): Boolean = try {
        dataStore.edit { preferences ->
            val typeExists = preferences[TYPE] != null
            val otherCredentialDataExists = hasPartialCredentialData(preferences)
            if (typeExists) {
                false
            } else {
                if (otherCredentialDataExists) throw CredentialPersistenceFailure()
                writeCredential(preferences, credential)
                preferences[FAILURES] = 0
                preferences[BLOCKED_UNTIL] = 0L
                true
            }
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        if (failure is CredentialPersistenceFailure) throw failure
        throw CredentialPersistenceFailure()
    }

    override suspend fun replace(credential: StoredPrimaryCredential) {
        try {
            dataStore.edit { preferences ->
                writeCredential(preferences, credential)
                preferences[FAILURES] = 0
                preferences[BLOCKED_UNTIL] = 0L
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (failure is CredentialPersistenceFailure) throw failure
            throw CredentialPersistenceFailure()
        }
    }

    override suspend fun attempts(): AttemptState = try {
        readAttempts(dataStore.data.first())
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw CredentialPersistenceFailure()
    }

    override suspend fun recordFailure(nowEpochMillis: Long): AttemptState = try {
        dataStore.edit { preferences ->
            val previous = readAttempts(preferences)
            val nextCount = if (previous.failedAttempts == Int.MAX_VALUE) Int.MAX_VALUE
            else previous.failedAttempts + 1
            val delay = AttemptThrottlePolicy.delayForFailure(nextCount)
            val blockedUntil = if (delay == 0L) 0L
            else if (nowEpochMillis > Long.MAX_VALUE - delay) Long.MAX_VALUE
            else nowEpochMillis + delay
            preferences[FAILURES] = nextCount
            preferences[BLOCKED_UNTIL] = blockedUntil
            AttemptState(nextCount, blockedUntil)
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw CredentialPersistenceFailure()
    }

    override suspend fun resetAttempts() {
        try {
            dataStore.edit { preferences ->
                preferences[FAILURES] = 0
                preferences[BLOCKED_UNTIL] = 0L
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw CredentialPersistenceFailure()
        }
    }

    private fun readCredential(preferences: Preferences): StoredPrimaryCredential? {
        val storageType = preferences[TYPE]
        if (storageType == null) {
            if (hasPartialCredentialData(preferences)) throw CredentialPersistenceFailure()
            return null
        }
        val type = PrimaryCredentialType.fromStorageValue(storageType) ?: throw CredentialPersistenceFailure()
        val schemaVersion = preferences[RECORD_VERSION] ?: throw CredentialPersistenceFailure()
        if (schemaVersion != CURRENT_RECORD_VERSION) throw CredentialPersistenceFailure()
        val version = preferences[KDF_VERSION] ?: throw CredentialPersistenceFailure()
        val iterations = preferences[KDF_ITERATIONS] ?: throw CredentialPersistenceFailure()
        val saltLength = preferences[SALT_LENGTH] ?: throw CredentialPersistenceFailure()
        val outputLength = preferences[OUTPUT_LENGTH] ?: throw CredentialPersistenceFailure()
        val encodedSalt = preferences[SALT] ?: throw CredentialPersistenceFailure()
        val encodedVerifier = preferences[WRAPPED_VERIFIER] ?: throw CredentialPersistenceFailure()
        val parameters = try {
            KdfParameters(version, iterations, saltLength, outputLength)
        } catch (failure: SecurityFailure) {
            throw CredentialPersistenceFailure()
        }
        val salt = decode(encodedSalt)
        val verifierBytes = decode(encodedVerifier)
        try {
            if (salt.size != parameters.saltBytes) throw CredentialPersistenceFailure()
            val wrapped = WrappedKeyEnvelope.decode(verifierBytes)
            if (wrapped.protection != KeyProtection.CREDENTIAL_DERIVED) throw CredentialPersistenceFailure()
            return StoredPrimaryCredential(type, parameters, salt, verifierBytes)
        } catch (failure: SecurityFailure) {
            throw CredentialPersistenceFailure()
        } finally {
            salt.fill(0)
            verifierBytes.fill(0)
        }
    }

    private fun writeCredential(preferences: MutablePreferences, credential: StoredPrimaryCredential) {
        val salt = credential.salt
        val verifier = credential.wrappedVerifier
        try {
            if (salt.size != credential.kdf.saltBytes) throw CredentialPersistenceFailure()
            val parsedVerifier = WrappedKeyEnvelope.decode(verifier)
            if (parsedVerifier.protection != KeyProtection.CREDENTIAL_DERIVED) {
                throw CredentialPersistenceFailure()
            }
            preferences[TYPE] = credential.type.storageValue
            preferences[RECORD_VERSION] = CURRENT_RECORD_VERSION
            preferences[KDF_VERSION] = credential.kdf.version
            preferences[KDF_ITERATIONS] = credential.kdf.iterations
            preferences[SALT_LENGTH] = credential.kdf.saltBytes
            preferences[OUTPUT_LENGTH] = credential.kdf.outputBytes
            preferences[SALT] = Base64.getEncoder().encodeToString(salt)
            preferences[WRAPPED_VERIFIER] = Base64.getEncoder().encodeToString(verifier)
        } catch (failure: SecurityFailure) {
            throw CredentialPersistenceFailure()
        } finally {
            salt.fill(0)
            verifier.fill(0)
        }
    }

    private fun readAttempts(preferences: Preferences): AttemptState {
        val failures = preferences[FAILURES] ?: 0
        val blockedUntil = preferences[BLOCKED_UNTIL] ?: 0L
        return try {
            AttemptState(failures, blockedUntil)
        } catch (failure: IllegalArgumentException) {
            throw CredentialPersistenceFailure()
        }
    }

    private fun hasPartialCredentialData(preferences: Preferences): Boolean =
        preferences[RECORD_VERSION] != null || preferences[KDF_VERSION] != null || preferences[KDF_ITERATIONS] != null ||
            preferences[SALT_LENGTH] != null || preferences[OUTPUT_LENGTH] != null ||
            preferences[SALT] != null || preferences[WRAPPED_VERIFIER] != null

    private fun decode(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (failure: IllegalArgumentException) {
        throw CredentialPersistenceFailure()
    }

    private companion object {
        const val CURRENT_RECORD_VERSION = 1
        val TYPE = stringPreferencesKey("primary_type")
        val RECORD_VERSION = intPreferencesKey("credential_record_version")
        val KDF_VERSION = intPreferencesKey("kdf_version")
        val KDF_ITERATIONS = intPreferencesKey("kdf_iterations")
        val SALT_LENGTH = intPreferencesKey("kdf_salt_bytes")
        val OUTPUT_LENGTH = intPreferencesKey("kdf_output_bytes")
        val SALT = stringPreferencesKey("kdf_salt_base64")
        val WRAPPED_VERIFIER = stringPreferencesKey("credential_verifier_envelope_base64")
        val FAILURES = intPreferencesKey("failed_attempt_count")
        val BLOCKED_UNTIL = longPreferencesKey("temporarily_blocked_until_epoch_ms")
    }
}
