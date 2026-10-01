package com.ashishkumar.nivara.data.biometrics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ashishkumar.nivara.domain.biometrics.BiometricAttemptState
import com.ashishkumar.nivara.domain.biometrics.BiometricPersistenceFailure
import com.ashishkumar.nivara.domain.biometrics.BiometricRecordState
import com.ashishkumar.nivara.domain.biometrics.BiometricStateStore
import com.ashishkumar.nivara.domain.biometrics.BiometricThrottlePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Stores only enabled/invalidated metadata and throttling counters; never biometric/key material. */
class DataStoreBiometricStateStore(
    private val dataStore: DataStore<Preferences>,
) : BiometricStateStore {
    override suspend fun recordState(): BiometricRecordState = try {
        when (dataStore.data.first()[STATE] ?: STATE_DISABLED) {
            STATE_DISABLED -> BiometricRecordState.DISABLED
            STATE_ENABLED -> BiometricRecordState.ENABLED
            STATE_INVALIDATED -> BiometricRecordState.INVALIDATED
            else -> throw BiometricPersistenceFailure()
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        if (failure is BiometricPersistenceFailure) throw failure
        throw BiometricPersistenceFailure()
    }

    override suspend fun setState(state: BiometricRecordState) {
        try {
            dataStore.edit { preferences ->
                preferences[STATE] = when (state) {
                    BiometricRecordState.DISABLED -> STATE_DISABLED
                    BiometricRecordState.ENABLED -> STATE_ENABLED
                    BiometricRecordState.INVALIDATED -> STATE_INVALIDATED
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw BiometricPersistenceFailure()
        }
    }

    override suspend fun attempts(): BiometricAttemptState = try {
        readAttempts(dataStore.data.first())
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        if (failure is BiometricPersistenceFailure) throw failure
        throw BiometricPersistenceFailure()
    }

    override suspend fun recordFailure(nowEpochMillis: Long): BiometricAttemptState = try {
        var recorded: BiometricAttemptState? = null
        dataStore.edit { preferences ->
            val next = BiometricThrottlePolicy.recordFailure(readAttempts(preferences), nowEpochMillis)
            preferences[FAILED_ATTEMPTS] = next.failedAttempts
            preferences[BLOCKED_UNTIL] = next.blockedUntilEpochMillis
            recorded = next
        }
        recorded ?: throw BiometricPersistenceFailure()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        if (failure is BiometricPersistenceFailure) throw failure
        throw BiometricPersistenceFailure()
    }

    override suspend fun resetAttempts() {
        try {
            dataStore.edit { preferences ->
                preferences[FAILED_ATTEMPTS] = 0
                preferences[BLOCKED_UNTIL] = 0L
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw BiometricPersistenceFailure()
        }
    }

    private fun readAttempts(preferences: Preferences): BiometricAttemptState {
        val failures = preferences[FAILED_ATTEMPTS] ?: 0
        val blockedUntil = preferences[BLOCKED_UNTIL] ?: 0L
        return try {
            BiometricAttemptState(failures, blockedUntil)
        } catch (failure: IllegalArgumentException) {
            throw BiometricPersistenceFailure()
        }
    }

    private companion object {
        const val STATE_DISABLED = "disabled"
        const val STATE_ENABLED = "enabled"
        const val STATE_INVALIDATED = "invalidated"
        val STATE = stringPreferencesKey("biometric_state")
        val FAILED_ATTEMPTS = intPreferencesKey("biometric_failed_attempt_count")
        val BLOCKED_UNTIL = longPreferencesKey("biometric_blocked_until_epoch_ms")
    }
}
