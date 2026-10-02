package com.ashishkumar.nivara.data.vault

import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.security.TimeProvider
import com.ashishkumar.nivara.data.security.JcaSecureRandomSource
import com.ashishkumar.nivara.domain.vault.ExistingVaultRecordsValidation
import com.ashishkumar.nivara.domain.vault.RecoveryCryptographyFailure
import com.ashishkumar.nivara.domain.vault.RecoveryReconnectKeyResult
import com.ashishkumar.nivara.domain.vault.RecoveryWrapExistingKeyResult
import com.ashishkumar.nivara.domain.vault.VaultDirectoryEntry
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.VaultMetadataCodec
import com.ashishkumar.nivara.domain.vault.VaultMetadataEnvelope
import com.ashishkumar.nivara.domain.vault.VaultMetadataFile
import com.ashishkumar.nivara.domain.vault.VaultRecoveryCodeCodec
import com.ashishkumar.nivara.domain.vault.VaultRecoveryCryptography
import com.ashishkumar.nivara.domain.vault.VaultRecoveryFile
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRecord
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRecordCodec
import com.ashishkumar.nivara.domain.vault.VaultRecoveryResult
import com.ashishkumar.nivara.domain.vault.VaultRecoverySetupCommitResult
import com.ashishkumar.nivara.domain.vault.VaultRecoverySetupResult
import com.ashishkumar.nivara.domain.vault.VaultStorage
import com.ashishkumar.nivara.domain.vault.VaultStorageCommitResult
import com.ashishkumar.nivara.domain.vault.VaultStorageSnapshot
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumId
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumMutationResult
import com.ashishkumar.nivara.domain.vault.content.VaultAlbum
import com.ashishkumar.nivara.domain.vault.content.VaultIndexInitializationResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultIndexWriteResult
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRead
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRepository
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultVaultRecoveryRepositoryTest {
    private companion object {
        val TEST_VAULT_ID = VaultId("00112233445566778899aabbccddeeff")
    }

    @Test
    fun setupShowsRandomCodeButPersistsOnlyAfterExplicitConfirmation() = runTest {
        val harness = Harness()
        val prepared = harness.repository.prepareSetup(TEST_VAULT_ID) as VaultRecoverySetupResult.Prepared
        val preview = prepared.preview
        assertTrue(preview.recoveryCode.startsWith("NVR1-"))
        assertTrue(preview.toString().contains("redacted"))
        assertEquals(0, harness.storage.recoveryCommitCalls)
        assertTrue(harness.storage.currentRecovery() is VaultRecoveryFile.Missing)

        assertEquals(VaultRecoverySetupCommitResult.Committed, harness.repository.confirmSetup(preview.setupId))
        assertEquals(1, harness.storage.recoveryCommitCalls)
        val record = harness.storage.currentRecovery() as VaultRecoveryFile.Present
        try {
            val decoded = VaultRecoveryRecordCodec.decode(record.bytes) as com.ashishkumar.nivara.domain.vault.VaultRecoveryRecordDecode.Supported
            assertEquals(TEST_VAULT_ID, decoded.record.vaultId)
            assertTrue(harness.crypto.verifiedPreparedRecord)
        } finally { record.bytes.fill(0) }
    }

    @Test
    fun cancelAndProcessOnlyPreviewDoNotWriteRecoveryMetadata() = runTest {
        val cancelled = Harness()
        val preview = (cancelled.repository.prepareSetup(TEST_VAULT_ID) as VaultRecoverySetupResult.Prepared).preview
        cancelled.repository.cancelSetup(preview.setupId)
        assertEquals(VaultRecoverySetupCommitResult.Expired, cancelled.repository.confirmSetup(preview.setupId))
        assertEquals(0, cancelled.storage.recoveryCommitCalls)

        val notConfirmed = Harness()
        notConfirmed.repository.prepareSetup(TEST_VAULT_ID)
        assertEquals(0, notConfirmed.storage.recoveryCommitCalls)
        assertTrue(notConfirmed.storage.currentRecovery() is VaultRecoveryFile.Missing)
    }

    @Test
    fun recoveryClearsInputAndNeverInitializesOrAcceptsDamagedAuthenticatedRecords() = runTest {
        val invalid = Harness()
        val invalidInput = "not a recovery code".toCharArray()
        assertEquals(
            VaultRecoveryResult.Failed(com.ashishkumar.nivara.domain.vault.VaultRecoveryFailure.INVALID_MATERIAL),
            invalid.repository.recover(invalidInput),
        )
        assertTrue(invalidInput.all { it == '\u0000' })
        assertEquals(0, invalid.storage.initializeCalls)

        val damaged = Harness().apply {
            index.result = VaultIndexRead.Corrupt
            storage.setRecoveryRecord(VaultRecoveryRecordCodec.encode(VaultRecoveryRecord(TEST_VAULT_ID, byteArrayOf(1))))
        }
        val codeKey = ByteArray(32) { (it + 1).toByte() }
        val code = VaultRecoveryCodeCodec.encode(codeKey).toCharArray()
        codeKey.fill(0)
        val result = damaged.repository.recover(code)
        assertEquals(VaultRecoveryResult.Failed(com.ashishkumar.nivara.domain.vault.VaultRecoveryFailure.RECORDS_DAMAGED), result)
        assertTrue(code.all { it == '\u0000' })
        assertEquals(0, damaged.storage.initializeCalls)
        assertEquals(0, damaged.storage.recoveryCommitCalls)
    }

    @Test
    fun failedEnvelopeAuthenticationIsRateLimitedInMemoryAndSuccessResetsIt() = runTest {
        val harness = Harness()
        harness.crypto.reconnectFailure = RecoveryCryptographyFailure.WRONG_VAULT
        val wrongCode = VaultRecoveryCodeCodec.encode(ByteArray(32) { 4 }).toCharArray()
        assertEquals(
            VaultRecoveryResult.Failed(com.ashishkumar.nivara.domain.vault.VaultRecoveryFailure.WRONG_VAULT),
            harness.repository.recover(wrongCode),
        )
        assertTrue(wrongCode.all { it == '\u0000' })

        val blockedCode = "bounded input".toCharArray()
        assertEquals(
            VaultRecoveryResult.Failed(com.ashishkumar.nivara.domain.vault.VaultRecoveryFailure.THROTTLED),
            harness.repository.recover(blockedCode),
        )
        assertTrue(blockedCode.all { it == '\u0000' })

        harness.clock.elapsedMillis += 1_000L
        harness.crypto.reconnectFailure = null
        val validCode = VaultRecoveryCodeCodec.encode(ByteArray(32) { 5 }).toCharArray()
        assertEquals(VaultRecoveryResult.Reconnected(TEST_VAULT_ID), harness.repository.recover(validCode))
        assertTrue(validCode.all { it == '\u0000' })

        harness.crypto.reconnectFailure = RecoveryCryptographyFailure.WRONG_VAULT
        val afterReset = VaultRecoveryCodeCodec.encode(ByteArray(32) { 6 }).toCharArray()
        assertEquals(
            VaultRecoveryResult.Failed(com.ashishkumar.nivara.domain.vault.VaultRecoveryFailure.WRONG_VAULT),
            harness.repository.recover(afterReset),
        )
        assertTrue(afterReset.all { it == '\u0000' })
    }

    @Test
    fun missingIndexAndOrganizationRemainMissingAndDoNotTriggerInitialization() = runTest {
        val harness = Harness().apply {
            index.result = VaultIndexRead.Missing
            organization.result = VaultOrganizationRead.Ready(VaultOrganizationSnapshot(0, emptyList()))
            storage.setRecoveryRecord(VaultRecoveryRecordCodec.encode(VaultRecoveryRecord(TEST_VAULT_ID, byteArrayOf(8))))
        }
        val chars = VaultRecoveryCodeCodec.encode(ByteArray(32) { 7 }).toCharArray()
        assertEquals(VaultRecoveryResult.Reconnected(TEST_VAULT_ID), harness.repository.recover(chars))
        assertTrue(chars.all { it == '\u0000' })
        assertEquals(0, harness.storage.initializeCalls)
        assertEquals(0, harness.storage.recoveryCommitCalls)
        assertEquals(1, harness.index.inspectCalls)
        assertEquals(1, harness.organization.inspectCalls)
    }

    private class Harness {
        val random: SecureRandomSource = JcaSecureRandomSource()
        val clock = FakeClock()
        val storage = FakeStorage(TEST_VAULT_ID)
        val crypto = FakeCryptography()
        val index = FakeIndexRepository()
        val organization = FakeOrganizationRepository()
        val repository = DefaultVaultRecoveryRepository(storage, crypto, index, organization, random, clock)
    }

    private class FakeClock(var elapsedMillis: Long = 10_000L) : TimeProvider {
        override fun nowEpochMillis(): Long = elapsedMillis
        override fun nowElapsedRealtimeMillis(): Long = elapsedMillis
    }

    private class FakeStorage(private val vaultId: VaultId) : VaultStorage {
        var initializeCalls = 0
        var recoveryCommitCalls = 0
        private var recoveryBytes = byteArrayOf()
        private val metadata = VaultMetadataCodec.encode(
            VaultMetadataEnvelope(vaultId, byteArrayOf(1, 2), byteArrayOf(3, 4)),
        )
        override suspend fun inspect(): VaultStorageSnapshot = VaultStorageSnapshot.Available(
            metadata = VaultMetadataFile.Present(metadata.copyOf()),
            dataDirectory = VaultDirectoryEntry.DIRECTORY,
            unexpectedEntries = false,
            recoveryRecord = currentRecovery().let { file ->
                if (file is VaultRecoveryFile.Present) VaultRecoveryFile.Present(file.bytes) else file
            },
        )
        override suspend fun initializeAtomically(metadataBytes: ByteArray): VaultStorageCommitResult {
            initializeCalls++
            return VaultStorageCommitResult.Created
        }
        override suspend fun commitRecoveryRecordAtomically(
            expectedVaultId: VaultId,
            recordBytes: ByteArray,
        ): VaultStorageCommitResult {
            recoveryCommitCalls++
            if (recoveryBytes.isNotEmpty()) return VaultStorageCommitResult.RecoveryRecordExists
            recoveryBytes = recordBytes.copyOf()
            return VaultStorageCommitResult.Created
        }
        fun currentRecovery(): VaultRecoveryFile = if (recoveryBytes.isEmpty()) VaultRecoveryFile.Missing
            else VaultRecoveryFile.Present(recoveryBytes.copyOf())
        fun setRecoveryRecord(bytes: ByteArray) { recoveryBytes = bytes.copyOf() }
    }

    private class FakeCryptography : VaultRecoveryCryptography {
        var verifiedPreparedRecord = false
        var reconnectFailure: RecoveryCryptographyFailure? = null
        override suspend fun wrapExistingContentKey(
            vaultId: VaultId,
            recoveryKeyBytes: ByteArray,
        ): RecoveryWrapExistingKeyResult = RecoveryWrapExistingKeyResult.Wrapped(byteArrayOf(9, 8, 7))
        override suspend fun verifyPreparedRecoveryRecord(
            vaultId: VaultId,
            recoveryKeyBytes: ByteArray,
            metadataBytes: ByteArray,
            recoveryRecordBytes: ByteArray,
        ): Boolean {
            verifiedPreparedRecord = metadataBytes.isNotEmpty() && recoveryRecordBytes.isNotEmpty()
            return verifiedPreparedRecord
        }
        override suspend fun reconnectWithRecoveryKey(
            vaultId: VaultId,
            recoveryKeyBytes: ByteArray,
            validateExistingRecords: suspend () -> ExistingVaultRecordsValidation,
        ): RecoveryReconnectKeyResult {
            reconnectFailure?.let { return RecoveryReconnectKeyResult.Failed(it) }
            return when (validateExistingRecords()) {
                ExistingVaultRecordsValidation.VALID -> RecoveryReconnectKeyResult.Reconnected
            ExistingVaultRecordsValidation.DAMAGED -> RecoveryReconnectKeyResult.Failed(RecoveryCryptographyFailure.RECORDS_DAMAGED)
            ExistingVaultRecordsValidation.UNSUPPORTED -> RecoveryReconnectKeyResult.Failed(RecoveryCryptographyFailure.UNSUPPORTED)
            ExistingVaultRecordsValidation.ACCESS_DENIED -> RecoveryReconnectKeyResult.Failed(RecoveryCryptographyFailure.ACCESS_DENIED)
            ExistingVaultRecordsValidation.UNAVAILABLE -> RecoveryReconnectKeyResult.Failed(RecoveryCryptographyFailure.UNAVAILABLE)
        }
    }

    private class FakeIndexRepository : VaultIndexRepository {
        var result: VaultIndexRead = VaultIndexRead.Missing
        var inspectCalls = 0
        override suspend fun inspect(vaultId: VaultId): VaultIndexRead { inspectCalls++; return result }
        override suspend fun initializeEmpty(vaultId: VaultId, authorizationCheckpoint: suspend () -> Boolean) =
            VaultIndexInitializationResult.Unavailable
        override suspend fun listItems(vaultId: VaultId): VaultIndexRead = result
        override suspend fun addItem(vaultId: VaultId, item: VaultItem, authorizationCheckpoint: suspend () -> Boolean) =
            VaultIndexWriteResult.Failed
    }

    private class FakeOrganizationRepository : VaultOrganizationRepository {
        var result: VaultOrganizationRead = VaultOrganizationRead.Ready(VaultOrganizationSnapshot(0, emptyList()))
        var inspectCalls = 0
        override suspend fun inspect(vaultId: VaultId): VaultOrganizationRead { inspectCalls++; return result }
        override suspend fun createAlbum(vaultId: VaultId, name: String, authorizationCheckpoint: suspend () -> Boolean) =
            VaultAlbumMutationResult.WriteFailed
        override suspend fun renameAlbum(vaultId: VaultId, albumId: VaultAlbumId, name: String, authorizationCheckpoint: suspend () -> Boolean) =
            VaultAlbumMutationResult.WriteFailed
        override suspend fun deleteAlbum(vaultId: VaultId, albumId: VaultAlbumId, authorizationCheckpoint: suspend () -> Boolean) =
            VaultAlbumMutationResult.WriteFailed
        override suspend fun addMembership(vaultId: VaultId, albumId: VaultAlbumId, itemId: VaultItemId, authorizationCheckpoint: suspend () -> Boolean) =
            VaultAlbumMutationResult.WriteFailed
        override suspend fun removeMembership(vaultId: VaultId, albumId: VaultAlbumId, itemId: VaultItemId, authorizationCheckpoint: suspend () -> Boolean) =
            VaultAlbumMutationResult.WriteFailed
    }
}
