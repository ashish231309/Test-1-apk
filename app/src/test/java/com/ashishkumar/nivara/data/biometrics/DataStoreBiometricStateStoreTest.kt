package com.ashishkumar.nivara.data.biometrics

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import com.ashishkumar.nivara.domain.biometrics.BiometricAttemptState
import com.ashishkumar.nivara.domain.biometrics.BiometricRecordState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreBiometricStateStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun enabledStateAndAppThrottlePersistAcrossStoreRecreation() = runBlocking {
        val file = File(temporaryFolder.newFolder(), "biometrics.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val first = store(file, firstScope)
        first.setState(BiometricRecordState.ENABLED)
        repeat(5) { first.recordFailure(100_000L) }
        assertEquals(BiometricAttemptState(5, 130_000L), first.attempts())
        firstJob.cancel()
        firstJob.join()

        val secondJob = SupervisorJob()
        val secondScope = CoroutineScope(secondJob + Dispatchers.IO)
        try {
            val second = store(file, secondScope)
            assertEquals(BiometricRecordState.ENABLED, second.recordState())
            assertEquals(BiometricAttemptState(5, 130_000L), second.attempts())
            second.setState(BiometricRecordState.INVALIDATED)
            second.resetAttempts()
            assertEquals(BiometricRecordState.INVALIDATED, second.recordState())
            assertEquals(BiometricAttemptState(), second.attempts())
        } finally {
            secondJob.cancel()
            secondJob.join()
        }
    }

    @Test
    fun disabledAndInvalidatedStatesAreDistinctAndCanBeExplicitlyReset() = runBlocking {
        val file = File(temporaryFolder.newFolder(), "state.preferences_pb")
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val store = store(file, scope)
            assertEquals(BiometricRecordState.DISABLED, store.recordState())
            store.setState(BiometricRecordState.INVALIDATED)
            assertEquals(BiometricRecordState.INVALIDATED, store.recordState())
            store.setState(BiometricRecordState.DISABLED)
            assertEquals(BiometricRecordState.DISABLED, store.recordState())
        } finally {
            job.cancel()
            job.join()
        }
    }

    private fun store(file: File, scope: CoroutineScope): DataStoreBiometricStateStore =
        DataStoreBiometricStateStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        )
}
