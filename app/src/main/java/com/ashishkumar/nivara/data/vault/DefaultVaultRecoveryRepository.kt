package com.ashishkumar.nivara.data.vault

import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.vault.ExistingVaultRecordsValidation
import com.ashishkumar.nivara.domain.vault.RecoveryCryptographyFailure
import com.ashishkumar.nivara.domain.vault.RecoveryReconnectKeyResult
import com.ashishkumar.nivara.domain.vault.RecoveryWrapExistingKeyResult
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.VaultMetadataDecode
import com.ashishkumar.nivara.domain.vault.VaultMetadataCodec
import com.ashishkumar.nivara.domain.vault.VaultMetadataFile
import com.ashishkumar.nivara.domain.vault.VaultRecoveryCodeCodec
import com.ashishkumar.nivara.domain.vault.VaultRecoveryCryptography
import com.ashishkumar.nivara.domain.vault.VaultRecoveryFailure
import com.ashishkumar.nivara.domain.vault.VaultRecoveryFile
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRecord
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRecordCodec
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRecordDecode
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRepository
import com.ashishkumar.nivara.domain.vault.VaultRecoveryResult
import com.ashishkumar.nivara.domain.vault.VaultRecoverySetupCommitResult
import com.ashishkumar.nivara.domain.vault.VaultRecoverySetupId
import com.ashishkumar.nivara.domain.vault.VaultRecoverySetupPreview
import com.ashishkumar.nivara.domain.vault.VaultRecoverySetupResult
import com.ashishkumar.nivara.domain.vault.VaultStorage
import com.ashishkumar.nivara.domain.vault.VaultStorageCommitResult
import com.ashishkumar.nivara.domain.vault.VaultStorageSnapshot
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRead
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.CharBuffer

/** Orchestrates create-only recovery setup and explicit recovery without gaining access to Android storage handles. */
class DefaultVaultRecoveryRepository(
    private val storage: VaultStorage,
    private val cryptography: VaultRecoveryCryptography,
    private val indexRepository: VaultIndexRepository,
    private val organizationRepository: VaultOrganizationRepository,
    private val random: SecureRandomSource,
) : VaultRecoveryRepository {
    private val mutex = Mutex()
    private var pendingSetup: PendingSetup? = null

    override suspend fun prepareSetup(vaultId: VaultId): VaultRecoverySetupResult = mutex.withLock {
        clearPending()
        var recoveryKey: ByteArray? = null
        var wrapped: ByteArray? = null
        var recordBytes: ByteArray? = null
        var tokenBytes: ByteArray? = null
        try {
            recoveryKey = random.generateRecoveryKeyBytes()
            if (recoveryKey.size != Aes256Key.KEY_BYTES) return@withLock VaultRecoverySetupResult.Failed
            val code = VaultRecoveryCodeCodec.encode(recoveryKey)
            wrapped = when (val result = cryptography.wrapExistingContentKey(vaultId, recoveryKey)) {
                is RecoveryWrapExistingKeyResult.Wrapped -> result.bytes
                RecoveryWrapExistingKeyResult.AlreadyConfigured -> return@withLock VaultRecoverySetupResult.AlreadyConfigured
                RecoveryWrapExistingKeyResult.NotReady -> return@withLock VaultRecoverySetupResult.VaultNotReady
                RecoveryWrapExistingKeyResult.Unavailable -> return@withLock VaultRecoverySetupResult.VaultUnavailable
                RecoveryWrapExistingKeyResult.Failed -> return@withLock VaultRecoverySetupResult.Failed
            }
            recordBytes = VaultRecoveryRecordCodec.encode(VaultRecoveryRecord(vaultId, wrapped))
            tokenBytes = random.generateBytes(16)
            if (tokenBytes.size != 16) return@withLock VaultRecoverySetupResult.Failed
            val setupId = VaultRecoverySetupId(tokenBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
            pendingSetup = PendingSetup(setupId, vaultId, recordBytes.copyOf(), recoveryKey.copyOf())
            VaultRecoverySetupResult.Prepared(VaultRecoverySetupPreview(setupId, code))
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            VaultRecoverySetupResult.Failed
        } finally {
            recoveryKey?.fill(0)
            wrapped?.fill(0)
            recordBytes?.fill(0)
            tokenBytes?.fill(0)
        }
    }

    override suspend fun confirmSetup(setupId: VaultRecoverySetupId): VaultRecoverySetupCommitResult = mutex.withLock {
        val pending = pendingSetup ?: return@withLock VaultRecoverySetupCommitResult.Expired
        if (pending.id != setupId) return@withLock VaultRecoverySetupCommitResult.Expired
        try {
            when (storage.commitRecoveryRecordAtomically(pending.vaultId, pending.recordBytes)) {
                VaultStorageCommitResult.Created -> {
                    val snapshot = storage.inspect()
                    val available = snapshot as? VaultStorageSnapshot.Available
                        ?: return@withLock VaultRecoverySetupCommitResult.VerificationFailed
                    val metadata = available.metadata as? VaultMetadataFile.Present
                        ?: return@withLock VaultRecoverySetupCommitResult.VerificationFailed
                    val readback = available.recoveryRecord as? VaultRecoveryFile.Present
                    if (readback == null) {
                        metadata.bytes.fill(0)
                        return@withLock VaultRecoverySetupCommitResult.VerificationFailed
                    }
                    try {
                        val matches = readback.bytes.contentEquals(pending.recordBytes)
                        val authentic = matches && cryptography.verifyPreparedRecoveryRecord(
                            pending.vaultId, pending.recoveryKeyBytes, metadata.bytes, readback.bytes,
                        )
                        if (authentic) VaultRecoverySetupCommitResult.Committed
                        else VaultRecoverySetupCommitResult.VerificationFailed
                    } finally {
                        metadata.bytes.fill(0)
                        readback.bytes.fill(0)
                    }
                }
                VaultStorageCommitResult.RecoveryRecordExists -> VaultRecoverySetupCommitResult.AlreadyConfigured
                VaultStorageCommitResult.AccessDenied -> VaultRecoverySetupCommitResult.VaultUnavailable
                VaultStorageCommitResult.Unavailable -> VaultRecoverySetupCommitResult.VaultUnavailable
                else -> VaultRecoverySetupCommitResult.Failed
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            VaultRecoverySetupCommitResult.Failed
        } finally {
            pendingSetup?.recordBytes?.fill(0)
            pendingSetup?.recoveryKeyBytes?.fill(0)
            pendingSetup = null
        }
    }

    override suspend fun cancelSetup(setupId: VaultRecoverySetupId) = mutex.withLock {
        if (pendingSetup?.id == setupId) clearPending()
    }

    override suspend fun recover(recoveryCode: CharArray): VaultRecoveryResult {
        var decodedKey: ByteArray? = null
        var validDecode: VaultRecoveryCodeCodec.Decode.Valid? = null
        try {
            val decoded = VaultRecoveryCodeCodec.decode(CharBuffer.wrap(recoveryCode))
            if (decoded !is VaultRecoveryCodeCodec.Decode.Valid) {
                return VaultRecoveryResult.Failed(VaultRecoveryFailure.INVALID_MATERIAL)
            }
            validDecode = decoded
            decodedKey = decoded.keyBytes()
            val snapshot = try { storage.inspect() }
            catch (failure: CancellationException) { throw failure }
            catch (_: SecurityException) { return VaultRecoveryResult.Failed(VaultRecoveryFailure.ACCESS_DENIED) }
            catch (_: Exception) { return VaultRecoveryResult.Failed(VaultRecoveryFailure.LOCATION_UNAVAILABLE) }
            val available = snapshot as? VaultStorageSnapshot.Available
                ?: return VaultRecoveryResult.Failed(
                    if (snapshot == VaultStorageSnapshot.AccessDenied) VaultRecoveryFailure.ACCESS_DENIED
                    else VaultRecoveryFailure.LOCATION_UNAVAILABLE,
                )
            if (available.unexpectedEntries || available.unexpectedDataEntries) {
                return VaultRecoveryResult.Failed(VaultRecoveryFailure.VAULT_DAMAGED)
            }
            val metadataFile = available.metadata as? VaultMetadataFile.Present
                ?: return VaultRecoveryResult.Failed(VaultRecoveryFailure.NOT_A_VAULT)
            val vaultId = try {
                when (val decodedMetadata = VaultMetadataCodec.decode(metadataFile.bytes)) {
                    VaultMetadataDecode.Invalid -> return VaultRecoveryResult.Failed(VaultRecoveryFailure.VAULT_DAMAGED)
                    is VaultMetadataDecode.UnsupportedVersion -> return VaultRecoveryResult.Failed(VaultRecoveryFailure.VAULT_UNSUPPORTED)
                    is VaultMetadataDecode.Supported -> decodedMetadata.metadata.vaultId
                }
            } finally { metadataFile.bytes.fill(0) }

            return when (val result = cryptography.reconnectWithRecoveryKey(vaultId, decodedKey) {
                validateExistingRecords(vaultId)
            }) {
                RecoveryReconnectKeyResult.Reconnected -> VaultRecoveryResult.Reconnected(vaultId)
                is RecoveryReconnectKeyResult.Failed -> VaultRecoveryResult.Failed(result.reason.toPublicFailure())
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return VaultRecoveryResult.Failed(VaultRecoveryFailure.UNAVAILABLE)
        } finally {
            recoveryCode.fill('\u0000')
            decodedKey?.fill(0)
            validDecode?.clear()
        }
    }

    private suspend fun validateExistingRecords(vaultId: VaultId): ExistingVaultRecordsValidation {
        val index = try { indexRepository.inspect(vaultId) }
        catch (failure: CancellationException) { throw failure }
        catch (_: SecurityException) { return ExistingVaultRecordsValidation.ACCESS_DENIED }
        catch (_: Exception) { return ExistingVaultRecordsValidation.UNAVAILABLE }
        when (index) {
            is VaultIndexRead.Ready -> {
                val diagnostics = index.contentDiagnostics ?: return ExistingVaultRecordsValidation.UNAVAILABLE
                if (diagnostics.missingContent != 0 || diagnostics.unindexedObjects != 0 ||
                    diagnostics.unfinishedObjects != 0
                ) return ExistingVaultRecordsValidation.DAMAGED
            }
            VaultIndexRead.Missing -> Unit // A genuinely empty Stage 13 vault need not have initialized an index.
            is VaultIndexRead.UnsupportedVersion -> return ExistingVaultRecordsValidation.UNSUPPORTED
            VaultIndexRead.AccessDenied -> return ExistingVaultRecordsValidation.ACCESS_DENIED
            VaultIndexRead.ObjectsWithoutIndex, is VaultIndexRead.ContentWithoutIndex ->
                return ExistingVaultRecordsValidation.DAMAGED
            VaultIndexRead.Unavailable -> return ExistingVaultRecordsValidation.UNAVAILABLE
            VaultIndexRead.Corrupt -> return ExistingVaultRecordsValidation.DAMAGED
            is VaultIndexRead.VaultUnavailable -> return ExistingVaultRecordsValidation.UNAVAILABLE
        }
        val organization = try { organizationRepository.inspect(vaultId) }
        catch (failure: CancellationException) { throw failure }
        catch (_: SecurityException) { return ExistingVaultRecordsValidation.ACCESS_DENIED }
        catch (_: Exception) { return ExistingVaultRecordsValidation.UNAVAILABLE }
        return when (organization) {
            is VaultOrganizationRead.Ready -> ExistingVaultRecordsValidation.VALID
            VaultOrganizationRead.Corrupt -> ExistingVaultRecordsValidation.DAMAGED
            is VaultOrganizationRead.UnsupportedVersion -> ExistingVaultRecordsValidation.UNSUPPORTED
            VaultOrganizationRead.AccessDenied -> ExistingVaultRecordsValidation.ACCESS_DENIED
            VaultOrganizationRead.Unavailable, VaultOrganizationRead.VaultUnavailable ->
                ExistingVaultRecordsValidation.UNAVAILABLE
        }
    }

    private fun RecoveryCryptographyFailure.toPublicFailure(): VaultRecoveryFailure = when (this) {
        RecoveryCryptographyFailure.NOT_READY -> VaultRecoveryFailure.UNAVAILABLE
        RecoveryCryptographyFailure.LOCATION_UNAVAILABLE -> VaultRecoveryFailure.LOCATION_UNAVAILABLE
        RecoveryCryptographyFailure.ACCESS_DENIED -> VaultRecoveryFailure.ACCESS_DENIED
        RecoveryCryptographyFailure.NOT_A_VAULT -> VaultRecoveryFailure.NOT_A_VAULT
        RecoveryCryptographyFailure.WRONG_VAULT -> VaultRecoveryFailure.WRONG_VAULT
        RecoveryCryptographyFailure.IDENTITY_UNKNOWN -> VaultRecoveryFailure.IDENTITY_UNKNOWN
        RecoveryCryptographyFailure.RECOVERY_NOT_CONFIGURED -> VaultRecoveryFailure.RECOVERY_NOT_CONFIGURED
        RecoveryCryptographyFailure.INVALID_MATERIAL -> VaultRecoveryFailure.INVALID_MATERIAL
        RecoveryCryptographyFailure.DAMAGED -> VaultRecoveryFailure.VAULT_DAMAGED
        RecoveryCryptographyFailure.UNSUPPORTED -> VaultRecoveryFailure.VAULT_UNSUPPORTED
        RecoveryCryptographyFailure.RECORDS_DAMAGED -> VaultRecoveryFailure.RECORDS_DAMAGED
        RecoveryCryptographyFailure.PERSISTENCE_FAILED -> VaultRecoveryFailure.KEY_ACCESS_PERSISTENCE
        RecoveryCryptographyFailure.UNAVAILABLE -> VaultRecoveryFailure.UNAVAILABLE
    }

    private fun clearPending() {
        pendingSetup?.recordBytes?.fill(0)
        pendingSetup?.recoveryKeyBytes?.fill(0)
        pendingSetup = null
    }

    private data class PendingSetup(
        val id: VaultRecoverySetupId,
        val vaultId: VaultId,
        val recordBytes: ByteArray,
        val recoveryKeyBytes: ByteArray,
    )
}
