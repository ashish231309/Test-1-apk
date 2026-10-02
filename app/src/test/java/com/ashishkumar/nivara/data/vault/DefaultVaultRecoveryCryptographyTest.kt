package com.ashishkumar.nivara.data.vault

import com.ashishkumar.nivara.data.security.AesGcmKeyWrappingService
import com.ashishkumar.nivara.data.security.JcaAesGcmEncryption
import com.ashishkumar.nivara.data.security.JcaSecureRandomSource
import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.DeviceKeyStore
import com.ashishkumar.nivara.domain.security.SecurityFailure
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.vault.ExistingVaultRecordsValidation
import com.ashishkumar.nivara.domain.vault.RecoveryReconnectKeyResult
import com.ashishkumar.nivara.domain.vault.RecoveryWrapExistingKeyResult
import com.ashishkumar.nivara.domain.vault.VaultDirectoryEntry
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.VaultInitializationResult
import com.ashishkumar.nivara.domain.vault.VaultKeyAccessRead
import com.ashishkumar.nivara.domain.vault.VaultKeyAccessRecord
import com.ashishkumar.nivara.domain.vault.VaultKeyAccessStore
import com.ashishkumar.nivara.domain.vault.VaultMetadataFile
import com.ashishkumar.nivara.domain.vault.VaultRecoveryFile
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRecord
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRecordCodec
import com.ashishkumar.nivara.domain.vault.VaultStatus
import com.ashishkumar.nivara.domain.vault.VaultStorage
import com.ashishkumar.nivara.domain.vault.VaultStorageCommitResult
import com.ashishkumar.nivara.domain.vault.VaultStorageSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultVaultRecoveryCryptographyTest {
    @Test
    fun validRecoveryAuthenticatesIdentityAndReconnectsTheSameKeyWithoutChangingVaultBytes() = runTest {
        val harness = Harness()
        val vaultId = (harness.repository.initialize() as VaultInitializationResult.Initialized).vaultId
        val baseMetadata = harness.storage.metadataBytes.copyOf()
        val recoveryKey = harness.random.generateRecoveryKeyBytes()
        try {
            val wrapped = (harness.crypto.wrapExistingContentKey(vaultId, recoveryKey)
                as RecoveryWrapExistingKeyResult.Wrapped).bytes
            val recordBytes = VaultRecoveryRecordCodec.encode(VaultRecoveryRecord(vaultId, wrapped))
            harness.storage.setRecoveryRecord(recordBytes)
            assertTrue(harness.crypto.verifyPreparedRecoveryRecord(
                vaultId, recoveryKey, baseMetadata, recordBytes,
            ))

            harness.keyStore.deleteKey("vault_content_wrap_v1")
            assertEquals(VaultStatus.RecoveryRequired(vaultId), harness.repository.inspect())
            assertTrue(harness.localStore.load() is VaultKeyAccessRead.Missing)

            assertEquals(
                RecoveryReconnectKeyResult.Reconnected,
                harness.crypto.reconnectWithRecoveryKey(vaultId, recoveryKey) {
                    ExistingVaultRecordsValidation.VALID
                },
            )
            assertEquals(VaultStatus.Ready(vaultId), harness.repository.inspect())
            assertTrue(harness.localStore.load() is VaultKeyAccessRead.Present)
            assertArrayEquals(baseMetadata, harness.storage.metadataBytes)
            assertArrayEquals(recordBytes, harness.storage.recoveryRecordBytes)
        } finally { recoveryKey.fill(0) }
    }

    @Test
    fun wrongRecoveryKeyAndDamagedRecordsNeverPersistLocalKeyAccess() = runTest {
        val harness = Harness()
        val vaultId = (harness.repository.initialize() as VaultInitializationResult.Initialized).vaultId
        val validRecoveryKey = harness.random.generateRecoveryKeyBytes()
        val wrongRecoveryKey = harness.random.generateRecoveryKeyBytes()
        try {
            val wrapped = (harness.crypto.wrapExistingContentKey(vaultId, validRecoveryKey)
                as RecoveryWrapExistingKeyResult.Wrapped).bytes
            val record = VaultRecoveryRecordCodec.encode(VaultRecoveryRecord(vaultId, wrapped))
            harness.storage.setRecoveryRecord(record)
            harness.keyStore.deleteKey("vault_content_wrap_v1")

            val wrongResult = harness.crypto.reconnectWithRecoveryKey(vaultId, wrongRecoveryKey) {
                ExistingVaultRecordsValidation.VALID
            }
            assertTrue(wrongResult is RecoveryReconnectKeyResult.Failed)
            assertTrue(harness.localStore.load() is VaultKeyAccessRead.Missing)

            val damagedResult = harness.crypto.reconnectWithRecoveryKey(vaultId, validRecoveryKey) {
                ExistingVaultRecordsValidation.DAMAGED
            }
            assertEquals(
                RecoveryReconnectKeyResult.Failed(
                    com.ashishkumar.nivara.domain.vault.RecoveryCryptographyFailure.RECORDS_DAMAGED,
                ),
                damagedResult,
            )
            assertTrue(harness.localStore.load() is VaultKeyAccessRead.Missing)
        } finally {
            validRecoveryKey.fill(0)
            wrongRecoveryKey.fill(0)
        }
    }

    @Test
    fun wrongIdentityAndUnconfiguredRecoveryAreRejectedWithoutInitialization() = runTest {
        val harness = Harness()
        val vaultId = (harness.repository.initialize() as VaultInitializationResult.Initialized).vaultId
        val recoveryKey = harness.random.generateRecoveryKeyBytes()
        val baseBefore = harness.storage.metadataBytes.copyOf()
        try {
            val prepared = harness.crypto.wrapExistingContentKey(vaultId, recoveryKey)
                as RecoveryWrapExistingKeyResult.Wrapped
            prepared.bytes.fill(0)
            harness.storage.setRecoveryRecord(byteArrayOf(1))
            assertEquals(
                RecoveryWrapExistingKeyResult.AlreadyConfigured,
                harness.crypto.wrapExistingContentKey(vaultId, recoveryKey),
            )
            val wrong = VaultId("ffeeddccbbaa99887766554433221100")
            assertEquals(
                RecoveryReconnectKeyResult.Failed(
                    com.ashishkumar.nivara.domain.vault.RecoveryCryptographyFailure.WRONG_VAULT,
                ),
                harness.crypto.reconnectWithRecoveryKey(wrong, recoveryKey) {
                    ExistingVaultRecordsValidation.VALID
                },
            )
            assertArrayEquals(baseBefore, harness.storage.metadataBytes)
            assertTrue(harness.localStore.load() is VaultKeyAccessRead.Missing)
            assertEquals(1, harness.storage.initializeCalls)
        } finally { recoveryKey.fill(0) }
    }

    private class Harness {
        val random: SecureRandomSource = JcaSecureRandomSource()
        val storage = FakeStorage()
        val keyStore = FakeDeviceKeyStore(random)
        val localStore = FakeVaultKeyAccessStore()
        private val encryption = JcaAesGcmEncryption(random)
        val repository = DefaultVaultRepository(
            storage = storage,
            encryption = encryption,
            keyWrapping = AesGcmKeyWrappingService(encryption),
            deviceKeyStore = keyStore,
            random = random,
            keyAccessStore = localStore,
        )
        val crypto get() = repository as com.ashishkumar.nivara.domain.vault.VaultRecoveryCryptography
    }

    private class FakeStorage : VaultStorage {
        var snapshot: VaultStorageSnapshot = VaultStorageSnapshot.Available(
            VaultMetadataFile.Missing, VaultDirectoryEntry.MISSING, unexpectedEntries = false,
        )
        var metadataBytes = byteArrayOf()
        var recoveryRecordBytes = byteArrayOf()
        var initializeCalls = 0

        override suspend fun inspect(): VaultStorageSnapshot = when (val current = snapshot) {
            is VaultStorageSnapshot.Available -> current.copy(
                metadata = when (val metadata = current.metadata) {
                    is VaultMetadataFile.Present -> VaultMetadataFile.Present(metadata.bytes.copyOf())
                    else -> metadata
                },
                recoveryRecord = if (recoveryRecordBytes.isEmpty()) VaultRecoveryFile.Missing
                    else VaultRecoveryFile.Present(recoveryRecordBytes.copyOf()),
            )
            else -> current
        }

        override suspend fun initializeAtomically(metadataBytes: ByteArray): VaultStorageCommitResult {
            initializeCalls++
            if (snapshot !is VaultStorageSnapshot.Available) return VaultStorageCommitResult.Unavailable
            metadataBytes.let { this.metadataBytes = it.copyOf() }
            snapshot = VaultStorageSnapshot.Available(
                VaultMetadataFile.Present(this.metadataBytes.copyOf()), VaultDirectoryEntry.DIRECTORY,
                unexpectedEntries = false,
            )
            return VaultStorageCommitResult.Created
        }

        override suspend fun commitRecoveryRecordAtomically(
            expectedVaultId: VaultId,
            recordBytes: ByteArray,
        ): VaultStorageCommitResult {
            if (recoveryRecordBytes.isNotEmpty()) return VaultStorageCommitResult.RecoveryRecordExists
            recoveryRecordBytes = recordBytes.copyOf()
            return VaultStorageCommitResult.Created
        }

        fun setRecoveryRecord(bytes: ByteArray) { recoveryRecordBytes = bytes.copyOf() }
    }

    private class FakeDeviceKeyStore(private val random: SecureRandomSource) : DeviceKeyStore {
        private var key: Aes256Key? = null
        override fun createAes256Key(alias: String): Aes256Key {
            if (key != null) throw SecurityFailure.KeyAlreadyExists()
            val bytes = random.generateAes256KeyBytes()
            return try { Aes256Key.fromBytes(bytes).also { key = it } } finally { bytes.fill(0) }
        }
        override fun getAes256Key(alias: String): Aes256Key = key ?: throw SecurityFailure.MissingKey()
        override fun deleteKey(alias: String) { key = null }
    }

    private class FakeVaultKeyAccessStore : VaultKeyAccessStore {
        private var record: VaultKeyAccessRecord? = null
        override suspend fun load(): VaultKeyAccessRead = record?.let { VaultKeyAccessRead.Present(it) }
            ?: VaultKeyAccessRead.Missing
        override suspend fun replace(record: VaultKeyAccessRecord): Boolean {
            this.record = record
            return true
        }
        override suspend fun clearIfMatches(record: VaultKeyAccessRecord): Boolean {
            this.record = null
            return true
        }
    }
}
