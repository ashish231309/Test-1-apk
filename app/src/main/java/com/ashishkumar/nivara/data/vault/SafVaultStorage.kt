package com.ashishkumar.nivara.data.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ashishkumar.nivara.domain.vault.VaultDirectoryEntry
import com.ashishkumar.nivara.domain.vault.VaultMetadataFile
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.VaultMetadataCodec
import com.ashishkumar.nivara.domain.vault.VaultMetadataDecode
import com.ashishkumar.nivara.domain.vault.VaultRecoveryFile
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRecordCodec
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRecordDecode
import com.ashishkumar.nivara.domain.vault.VaultStorage
import com.ashishkumar.nivara.domain.vault.VaultStorageCommitResult
import com.ashishkumar.nivara.domain.vault.VaultStorageSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/** SAF-backed storage rooted only at the exact user-selected and persistently granted document tree. */
class SafVaultStorage internal constructor(
    context: Context,
    private val locations: VaultLocationStore,
) : VaultStorage {
    private val applicationContext = context.applicationContext
    private val resolver: ContentResolver = applicationContext.contentResolver
    private val mutex = Mutex()

    override suspend fun inspect(): VaultStorageSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock { inspectLocked() }
    }

    override suspend fun initializeAtomically(metadataBytes: ByteArray): VaultStorageCommitResult =
        withContext(Dispatchers.IO) {
            mutex.withLock { initializeLocked(metadataBytes) }
        }

    override suspend fun commitRecoveryRecordAtomically(
        expectedVaultId: com.ashishkumar.nivara.domain.vault.VaultId,
        recordBytes: ByteArray,
    ): VaultStorageCommitResult = withContext(Dispatchers.IO) {
        mutex.withLock { commitRecoveryRecordLocked(expectedVaultId, recordBytes) }
    }

    private fun inspectLocked(): VaultStorageSnapshot {
        val tree = selectedAccessibleTree() ?: return when (selectionFailure) {
            SelectionFailure.NONE -> VaultStorageSnapshot.RootNotSelected
            SelectionFailure.DENIED -> VaultStorageSnapshot.AccessDenied
            SelectionFailure.UNAVAILABLE -> VaultStorageSnapshot.Unavailable
        }
        return try {
            val children = listChildren(tree)
            val metadataMatches = children.filter { it.name == METADATA_NAME }
            val dataMatches = children.filter { it.name == DATA_DIRECTORY_NAME }
            val recoveryMatches = children.filter { it.name == RECOVERY_RECORD_NAME }
            val recoveryPending = children.any { it.name == TEMPORARY_RECOVERY_RECORD_NAME }
            val recoveryRecord = when {
                recoveryPending -> VaultRecoveryFile.Pending
                recoveryMatches.size > 1 -> VaultRecoveryFile.WrongType
                recoveryMatches.isEmpty() -> VaultRecoveryFile.Missing
                recoveryMatches.single().mimeType == Document.MIME_TYPE_DIR -> VaultRecoveryFile.WrongType
                else -> readRecoveryRecord(recoveryMatches.single().uri)
            }
            val metadata = when {
                metadataMatches.size > 1 -> VaultMetadataFile.WrongType
                metadataMatches.isEmpty() -> VaultMetadataFile.Missing
                metadataMatches.single().mimeType == Document.MIME_TYPE_DIR -> VaultMetadataFile.WrongType
                else -> readMetadata(metadataMatches.single().uri)
            }
            val dataDirectory = when {
                dataMatches.size > 1 -> VaultDirectoryEntry.WRONG_TYPE
                dataMatches.isEmpty() -> VaultDirectoryEntry.MISSING
                dataMatches.single().mimeType == Document.MIME_TYPE_DIR -> VaultDirectoryEntry.DIRECTORY
                else -> VaultDirectoryEntry.WRONG_TYPE
            }
            val unexpected = children.any {
                it.name != METADATA_NAME && it.name != DATA_DIRECTORY_NAME &&
                    it.name != RECOVERY_RECORD_NAME && it.name != TEMPORARY_RECOVERY_RECORD_NAME
            }
            val unexpectedDataEntries = if (dataDirectory == VaultDirectoryEntry.DIRECTORY) {
                val entries = listChildren(tree, dataMatches.single().uri)
                entries.any { entry ->
                    entry.name !in CONTENT_DIRECTORY_NAMES || entry.mimeType != Document.MIME_TYPE_DIR
                } || CONTENT_DIRECTORY_NAMES.any { expected -> entries.count { it.name == expected } > 1 }
            } else false
            VaultStorageSnapshot.Available(
                metadata = metadata,
                dataDirectory = dataDirectory,
                unexpectedEntries = unexpected,
                unexpectedDataEntries = unexpectedDataEntries,
                recoveryRecord = recoveryRecord,
            )
        } catch (_: SecurityException) {
            VaultStorageSnapshot.AccessDenied
        } catch (_: FileNotFoundException) {
            VaultStorageSnapshot.Unavailable
        } catch (_: IOException) {
            VaultStorageSnapshot.Unavailable
        } catch (_: Exception) {
            VaultStorageSnapshot.Unavailable
        }
    }

    private fun readMetadata(uri: Uri): VaultMetadataFile = try {
        val input = resolver.openInputStream(uri) ?: return VaultMetadataFile.Unavailable
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_METADATA_BYTES) return VaultMetadataFile.Unreadable
                output.write(buffer, 0, count)
            }
            VaultMetadataFile.Present(output.toByteArray())
        }
    } catch (_: SecurityException) {
        VaultMetadataFile.AccessDenied
    } catch (_: FileNotFoundException) {
        VaultMetadataFile.Unavailable
    } catch (_: IOException) {
        VaultMetadataFile.Unreadable
    } catch (_: Exception) {
        VaultMetadataFile.Unavailable
    }

    private suspend fun commitRecoveryRecordLocked(
        expectedVaultId: VaultId,
        recordBytes: ByteArray,
    ): VaultStorageCommitResult {
        if (recordBytes.isEmpty() || recordBytes.size > MAX_RECOVERY_RECORD_BYTES) {
            return VaultStorageCommitResult.MetadataWriteFailed
        }
        val tree = try { selectedAccessibleTree() }
        catch (_: SecurityException) { return VaultStorageCommitResult.AccessDenied }
        catch (_: Exception) { return VaultStorageCommitResult.Unavailable }
            ?: return when (selectionFailure) {
                SelectionFailure.NONE, SelectionFailure.UNAVAILABLE -> VaultStorageCommitResult.Unavailable
                SelectionFailure.DENIED -> VaultStorageCommitResult.AccessDenied
            }

        var temporary: Uri? = null
        var finalRecord: Uri? = null
        try {
            val beforeSnapshot = inspectLocked()
            val before = beforeSnapshot as? VaultStorageSnapshot.Available
                ?: return when (beforeSnapshot) {
                    VaultStorageSnapshot.AccessDenied -> VaultStorageCommitResult.AccessDenied
                    VaultStorageSnapshot.RootNotSelected, VaultStorageSnapshot.Unavailable ->
                        VaultStorageCommitResult.Unavailable
                    is VaultStorageSnapshot.Available -> VaultStorageCommitResult.VerificationFailed
                }
            val metadata = before.metadata as? VaultMetadataFile.Present
                ?: return when (before.metadata) {
                    VaultMetadataFile.AccessDenied -> VaultStorageCommitResult.AccessDenied
                    VaultMetadataFile.Unavailable -> VaultStorageCommitResult.Unavailable
                    else -> VaultStorageCommitResult.VerificationFailed
                }
            val metadataVaultId = try {
                when (val decoded = VaultMetadataCodec.decode(metadata.bytes)) {
                    is VaultMetadataDecode.Supported -> decoded.metadata.vaultId
                    else -> null
                }
            } finally { metadata.bytes.fill(0) }
            val recordVaultId = when (val decoded = VaultRecoveryRecordCodec.decode(recordBytes)) {
                is VaultRecoveryRecordDecode.Supported -> {
                    decoded.record.wrappedContentKey.fill(0)
                    decoded.record.vaultId
                }
                else -> null
            }
            if (metadataVaultId != expectedVaultId || recordVaultId != expectedVaultId) {
                return VaultStorageCommitResult.VerificationFailed
            }
            if (before.dataDirectory != VaultDirectoryEntry.DIRECTORY || before.unexpectedEntries ||
                before.unexpectedDataEntries
            ) return VaultStorageCommitResult.VerificationFailed
            when (before.recoveryRecord) {
                VaultRecoveryFile.Missing -> Unit
                VaultRecoveryFile.AccessDenied -> return VaultStorageCommitResult.AccessDenied
                VaultRecoveryFile.Unavailable -> return VaultStorageCommitResult.Unavailable
                VaultRecoveryFile.Pending, VaultRecoveryFile.WrongType, VaultRecoveryFile.Unreadable,
                is VaultRecoveryFile.Present -> return VaultStorageCommitResult.RecoveryRecordExists
            }

            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            temporary = DocumentsContract.createDocument(
                resolver, root, MIME_TYPE, TEMPORARY_RECOVERY_RECORD_NAME,
            ) ?: return VaultStorageCommitResult.MetadataWriteFailed
            val tempDocument = listChildren(tree).firstOrNull { it.name == TEMPORARY_RECOVERY_RECORD_NAME }
                ?: return cleanupRecoveryTemporary(tree, temporary, VaultStorageCommitResult.VerificationFailed)
            if ((tempDocument.flags and Document.FLAG_SUPPORTS_RENAME) == 0) {
                return cleanupRecoveryTemporary(tree, temporary, VaultStorageCommitResult.AtomicCommitFailed)
            }
            val output = resolver.openOutputStream(temporary, "w")
                ?: return cleanupRecoveryTemporary(tree, temporary, VaultStorageCommitResult.MetadataWriteFailed)
            output.use { stream -> stream.write(recordBytes); stream.flush() }
            val staged = readRaw(temporary)
                ?: return cleanupRecoveryTemporary(tree, temporary, VaultStorageCommitResult.VerificationFailed)
            val stagedMatches = staged.contentEquals(recordBytes)
            staged.fill(0)
            if (!stagedMatches) {
                return cleanupRecoveryTemporary(tree, temporary, VaultStorageCommitResult.VerificationFailed)
            }

            finalRecord = DocumentsContract.renameDocument(resolver, temporary, RECOVERY_RECORD_NAME)
                ?: return cleanupRecoveryTemporary(tree, temporary, VaultStorageCommitResult.AtomicCommitFailed)
            temporary = null
            val committedBytes = readRaw(finalRecord)
                ?: return VaultStorageCommitResult.VerificationFailed
            val committedMatches = committedBytes.contentEquals(recordBytes)
            committedBytes.fill(0)
            if (!committedMatches) return VaultStorageCommitResult.VerificationFailed

            val after = inspectLocked() as? VaultStorageSnapshot.Available
                ?: return VaultStorageCommitResult.VerificationFailed
            val persisted = after.recoveryRecord as? VaultRecoveryFile.Present
                ?: return VaultStorageCommitResult.VerificationFailed
            val matches = persisted.bytes.contentEquals(recordBytes)
            persisted.bytes.fill(0)
            return if (matches && !after.unexpectedEntries && !after.unexpectedDataEntries) {
                VaultStorageCommitResult.Created
            } else VaultStorageCommitResult.VerificationFailed
        } catch (failure: CancellationException) {
            temporary?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw failure
        } catch (_: SecurityException) {
            return cleanupRecoveryTemporary(tree, temporary, VaultStorageCommitResult.AccessDenied)
        } catch (_: FileNotFoundException) {
            return cleanupRecoveryTemporary(tree, temporary, VaultStorageCommitResult.Unavailable)
        } catch (_: IOException) {
            return cleanupRecoveryTemporary(tree, temporary, VaultStorageCommitResult.Failed)
        } catch (_: Exception) {
            return cleanupRecoveryTemporary(tree, temporary, VaultStorageCommitResult.Failed)
        }
    }

    private fun cleanupRecoveryTemporary(
        tree: Uri,
        temporary: Uri?,
        result: VaultStorageCommitResult,
    ): VaultStorageCommitResult {
        if (temporary == null) return result
        return try {
            if (DocumentsContract.deleteDocument(resolver, temporary)) result
            else VaultStorageCommitResult.CleanupFailed
        } catch (_: Exception) {
            VaultStorageCommitResult.CleanupFailed
        }
    }

    private suspend fun initializeLocked(metadataBytes: ByteArray): VaultStorageCommitResult {
        if (metadataBytes.isEmpty() || metadataBytes.size > MAX_METADATA_BYTES) {
            return VaultStorageCommitResult.MetadataWriteFailed
        }
        val tree = try {
            selectedAccessibleTree()
        } catch (_: SecurityException) {
            return VaultStorageCommitResult.AccessDenied
        } catch (_: Exception) {
            return VaultStorageCommitResult.Unavailable
        } ?: return when (selectionFailure) {
            SelectionFailure.NONE -> VaultStorageCommitResult.Unavailable
            SelectionFailure.DENIED -> VaultStorageCommitResult.AccessDenied
            SelectionFailure.UNAVAILABLE -> VaultStorageCommitResult.Unavailable
        }

        var dataDirectory: Uri? = null
        var temporaryMetadata: Uri? = null
        var finalMetadata: Uri? = null
        var phase = CommitPhase.ROOT_VALIDATION
        try {
            if (listChildren(tree).isNotEmpty()) return VaultStorageCommitResult.RootNotEmpty
            phase = CommitPhase.DIRECTORY_CREATION
            val rootDocumentId = DocumentsContract.getTreeDocumentId(tree)
            dataDirectory = DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, rootDocumentId),
                Document.MIME_TYPE_DIR,
                DATA_DIRECTORY_NAME,
            ) ?: return VaultStorageCommitResult.DirectoryCreationFailed
            phase = CommitPhase.METADATA_WRITE
            temporaryMetadata = DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, rootDocumentId),
                MIME_TYPE,
                TEMPORARY_METADATA_NAME,
            ) ?: return cleanupAnd(tree, VaultStorageCommitResult.MetadataWriteFailed, temporaryMetadata, dataDirectory, finalMetadata)
            val temporaryDocument = listChildren(tree).firstOrNull { it.name == TEMPORARY_METADATA_NAME }
                ?: return cleanupAnd(tree, VaultStorageCommitResult.VerificationFailed, temporaryMetadata, dataDirectory, finalMetadata)
            if ((temporaryDocument.flags and Document.FLAG_SUPPORTS_RENAME) == 0) {
                return cleanupAnd(tree, VaultStorageCommitResult.AtomicCommitFailed, temporaryMetadata, dataDirectory, finalMetadata)
            }

            val output = resolver.openOutputStream(temporaryMetadata, "w")
                ?: return cleanupAnd(tree, VaultStorageCommitResult.MetadataWriteFailed, temporaryMetadata, dataDirectory, finalMetadata)
            output.use { stream ->
                stream.write(metadataBytes)
                stream.flush()
            }
            phase = CommitPhase.TEMPORARY_VERIFICATION
            val temporaryBytes = readRaw(temporaryMetadata)
                ?: return cleanupAnd(tree, VaultStorageCommitResult.VerificationFailed, temporaryMetadata, dataDirectory, finalMetadata)
            val temporaryMatches = temporaryBytes.contentEquals(metadataBytes)
            temporaryBytes.fill(0)
            if (!temporaryMatches) {
                return cleanupAnd(tree, VaultStorageCommitResult.VerificationFailed, temporaryMetadata, dataDirectory, finalMetadata)
            }

            phase = CommitPhase.ATOMIC_COMMIT
            finalMetadata = DocumentsContract.renameDocument(resolver, temporaryMetadata, METADATA_NAME)
                ?: return cleanupAnd(tree, VaultStorageCommitResult.AtomicCommitFailed, temporaryMetadata, dataDirectory, finalMetadata)
            val pendingAfterRename = listChildren(tree).firstOrNull { it.name == TEMPORARY_METADATA_NAME }
            temporaryMetadata = pendingAfterRename?.uri
            phase = CommitPhase.FINAL_VERIFICATION
            val finalBytes = readRaw(finalMetadata)
                ?: return cleanupAnd(tree, VaultStorageCommitResult.VerificationFailed, temporaryMetadata, dataDirectory, finalMetadata)
            val finalMatches = finalBytes.contentEquals(metadataBytes)
            finalBytes.fill(0)
            if (!finalMatches) {
                return cleanupAnd(tree, VaultStorageCommitResult.VerificationFailed, temporaryMetadata, dataDirectory, finalMetadata)
            }
            val finalSnapshot = inspectLocked() as? VaultStorageSnapshot.Available
                ?: return cleanupAnd(tree, VaultStorageCommitResult.VerificationFailed, temporaryMetadata, dataDirectory, finalMetadata)
            if (finalSnapshot.metadata !is VaultMetadataFile.Present ||
                finalSnapshot.dataDirectory != VaultDirectoryEntry.DIRECTORY || finalSnapshot.unexpectedEntries ||
                finalSnapshot.unexpectedDataEntries
            ) {
                (finalSnapshot.metadata as? VaultMetadataFile.Present)?.bytes?.fill(0)
                return cleanupAnd(tree, VaultStorageCommitResult.VerificationFailed, temporaryMetadata, dataDirectory, finalMetadata)
            }
            val persistedBytes = (finalSnapshot.metadata as VaultMetadataFile.Present).bytes
            val persistedMatches = persistedBytes.contentEquals(metadataBytes)
            persistedBytes.fill(0)
            if (!persistedMatches) {
                return cleanupAnd(tree, VaultStorageCommitResult.VerificationFailed, temporaryMetadata, dataDirectory, finalMetadata)
            }
            return VaultStorageCommitResult.Created
        } catch (failure: CancellationException) {
            cleanupAnd(tree, VaultStorageCommitResult.VerificationFailed, temporaryMetadata, dataDirectory, finalMetadata)
            throw failure
        } catch (_: SecurityException) {
            return cleanupAnd(tree, VaultStorageCommitResult.AccessDenied, temporaryMetadata, dataDirectory, finalMetadata)
        } catch (_: FileNotFoundException) {
            return cleanupAnd(tree, VaultStorageCommitResult.Unavailable, temporaryMetadata, dataDirectory, finalMetadata)
        } catch (_: IOException) {
            return cleanupAnd(tree, failureFor(phase), temporaryMetadata, dataDirectory, finalMetadata)
        } catch (_: Exception) {
            return cleanupAnd(tree, failureFor(phase), temporaryMetadata, dataDirectory, finalMetadata)
        }
    }

    private fun failureFor(phase: CommitPhase): VaultStorageCommitResult = when (phase) {
        CommitPhase.ROOT_VALIDATION -> VaultStorageCommitResult.Unavailable
        CommitPhase.DIRECTORY_CREATION -> VaultStorageCommitResult.DirectoryCreationFailed
        CommitPhase.METADATA_WRITE -> VaultStorageCommitResult.MetadataWriteFailed
        CommitPhase.TEMPORARY_VERIFICATION,
        CommitPhase.FINAL_VERIFICATION -> VaultStorageCommitResult.VerificationFailed
        CommitPhase.ATOMIC_COMMIT -> VaultStorageCommitResult.AtomicCommitFailed
    }

    private fun cleanupAnd(
        treeUri: Uri,
        result: VaultStorageCommitResult,
        temporary: Uri?,
        dataDirectory: Uri?,
        finalMetadata: Uri?,
    ): VaultStorageCommitResult {
        var cleaned = true
        if (temporary != null && temporary != finalMetadata) cleaned = deleteDocument(temporary) && cleaned
        if (finalMetadata != null) cleaned = deleteDocument(finalMetadata) && cleaned
        if (dataDirectory != null) {
            val empty = try {
                listChildren(treeUri, dataDirectory).isEmpty()
            } catch (_: Exception) {
                false
            }
            if (empty) cleaned = deleteDocument(dataDirectory) && cleaned else cleaned = false
        }
        val rootEmpty = try {
            listChildren(treeUri).isEmpty()
        } catch (_: Exception) {
            false
        }
        if (!rootEmpty) cleaned = false
        return if (cleaned) result else VaultStorageCommitResult.CleanupFailed
    }

    private fun deleteDocument(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: Exception) {
        false
    }

    private fun readRaw(uri: Uri): ByteArray? = try {
        val input = resolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_METADATA_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (_: Exception) {
        null
    }

    private fun readRecoveryRecord(uri: Uri): VaultRecoveryFile = try {
        val input = resolver.openInputStream(uri) ?: return VaultRecoveryFile.Unavailable
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RECOVERY_RECORD_BYTES) {
                    buffer.fill(0)
                    return VaultRecoveryFile.Unreadable
                }
                output.write(buffer, 0, count)
            }
            buffer.fill(0)
            VaultRecoveryFile.Present(output.toByteArray())
        }
    } catch (_: SecurityException) {
        VaultRecoveryFile.AccessDenied
    } catch (_: FileNotFoundException) {
        VaultRecoveryFile.Unavailable
    } catch (_: IOException) {
        VaultRecoveryFile.Unreadable
    } catch (_: Exception) {
        VaultRecoveryFile.Unavailable
    }

    private fun listChildren(treeUri: Uri, parentDocument: Uri? = null): List<ChildDocument> {
        val parentId = if (parentDocument == null) {
            DocumentsContract.getTreeDocumentId(treeUri)
        } else {
            DocumentsContract.getDocumentId(parentDocument)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val result = ArrayList<ChildDocument>()
        val cursor = resolver.query(
            childrenUri,
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_FLAGS,
            ),
            null,
            null,
            null,
        ) ?: throw IOException("The selected document provider returned no directory listing.")
        cursor.use { rows ->
            var count = 0
            while (rows.moveToNext()) {
                count++
                if (count > MAX_ROOT_ENTRIES) throw IOException("The selected vault root has too many entries.")
                val id = rows.getString(0) ?: throw IOException("A document entry has no identity.")
                val name = rows.getString(1) ?: throw IOException("A document entry has no name.")
                val mime = rows.getString(2) ?: throw IOException("A document entry has no type.")
                val flags = rows.getLong(3).toInt()
                result += ChildDocument(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    name,
                    mime,
                    flags,
                )
            }
        }
        return result
    }

    private var selectionFailure: SelectionFailure = SelectionFailure.NONE

    private fun selectedAccessibleTree(): Uri? {
        selectionFailure = SelectionFailure.NONE
        val treeUri = try {
            locations.selectedTreeUri()
        } catch (_: Exception) {
            selectionFailure = SelectionFailure.UNAVAILABLE
            return null
        } ?: return null
        if (!DocumentsContract.isTreeUri(treeUri)) {
            selectionFailure = SelectionFailure.UNAVAILABLE
            return null
        }
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        if (permission == null || !permission.isReadPermission || !permission.isWritePermission) {
            selectionFailure = SelectionFailure.DENIED
            return null
        }
        return treeUri
    }

    private data class ChildDocument(val uri: Uri, val name: String, val mimeType: String, val flags: Int)
    private enum class CommitPhase {
        ROOT_VALIDATION,
        DIRECTORY_CREATION,
        METADATA_WRITE,
        TEMPORARY_VERIFICATION,
        ATOMIC_COMMIT,
        FINAL_VERIFICATION,
    }
    private enum class SelectionFailure { NONE, DENIED, UNAVAILABLE }

    private companion object {
        const val METADATA_NAME = "vault.nvmeta"
        const val TEMPORARY_METADATA_NAME = "vault.nvmeta.pending"
        const val RECOVERY_RECORD_NAME = "vault.nvrec"
        const val TEMPORARY_RECOVERY_RECORD_NAME = "vault.nvrec.pending"
        const val DATA_DIRECTORY_NAME = "data"
        val CONTENT_DIRECTORY_NAMES = setOf("index", "objects", "organization")
        const val MIME_TYPE = "application/octet-stream"
        const val MAX_METADATA_BYTES = 16 * 1024
        const val MAX_RECOVERY_RECORD_BYTES = 16 * 1024
        const val MAX_ROOT_ENTRIES = 10_000
    }
}
