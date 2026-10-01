package com.ashishkumar.nivara.data.vault

import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.content.VaultAlbum
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumId
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumMutationResult
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumName
import com.ashishkumar.nivara.domain.vault.content.VaultContentCrypto
import com.ashishkumar.nivara.domain.vault.content.VaultContentCryptoResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentStorage
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultItemLifecycle
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationCodec
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationFiles
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationLimits
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRead
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRepository
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationSnapshot
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationStorageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Authenticated organization metadata only; the authenticated index remains authoritative for item fields. */
class DefaultVaultOrganizationRepository(
    private val storage: VaultContentStorage,
    private val crypto: VaultContentCrypto,
    private val index: VaultIndexRepository,
    private val random: SecureRandomSource,
) : VaultOrganizationRepository {
    private val mutex = Mutex()

    override suspend fun inspect(vaultId: VaultId): VaultOrganizationRead = mutex.withLock {
        try {
            readLocked(vaultId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            VaultOrganizationRead.Unavailable
        }
    }

    override suspend fun createAlbum(
        vaultId: VaultId,
        name: String,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultAlbumMutationResult = mutate(vaultId, authorizationCheckpoint) { current ->
        val normalized = VaultAlbumName.normalize(name)
            ?: return@mutate EditPlan.Rejected(VaultAlbumMutationResult.InvalidName("Album names must be non-empty, valid Unicode, and no longer than 100 characters."))
        if (current.albums.size >= VaultOrganizationLimits.MAX_ALBUMS) {
            return@mutate EditPlan.Rejected(VaultAlbumMutationResult.AlbumLimitReached)
        }
        val id = newAlbumId(current.albums.mapTo(HashSet()) { it.id })
            ?: return@mutate EditPlan.Rejected(VaultAlbumMutationResult.WriteFailed)
        EditPlan.Updated(current.albums + VaultAlbum(id, normalized, emptyList()), id)
    }

    override suspend fun renameAlbum(
        vaultId: VaultId,
        albumId: VaultAlbumId,
        name: String,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultAlbumMutationResult = mutate(vaultId, authorizationCheckpoint) { current ->
        val normalized = VaultAlbumName.normalize(name)
            ?: return@mutate EditPlan.Rejected(VaultAlbumMutationResult.InvalidName("Album names must be non-empty, valid Unicode, and no longer than 100 characters."))
        if (current.albums.none { it.id == albumId }) return@mutate EditPlan.Rejected(VaultAlbumMutationResult.AlbumNotFound)
        val renamed = current.albums.map { album ->
            if (album.id == albumId) VaultAlbum(album.id, normalized, album.memberItemIds) else album
        }
        EditPlan.Updated(renamed, albumId)
    }

    override suspend fun deleteAlbum(
        vaultId: VaultId,
        albumId: VaultAlbumId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultAlbumMutationResult = mutate(vaultId, authorizationCheckpoint) { current ->
        if (current.albums.none { it.id == albumId }) return@mutate EditPlan.Rejected(VaultAlbumMutationResult.AlbumNotFound)
        // Deleting this metadata row never opens or deletes an encrypted object.
        EditPlan.Updated(current.albums.filterNot { it.id == albumId }, albumId)
    }

    override suspend fun addMembership(
        vaultId: VaultId,
        albumId: VaultAlbumId,
        itemId: VaultItemId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultAlbumMutationResult = mutate(vaultId, authorizationCheckpoint) { current ->
        val album = current.albums.firstOrNull { it.id == albumId }
            ?: return@mutate EditPlan.Rejected(VaultAlbumMutationResult.AlbumNotFound)
        when (val indexed = index.inspect(vaultId)) {
            is VaultIndexRead.Ready -> {
                val memberItem = indexed.items.firstOrNull { it.id == itemId }
                    ?: return@mutate EditPlan.Rejected(VaultAlbumMutationResult.ItemNotFound)
                if (memberItem.lifecycle == VaultItemLifecycle.TRASHED) {
                    return@mutate EditPlan.Rejected(VaultAlbumMutationResult.ItemTrashed)
                }
            }
            else -> return@mutate EditPlan.Rejected(mapIndexFailure(indexed))
        }
        if (itemId in album.memberItemIds) return@mutate EditPlan.Rejected(VaultAlbumMutationResult.AlreadyMember)
        if (album.memberItemIds.size >= VaultOrganizationLimits.MAX_ITEMS_PER_ALBUM ||
            current.albums.sumOf { it.memberItemIds.size.toLong() } >= VaultOrganizationLimits.MAX_TOTAL_MEMBERSHIPS
        ) return@mutate EditPlan.Rejected(VaultAlbumMutationResult.MembershipLimitReached)
        val updated = current.albums.map { candidate ->
            if (candidate.id == albumId) VaultAlbum(candidate.id, candidate.name, candidate.memberItemIds + itemId)
            else candidate
        }
        EditPlan.Updated(updated, albumId)
    }

    override suspend fun removeMembership(
        vaultId: VaultId,
        albumId: VaultAlbumId,
        itemId: VaultItemId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultAlbumMutationResult = mutate(vaultId, authorizationCheckpoint) { current ->
        val album = current.albums.firstOrNull { it.id == albumId }
            ?: return@mutate EditPlan.Rejected(VaultAlbumMutationResult.AlbumNotFound)
        if (itemId !in album.memberItemIds) return@mutate EditPlan.Rejected(VaultAlbumMutationResult.NotMember)
        val updated = current.albums.map { candidate ->
            if (candidate.id == albumId) VaultAlbum(candidate.id, candidate.name, candidate.memberItemIds.filterNot { it == itemId })
            else candidate
        }
        // Stale IDs can be explicitly removed from album metadata; no vault object is touched.
        EditPlan.Updated(updated, albumId)
    }

    private suspend fun mutate(
        vaultId: VaultId,
        authorizationCheckpoint: suspend () -> Boolean,
        edit: suspend (VaultOrganizationSnapshot) -> EditPlan,
    ): VaultAlbumMutationResult = mutex.withLock {
        try {
            if (!authorizationCheckpoint()) return@withLock VaultAlbumMutationResult.AuthorizationExpired
            val current = when (val read = readLocked(vaultId)) {
                is VaultOrganizationRead.Ready -> read.snapshot
                else -> return@withLock read.toMutationFailure()
            }
            val plan = edit(current)
            if (plan is EditPlan.Rejected) return@withLock plan.result
            plan as EditPlan.Updated
            if (!authorizationCheckpoint()) return@withLock VaultAlbumMutationResult.AuthorizationExpired
            if (current.generation == Long.MAX_VALUE) return@withLock VaultAlbumMutationResult.WriteFailed
            val next = try {
                VaultOrganizationSnapshot(current.generation + 1, plan.albums.sortedBy { it.id.value })
            } catch (_: Exception) {
                return@withLock VaultAlbumMutationResult.WriteFailed
            }
            val plaintext = try { VaultOrganizationCodec.encode(vaultId, next) }
            catch (_: Exception) { return@withLock VaultAlbumMutationResult.WriteFailed }
            val stored = try {
                when (val encrypted = crypto.encryptOrganization(vaultId, next.generation, plaintext)) {
                    is VaultContentCryptoResult.Success -> encrypted.value
                    VaultContentCryptoResult.AuthorizationExpired -> return@withLock VaultAlbumMutationResult.AuthorizationExpired
                    else -> return@withLock encrypted.toMutationFailure()
                }
            } finally { plaintext.fill(0) }
            try {
                when (storage.commitOrganizationFileAtomically(next.generation, stored, authorizationCheckpoint)) {
                    VaultOrganizationStorageResult.Created -> Unit
                    VaultOrganizationStorageResult.AuthorizationExpired -> return@withLock VaultAlbumMutationResult.AuthorizationExpired
                    else -> return@withLock VaultAlbumMutationResult.WriteFailed
                }
            } finally { stored.fill(0) }

            if (!authorizationCheckpoint()) return@withLock VaultAlbumMutationResult.AuthorizationExpired
            val verified = readLocked(vaultId)
            if (verified !is VaultOrganizationRead.Ready || verified.snapshot != next) {
                // The prior authenticated generation is retained. Remove only this unverified write where possible.
                storage.discardOrganizationFile(next.generation)
                return@withLock if (verified is VaultOrganizationRead.Ready) VaultAlbumMutationResult.WriteFailed
                else verified.toMutationFailure()
            }
            if (!authorizationCheckpoint()) return@withLock VaultAlbumMutationResult.AuthorizationExpired
            val files = storage.inspectOrganizationFiles()
            val generations = (files as? VaultOrganizationFiles.Present)?.generations.orEmpty().sorted()
            val keep = generations.filter { it <= next.generation }.takeLast(2).toSet()
            val pruneResult = if (keep.isNotEmpty()) {
                storage.pruneOrganizationFiles(keep, authorizationCheckpoint)
            } else VaultOrganizationStorageResult.Unavailable
            if (pruneResult == VaultOrganizationStorageResult.AuthorizationExpired) {
                return@withLock VaultAlbumMutationResult.AuthorizationExpired
            }
            val pruned = pruneResult == VaultOrganizationStorageResult.Created
            VaultAlbumMutationResult.Changed(verified.snapshot, plan.affectedAlbumId, pruned)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            VaultAlbumMutationResult.WriteFailed
        }
    }

    private suspend fun readLocked(vaultId: VaultId): VaultOrganizationRead {
        val files = when (val inspected = storage.inspectOrganizationFiles()) {
            VaultOrganizationFiles.Missing -> return VaultOrganizationRead.Ready(VaultOrganizationSnapshot(0, emptyList()))
            is VaultOrganizationFiles.Present -> inspected
            VaultOrganizationFiles.Unavailable -> return VaultOrganizationRead.Unavailable
            VaultOrganizationFiles.AccessDenied -> return VaultOrganizationRead.AccessDenied
        }
        if (files.hasPendingWrite || files.hasUnexpectedEntries) return VaultOrganizationRead.Corrupt
        if (files.generations.isEmpty()) return VaultOrganizationRead.Ready(VaultOrganizationSnapshot(0, emptyList()))
        if (files.generations.size > 256 || files.generations.any { it < 1 } || files.generations.toSet().size != files.generations.size) {
            return VaultOrganizationRead.Corrupt
        }
        val generation = files.generations.maxOrNull() ?: return VaultOrganizationRead.Corrupt
        val encoded = storage.readOrganizationFile(generation) ?: return VaultOrganizationRead.Unavailable
        return try {
            when (val decrypted = crypto.decryptOrganization(vaultId, generation, encoded)) {
                is VaultContentCryptoResult.Success -> {
                    val plaintext = decrypted.value
                    try {
                        when (val decoded = VaultOrganizationCodec.decode(plaintext)) {
                            is VaultOrganizationCodec.DecodeResult.Decoded -> {
                                if (decoded.vaultId != vaultId || decoded.snapshot.generation != generation) {
                                    VaultOrganizationRead.Corrupt
                                } else VaultOrganizationRead.Ready(decoded.snapshot)
                            }
                            is VaultOrganizationCodec.DecodeResult.Unsupported -> VaultOrganizationRead.UnsupportedVersion(decoded.version)
                            VaultOrganizationCodec.DecodeResult.Invalid -> VaultOrganizationRead.Corrupt
                        }
                    } finally { plaintext.fill(0) }
                }
                VaultContentCryptoResult.AuthenticationFailed,
                VaultContentCryptoResult.SourceSizeMismatch -> VaultOrganizationRead.Corrupt
                is VaultContentCryptoResult.UnsupportedVersion -> VaultOrganizationRead.UnsupportedVersion(decrypted.version)
                is VaultContentCryptoResult.VaultUnavailable -> VaultOrganizationRead.VaultUnavailable
                VaultContentCryptoResult.AuthorizationExpired -> VaultOrganizationRead.Unavailable
                VaultContentCryptoResult.OperationFailed -> VaultOrganizationRead.Unavailable
            }
        } finally { encoded.fill(0) }
    }

    private fun newAlbumId(existing: Set<VaultAlbumId>): VaultAlbumId? {
        repeat(8) {
            val bytes = try { random.generateBytes(VaultAlbumId.BYTE_COUNT) } catch (_: Exception) { return null }
            try {
                if (bytes.size != VaultAlbumId.BYTE_COUNT) return null
                val id = VaultAlbumId.fromBytes(bytes)
                if (id !in existing) return id
            } finally { bytes.fill(0) }
        }
        return null
    }

    private fun mapIndexFailure(indexed: VaultIndexRead): VaultAlbumMutationResult = when (indexed) {
        VaultIndexRead.Missing, VaultIndexRead.ObjectsWithoutIndex, is VaultIndexRead.ContentWithoutIndex ->
            VaultAlbumMutationResult.IndexUnavailable
        VaultIndexRead.Unavailable -> VaultAlbumMutationResult.IndexUnavailable
        VaultIndexRead.AccessDenied -> VaultAlbumMutationResult.AccessDenied
        VaultIndexRead.Corrupt -> VaultAlbumMutationResult.Corrupt
        is VaultIndexRead.UnsupportedVersion -> VaultAlbumMutationResult.UnsupportedVersion
        is VaultIndexRead.VaultUnavailable -> VaultAlbumMutationResult.VaultUnavailable
        is VaultIndexRead.Ready -> VaultAlbumMutationResult.ItemNotFound
    }

    private fun VaultOrganizationRead.toMutationFailure(): VaultAlbumMutationResult = when (this) {
        VaultOrganizationRead.Corrupt -> VaultAlbumMutationResult.Corrupt
        is VaultOrganizationRead.UnsupportedVersion -> VaultAlbumMutationResult.UnsupportedVersion
        VaultOrganizationRead.Unavailable -> VaultAlbumMutationResult.OrganizationUnavailable
        VaultOrganizationRead.AccessDenied -> VaultAlbumMutationResult.AccessDenied
        VaultOrganizationRead.VaultUnavailable -> VaultAlbumMutationResult.VaultUnavailable
        is VaultOrganizationRead.Ready -> VaultAlbumMutationResult.WriteFailed
    }

    private fun VaultContentCryptoResult<*>.toMutationFailure(): VaultAlbumMutationResult = when (this) {
        is VaultContentCryptoResult.VaultUnavailable -> VaultAlbumMutationResult.VaultUnavailable
        VaultContentCryptoResult.AuthenticationFailed -> VaultAlbumMutationResult.Corrupt
        is VaultContentCryptoResult.UnsupportedVersion -> VaultAlbumMutationResult.UnsupportedVersion
        VaultContentCryptoResult.OperationFailed,
        VaultContentCryptoResult.SourceSizeMismatch -> VaultAlbumMutationResult.WriteFailed
        VaultContentCryptoResult.AuthorizationExpired -> VaultAlbumMutationResult.AuthorizationExpired
        is VaultContentCryptoResult.Success -> VaultAlbumMutationResult.WriteFailed
    }

    private sealed interface EditPlan {
        data class Updated(val albums: List<VaultAlbum>, val affectedAlbumId: VaultAlbumId?) : EditPlan
        data class Rejected(val result: VaultAlbumMutationResult) : EditPlan
    }
}
