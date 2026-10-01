package com.ashishkumar.nivara.data.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.vault.content.VaultContentObjectCodec
import com.ashishkumar.nivara.domain.vault.content.VaultContentStorage
import com.ashishkumar.nivara.domain.vault.content.VaultIndexCodec
import com.ashishkumar.nivara.domain.vault.content.VaultIndexEnvelopeCodec
import com.ashishkumar.nivara.domain.vault.content.VaultIndexFileCommitResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexFiles
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationFiles
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationLimits
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationStorageResult
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultObjectCommitResult
import com.ashishkumar.nivara.domain.vault.content.VaultObjectDirectoryRead
import com.ashishkumar.nivara.domain.vault.content.VaultObjectWriteInfo
import com.ashishkumar.nivara.domain.vault.content.VaultObjectOpenResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** SAF content adapter. It never derives a path from provider names or source filenames. */
class SafVaultContentStorage internal constructor(
    context: Context,
    private val locations: VaultLocationStore,
    private val random: SecureRandomSource,
) : VaultContentStorage {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val mutex = Mutex()

    override suspend fun initializeContentDirectories(): VaultIndexFileCommitResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val tree = selectedTree() ?: return@withLock selectionResult()
                val structure = structure(tree) ?: return@withLock VaultIndexFileCommitResult.WriteFailed
                val index = ensureDirectory(tree, structure.data, INDEX_DIRECTORY)
                    ?: return@withLock VaultIndexFileCommitResult.WriteFailed
                val objects = ensureDirectory(tree, structure.data, OBJECTS_DIRECTORY)
                    ?: return@withLock VaultIndexFileCommitResult.WriteFailed
                val organization = ensureDirectory(tree, structure.data, ORGANIZATION_DIRECTORY)
                    ?: return@withLock VaultIndexFileCommitResult.WriteFailed
                if (index.mimeType != Document.MIME_TYPE_DIR || objects.mimeType != Document.MIME_TYPE_DIR ||
                    organization.mimeType != Document.MIME_TYPE_DIR
                ) {
                    return@withLock VaultIndexFileCommitResult.WriteFailed
                }
                VaultIndexFileCommitResult.Created
            } catch (_: SecurityException) {
                VaultIndexFileCommitResult.AccessDenied
            } catch (_: FileNotFoundException) {
                VaultIndexFileCommitResult.Unavailable
            } catch (_: Exception) {
                VaultIndexFileCommitResult.WriteFailed
            }
        }
    }

    override suspend fun inspectIndexFiles(): VaultIndexFiles = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val tree = selectedTree() ?: return@withLock when (selectionFailure) {
                    SelectionFailure.DENIED -> VaultIndexFiles.AccessDenied
                    else -> VaultIndexFiles.Unavailable
                }
                val structure = structure(tree) ?: return@withLock VaultIndexFiles.Unavailable
                val index = findUniqueChild(tree, structure.data, INDEX_DIRECTORY)
                val objects = findUniqueChild(tree, structure.data, OBJECTS_DIRECTORY)
                if (index != null && index.mimeType != Document.MIME_TYPE_DIR) return@withLock VaultIndexFiles.Unavailable
                if (objects != null && objects.mimeType != Document.MIME_TYPE_DIR) return@withLock VaultIndexFiles.Unavailable
                val objectExists = objects?.let { hasChildren(tree, it.uri) } ?: false
                if (index == null) {
                    return@withLock VaultIndexFiles.NoIndex(
                        hasPendingWrite = false,
                        hasUnexpectedEntries = false,
                        hasObjects = objectExists,
                    )
                }
                val entries = listChildren(tree, index.uri, MAX_INDEX_GENERATIONS)
                val generations = ArrayList<Long>()
                var pending = false
                var unexpected = false
                val seen = HashSet<Long>()
                entries.forEach { child ->
                    val generation = parseIndexName(child.name)
                    when {
                        generation != null && child.mimeType != Document.MIME_TYPE_DIR -> {
                            if (!seen.add(generation)) unexpected = true else generations += generation
                        }
                        child.name.startsWith(PENDING_PREFIX) -> pending = true
                        else -> unexpected = true
                    }
                }
                if (generations.isEmpty()) {
                    return@withLock VaultIndexFiles.NoIndex(
                        hasPendingWrite = pending,
                        hasUnexpectedEntries = unexpected || objects == null,
                        hasObjects = objectExists,
                    )
                }
                VaultIndexFiles.Present(
                    generations = generations.sorted(),
                    hasPendingWrite = pending,
                    hasUnexpectedEntries = unexpected || objects == null,
                    hasObjects = objectExists,
                )
            } catch (_: SecurityException) {
                VaultIndexFiles.AccessDenied
            } catch (_: FileNotFoundException) {
                VaultIndexFiles.Unavailable
            } catch (_: IOException) {
                VaultIndexFiles.Unavailable
            } catch (_: Exception) {
                VaultIndexFiles.Unavailable
            }
        }
    }

    override suspend fun readIndexFile(generation: Long): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tree = selectedTree() ?: return@withLock null
            val structure = structure(tree) ?: return@withLock null
            val index = findUniqueChild(tree, structure.data, INDEX_DIRECTORY) ?: return@withLock null
            val child = findUniqueChild(tree, index.uri, indexName(generation)) ?: return@withLock null
            if (child.mimeType == Document.MIME_TYPE_DIR) return@withLock null
            readBounded(child.uri, VaultIndexEnvelopeCodec.MAX_ENVELOPE_BYTES)
        }
    }

    override suspend fun inspectOrganizationFiles(): VaultOrganizationFiles = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val tree = selectedTree() ?: return@withLock when (selectionFailure) {
                    SelectionFailure.DENIED -> VaultOrganizationFiles.AccessDenied
                    else -> VaultOrganizationFiles.Unavailable
                }
                val structure = structure(tree) ?: return@withLock VaultOrganizationFiles.Unavailable
                val directory = findUniqueChild(tree, structure.data, ORGANIZATION_DIRECTORY)
                    ?: return@withLock VaultOrganizationFiles.Missing
                if (directory.mimeType != Document.MIME_TYPE_DIR) {
                    return@withLock VaultOrganizationFiles.Present(emptyList(), false, true)
                }
                val listing = organizationListing(tree, directory.uri)
                    ?: return@withLock VaultOrganizationFiles.Unavailable
                VaultOrganizationFiles.Present(listing.generations.sorted(), listing.pending, listing.unexpected)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: SecurityException) {
                VaultOrganizationFiles.AccessDenied
            } catch (_: FileNotFoundException) {
                VaultOrganizationFiles.Unavailable
            } catch (_: IOException) {
                VaultOrganizationFiles.Unavailable
            } catch (_: Exception) {
                VaultOrganizationFiles.Unavailable
            }
        }
    }

    override suspend fun readOrganizationFile(generation: Long): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val tree = selectedTree() ?: return@withLock null
                val structure = structure(tree) ?: return@withLock null
                val directory = findUniqueChild(tree, structure.data, ORGANIZATION_DIRECTORY)
                    ?: return@withLock null
                if (directory.mimeType != Document.MIME_TYPE_DIR) return@withLock null
                val child = findUniqueChild(tree, directory.uri, organizationName(generation))
                    ?: return@withLock null
                if (child.mimeType == Document.MIME_TYPE_DIR) return@withLock null
                readBounded(child.uri, VaultOrganizationLimits.MAX_STORED_RECORD_BYTES)
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { null }
        }
    }

    override suspend fun commitOrganizationFileAtomically(
        generation: Long,
        bytes: ByteArray,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultOrganizationStorageResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (generation < 1 || bytes.isEmpty() || bytes.size > VaultOrganizationLimits.MAX_STORED_RECORD_BYTES) {
                return@withLock VaultOrganizationStorageResult.WriteFailed
            }
            var temporary: Uri? = null
            var committed: Uri? = null
            try {
                val tree = selectedTree() ?: return@withLock organizationSelectionResult()
                val structure = structure(tree) ?: return@withLock VaultOrganizationStorageResult.WriteFailed
                val directory = ensureDirectory(tree, structure.data, ORGANIZATION_DIRECTORY)
                    ?: return@withLock VaultOrganizationStorageResult.WriteFailed
                if (directory.mimeType != Document.MIME_TYPE_DIR) return@withLock VaultOrganizationStorageResult.WriteFailed
                val before = organizationListing(tree, directory.uri)
                    ?: return@withLock VaultOrganizationStorageResult.WriteFailed
                if (before.pending || before.unexpected || before.generations.any { it >= generation } ||
                    (before.generations.maxOrNull() ?: 0L) != generation - 1 ||
                    findUniqueChild(tree, directory.uri, organizationName(generation)) != null
                ) return@withLock VaultOrganizationStorageResult.Conflict
                val suffix = randomSuffix() ?: return@withLock VaultOrganizationStorageResult.WriteFailed
                val pendingName = "$ORGANIZATION_PENDING_PREFIX${organizationName(generation)}-$suffix"
                temporary = DocumentsContract.createDocument(resolver, directory.uri, MIME_TYPE, pendingName)
                    ?: return@withLock VaultOrganizationStorageResult.WriteFailed
                val temporaryDocument = findUniqueChild(tree, directory.uri, pendingName)
                    ?: return@withLock cleanupOrganization(tree, VaultOrganizationStorageResult.VerificationFailed, temporary, null)
                if ((temporaryDocument.flags and Document.FLAG_SUPPORTS_RENAME) == 0) {
                    return@withLock cleanupOrganization(tree, VaultOrganizationStorageResult.WriteFailed, temporary, null)
                }
                writeDocument(temporary!!, bytes)
                val firstReadback = readBounded(temporary!!, VaultOrganizationLimits.MAX_STORED_RECORD_BYTES)
                    ?: return@withLock cleanupOrganization(tree, VaultOrganizationStorageResult.VerificationFailed, temporary, null)
                val firstMatches = firstReadback.contentEquals(bytes)
                firstReadback.fill(0)
                if (!firstMatches) return@withLock cleanupOrganization(tree, VaultOrganizationStorageResult.VerificationFailed, temporary, null)
                if (!authorizationCheckpoint()) {
                    return@withLock cleanupOrganization(tree, VaultOrganizationStorageResult.AuthorizationExpired, temporary, null)
                }
                committed = DocumentsContract.renameDocument(resolver, temporary!!, organizationName(generation))
                    ?: return@withLock cleanupOrganization(tree, VaultOrganizationStorageResult.WriteFailed, temporary, null)
                temporary = null
                val finalDocument = findUniqueChild(tree, directory.uri, organizationName(generation))
                    ?: return@withLock cleanupOrganization(tree, VaultOrganizationStorageResult.VerificationFailed, temporary, committed)
                if (finalDocument.mimeType == Document.MIME_TYPE_DIR) {
                    return@withLock cleanupOrganization(tree, VaultOrganizationStorageResult.VerificationFailed, temporary, committed)
                }
                val finalReadback = readBounded(finalDocument.uri, VaultOrganizationLimits.MAX_STORED_RECORD_BYTES)
                    ?: return@withLock cleanupOrganization(tree, VaultOrganizationStorageResult.VerificationFailed, temporary, committed)
                val finalMatches = finalReadback.contentEquals(bytes)
                finalReadback.fill(0)
                if (!finalMatches || !authorizationCheckpoint()) {
                    val failure = if (finalMatches) VaultOrganizationStorageResult.AuthorizationExpired
                        else VaultOrganizationStorageResult.VerificationFailed
                    return@withLock cleanupOrganization(tree, failure, temporary, committed)
                }
                VaultOrganizationStorageResult.Created
            } catch (failure: CancellationException) {
                cleanupOrganization(null, VaultOrganizationStorageResult.WriteFailed, temporary, committed)
                throw failure
            } catch (_: SecurityException) {
                cleanupOrganization(null, VaultOrganizationStorageResult.AccessDenied, temporary, committed)
            } catch (_: FileNotFoundException) {
                cleanupOrganization(null, VaultOrganizationStorageResult.Unavailable, temporary, committed)
            } catch (_: IOException) {
                cleanupOrganization(null, VaultOrganizationStorageResult.WriteFailed, temporary, committed)
            } catch (_: Exception) {
                cleanupOrganization(null, VaultOrganizationStorageResult.WriteFailed, temporary, committed)
            }
        }
    }

    override suspend fun pruneOrganizationFiles(
        keepGenerations: Set<Long>,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultOrganizationStorageResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val tree = selectedTree() ?: return@withLock organizationSelectionResult()
                    val structure = structure(tree) ?: return@withLock VaultOrganizationStorageResult.Unavailable
                    val directory = findUniqueChild(tree, structure.data, ORGANIZATION_DIRECTORY)
                        ?: return@withLock VaultOrganizationStorageResult.Unavailable
                    if (directory.mimeType != Document.MIME_TYPE_DIR) return@withLock VaultOrganizationStorageResult.WriteFailed
                    val listing = organizationListing(tree, directory.uri)
                        ?: return@withLock VaultOrganizationStorageResult.Unavailable
                    if (listing.pending || listing.unexpected || listing.generations.isEmpty() ||
                        listing.generations.maxOrNull() !in keepGenerations || keepGenerations.any { it !in listing.generations }
                    ) return@withLock VaultOrganizationStorageResult.Conflict
                    if (!authorizationCheckpoint()) return@withLock VaultOrganizationStorageResult.AuthorizationExpired
                    var success = true
                    for (generation in listing.generations.filter { it !in keepGenerations }) {
                        if (!authorizationCheckpoint()) return@withLock VaultOrganizationStorageResult.AuthorizationExpired
                        val child = findUniqueChild(tree, directory.uri, organizationName(generation))
                        if (child == null || !delete(child.uri)) success = false
                    }
                    if (!authorizationCheckpoint()) return@withLock VaultOrganizationStorageResult.AuthorizationExpired
                    if (success) VaultOrganizationStorageResult.Created else VaultOrganizationStorageResult.CleanupFailed
                } catch (_: SecurityException) {
                    VaultOrganizationStorageResult.AccessDenied
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    VaultOrganizationStorageResult.Unavailable
                }
            }
        }

    override suspend fun discardOrganizationFile(generation: Long): VaultOrganizationStorageResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (generation < 1) return@withLock VaultOrganizationStorageResult.WriteFailed
                    val tree = selectedTree() ?: return@withLock organizationSelectionResult()
                    val structure = structure(tree) ?: return@withLock VaultOrganizationStorageResult.Unavailable
                    val directory = findUniqueChild(tree, structure.data, ORGANIZATION_DIRECTORY)
                        ?: return@withLock VaultOrganizationStorageResult.Unavailable
                    if (directory.mimeType != Document.MIME_TYPE_DIR) return@withLock VaultOrganizationStorageResult.WriteFailed
                    val child = findUniqueChild(tree, directory.uri, organizationName(generation))
                        ?: return@withLock VaultOrganizationStorageResult.Created
                    if (child.mimeType == Document.MIME_TYPE_DIR) return@withLock VaultOrganizationStorageResult.WriteFailed
                    if (delete(child.uri)) VaultOrganizationStorageResult.Created
                    else VaultOrganizationStorageResult.CleanupFailed
                } catch (_: SecurityException) {
                    VaultOrganizationStorageResult.AccessDenied
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    VaultOrganizationStorageResult.Unavailable
                }
            }
        }

    override suspend fun commitIndexFileAtomically(
        generation: Long,
        bytes: ByteArray,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultIndexFileCommitResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (generation < 0 || bytes.isEmpty() || bytes.size > VaultIndexEnvelopeCodec.MAX_ENVELOPE_BYTES) {
                return@withLock VaultIndexFileCommitResult.WriteFailed
            }
            var temporary: Uri? = null
            var committed: Uri? = null
            try {
                val tree = selectedTree() ?: return@withLock selectionResult()
                val structure = structure(tree) ?: return@withLock VaultIndexFileCommitResult.WriteFailed
                val index = findUniqueChild(tree, structure.data, INDEX_DIRECTORY)
                    ?: return@withLock VaultIndexFileCommitResult.WriteFailed
                if (index.mimeType != Document.MIME_TYPE_DIR) return@withLock VaultIndexFileCommitResult.WriteFailed
                val before = indexListing(tree, index.uri) ?: return@withLock VaultIndexFileCommitResult.WriteFailed
                if (before.unexpected || before.generations.any { it >= generation } ||
                    (generation == 0L && before.generations.isNotEmpty()) ||
                    (generation > 0L && before.generations.maxOrNull() != generation - 1)
                ) return@withLock VaultIndexFileCommitResult.Conflict
                if (findUniqueChild(tree, index.uri, indexName(generation)) != null) {
                    return@withLock VaultIndexFileCommitResult.Conflict
                }
                val suffix = randomSuffix() ?: return@withLock VaultIndexFileCommitResult.WriteFailed
                val temporaryName = "$PENDING_PREFIX${indexName(generation)}-$suffix"
                temporary = DocumentsContract.createDocument(
                    resolver,
                    index.uri,
                    MIME_TYPE,
                    temporaryName,
                ) ?: return@withLock VaultIndexFileCommitResult.WriteFailed
                val tempDoc = findUniqueChild(tree, index.uri, temporaryName)
                    ?: return@withLock cleanupIndex(tree, VaultIndexFileCommitResult.VerificationFailed, temporary, null)
                if ((tempDoc.flags and Document.FLAG_SUPPORTS_RENAME) == 0) {
                    return@withLock cleanupIndex(tree, VaultIndexFileCommitResult.WriteFailed, temporary, null)
                }
                writeDocument(temporary!!, bytes)
                val readback = readBounded(temporary!!, VaultIndexEnvelopeCodec.MAX_ENVELOPE_BYTES)
                    ?: return@withLock cleanupIndex(tree, VaultIndexFileCommitResult.VerificationFailed, temporary, null)
                val equal = readback.contentEquals(bytes)
                readback.fill(0)
                if (!equal) return@withLock cleanupIndex(tree, VaultIndexFileCommitResult.VerificationFailed, temporary, null)
                if (!authorizationCheckpoint()) {
                    return@withLock cleanupIndex(tree, VaultIndexFileCommitResult.AuthorizationExpired, temporary, null)
                }

                committed = DocumentsContract.renameDocument(resolver, temporary!!, indexName(generation))
                    ?: return@withLock cleanupIndex(tree, VaultIndexFileCommitResult.WriteFailed, temporary, null)
                temporary = null
                val actual = findUniqueChild(tree, index.uri, indexName(generation))
                    ?: return@withLock cleanupIndex(tree, VaultIndexFileCommitResult.VerificationFailed, null, committed)
                if (actual.mimeType == Document.MIME_TYPE_DIR) {
                    return@withLock cleanupIndex(tree, VaultIndexFileCommitResult.VerificationFailed, null, committed)
                }
                val finalBytes = readBounded(actual.uri, VaultIndexEnvelopeCodec.MAX_ENVELOPE_BYTES)
                    ?: return@withLock cleanupIndex(tree, VaultIndexFileCommitResult.VerificationFailed, null, committed)
                val finalEqual = finalBytes.contentEquals(bytes)
                finalBytes.fill(0)
                if (!finalEqual) return@withLock cleanupIndex(tree, VaultIndexFileCommitResult.VerificationFailed, null, committed)
                VaultIndexFileCommitResult.Created
            } catch (failure: CancellationException) {
                cleanupIndex(null, VaultIndexFileCommitResult.VerificationFailed, temporary, committed)
                throw failure
            } catch (_: SecurityException) {
                cleanupIndex(null, VaultIndexFileCommitResult.AccessDenied, temporary, committed)
            } catch (_: FileNotFoundException) {
                cleanupIndex(null, VaultIndexFileCommitResult.Unavailable, temporary, committed)
            } catch (_: Exception) {
                cleanupIndex(null, VaultIndexFileCommitResult.WriteFailed, temporary, committed)
            }
        }
    }

    override suspend fun writeObjectAtomically(
        itemId: VaultItemId,
        authorizationCheckpoint: suspend () -> Boolean,
        writer: suspend (OutputStream) -> VaultObjectWriteInfo,
    ): VaultObjectCommitResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            var temporary: Uri? = null
            try {
                val tree = selectedTree() ?: return@withLock when (selectionFailure) {
                    SelectionFailure.DENIED -> VaultObjectCommitResult.AccessDenied
                    else -> VaultObjectCommitResult.DestinationUnavailable
                }
                val structure = structure(tree) ?: return@withLock VaultObjectCommitResult.DestinationUnavailable
                val objects = findUniqueChild(tree, structure.data, OBJECTS_DIRECTORY)
                    ?: return@withLock VaultObjectCommitResult.DestinationUnavailable
                if (objects.mimeType != Document.MIME_TYPE_DIR) return@withLock VaultObjectCommitResult.DestinationUnavailable
                val finalName = objectName(itemId)
                if (findUniqueChild(tree, objects.uri, finalName) != null) return@withLock VaultObjectCommitResult.Conflict
                val suffix = randomSuffix() ?: return@withLock VaultObjectCommitResult.WriteFailed
                val tempName = "$OBJECT_PENDING_PREFIX${itemId.value}-$suffix"
                temporary = DocumentsContract.createDocument(resolver, objects.uri, MIME_TYPE, tempName)
                    ?: return@withLock VaultObjectCommitResult.WriteFailed
                val tempDocument = findUniqueChild(tree, objects.uri, tempName)
                    ?: return@withLock cleanupObject(tree, VaultObjectCommitResult.VerificationFailed, temporary)
                if ((tempDocument.flags and Document.FLAG_SUPPORTS_RENAME) == 0) {
                    return@withLock cleanupObject(tree, VaultObjectCommitResult.WriteFailed, temporary)
                }
                val info = resolver.openOutputStream(temporary!!, "w")?.use { output -> writer(output) }
                    ?: return@withLock cleanupObject(tree, VaultObjectCommitResult.WriteFailed, temporary)
                val tempBytes = countBytes(temporary!!)
                    ?: return@withLock cleanupObject(tree, VaultObjectCommitResult.VerificationFailed, temporary)
                if (tempBytes != info.expectedObjectBytes) {
                    return@withLock cleanupObject(tree, VaultObjectCommitResult.VerificationFailed, temporary)
                }
                if (!authorizationCheckpoint()) {
                    return@withLock cleanupObject(tree, VaultObjectCommitResult.AuthorizationExpired, temporary)
                }
                val finalUri = DocumentsContract.renameDocument(resolver, temporary!!, finalName)
                    ?: return@withLock cleanupObject(tree, VaultObjectCommitResult.WriteFailed, temporary)
                temporary = null
                val finalDocument = findUniqueChild(tree, objects.uri, finalName)
                    ?: return@withLock VaultObjectCommitResult.VerificationFailed
                if (finalDocument.mimeType == Document.MIME_TYPE_DIR) return@withLock VaultObjectCommitResult.VerificationFailed
                val actualSize = countBytes(finalDocument.uri)
                    ?: return@withLock VaultObjectCommitResult.VerificationFailed
                if (actualSize != info.expectedObjectBytes) return@withLock VaultObjectCommitResult.VerificationFailed
                // SAF rename providers may return a replacement URI; the name lookup is the authoritative final reference.
                if (finalUri != finalDocument.uri) Unit
                VaultObjectCommitResult.Created(actualSize)
            } catch (failure: CancellationException) {
                cleanupObject(null, VaultObjectCommitResult.WriteFailed, temporary)
                throw failure
            } catch (_: SecurityException) {
                cleanupObject(null, VaultObjectCommitResult.AccessDenied, temporary)
            } catch (_: FileNotFoundException) {
                cleanupObject(null, VaultObjectCommitResult.DestinationUnavailable, temporary)
            } catch (_: IOException) {
                cleanupObject(null, VaultObjectCommitResult.WriteFailed, temporary)
            } catch (_: Exception) {
                cleanupObject(null, VaultObjectCommitResult.WriteFailed, temporary)
            }
        }
    }

    override suspend fun openObject(itemId: VaultItemId): InputStream? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tree = selectedTree() ?: return@withLock null
            val structure = structure(tree) ?: return@withLock null
            val objects = findUniqueChild(tree, structure.data, OBJECTS_DIRECTORY) ?: return@withLock null
            if (objects.mimeType != Document.MIME_TYPE_DIR) return@withLock null
            val child = findUniqueChild(tree, objects.uri, objectName(itemId)) ?: return@withLock null
            if (child.mimeType == Document.MIME_TYPE_DIR) return@withLock null
            resolver.openInputStream(child.uri)
        }
    }

    override suspend fun openObjectResult(itemId: VaultItemId): VaultObjectOpenResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val tree = selectedTree() ?: return@withLock when (selectionFailure) {
                    SelectionFailure.DENIED -> VaultObjectOpenResult.AccessDenied
                    else -> VaultObjectOpenResult.Unavailable
                }
                val structure = structure(tree) ?: return@withLock VaultObjectOpenResult.Unavailable
                val objects = findUniqueChild(tree, structure.data, OBJECTS_DIRECTORY)
                    ?: return@withLock VaultObjectOpenResult.Missing
                if (objects.mimeType != Document.MIME_TYPE_DIR) return@withLock VaultObjectOpenResult.Unavailable
                val child = findUniqueChild(tree, objects.uri, objectName(itemId))
                    ?: return@withLock VaultObjectOpenResult.Missing
                if (child.mimeType == Document.MIME_TYPE_DIR) return@withLock VaultObjectOpenResult.Unavailable
                val input = resolver.openInputStream(child.uri)
                    ?: return@withLock VaultObjectOpenResult.Unavailable
                VaultObjectOpenResult.Opened(input)
            } catch (_: SecurityException) {
                VaultObjectOpenResult.AccessDenied
            } catch (_: FileNotFoundException) {
                VaultObjectOpenResult.Missing
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                VaultObjectOpenResult.Unavailable
            }
        }
    }

    override suspend fun inspectObjectDirectory(): VaultObjectDirectoryRead = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val tree = selectedTree() ?: return@withLock when (selectionFailure) {
                    SelectionFailure.DENIED -> VaultObjectDirectoryRead.AccessDenied
                    else -> VaultObjectDirectoryRead.Unavailable
                }
                val structure = structure(tree) ?: return@withLock VaultObjectDirectoryRead.Unavailable
                val objects = findUniqueChild(tree, structure.data, OBJECTS_DIRECTORY)
                    ?: return@withLock VaultObjectDirectoryRead.Unavailable
                if (objects.mimeType != Document.MIME_TYPE_DIR) return@withLock VaultObjectDirectoryRead.Unavailable
                val ids = LinkedHashSet<VaultItemId>()
                var unfinished = 0
                var unrecognized = 0
                listChildren(tree, objects.uri, MAX_INDEX_GENERATIONS).forEach { child ->
                    when {
                        child.name.startsWith(OBJECT_PENDING_PREFIX) -> unfinished++
                        child.mimeType != Document.MIME_TYPE_DIR && child.name.matches(Regex("[0-9a-f]{32}\\.nvc")) -> {
                            val id = runCatching { VaultItemId(child.name.removeSuffix(".nvc")) }.getOrNull()
                            if (id == null || !ids.add(id)) unrecognized++
                        }
                        else -> unrecognized++
                    }
                }
                VaultObjectDirectoryRead.Available(ids, unfinished, unrecognized)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: SecurityException) {
                VaultObjectDirectoryRead.AccessDenied
            } catch (_: Exception) {
                VaultObjectDirectoryRead.Unavailable
            }
        }
    }

    private fun structure(tree: Uri): Structure? {
        val root = listChildren(tree, null, MAX_ROOT_ENTRIES)
        if (root.any { it.name != METADATA_NAME && it.name != DATA_DIRECTORY }) return null
        val metadata = root.filter { it.name == METADATA_NAME }
        val data = root.filter { it.name == DATA_DIRECTORY }
        if (metadata.size != 1 || metadata.single().mimeType == Document.MIME_TYPE_DIR || data.size != 1 ||
            data.single().mimeType != Document.MIME_TYPE_DIR
        ) return null
        val dataEntries = listChildren(tree, data.single().uri, MAX_ROOT_ENTRIES)
        if (dataEntries.any { it.name !in CONTENT_DIRECTORIES || it.mimeType != Document.MIME_TYPE_DIR } ||
            CONTENT_DIRECTORIES.any { name -> dataEntries.count { it.name == name } > 1 }
        ) return null
        return Structure(data.single().uri)
    }

    private fun ensureDirectory(tree: Uri, parent: Uri, name: String): Child? {
        val existing = findUniqueChild(tree, parent, name)
        if (existing != null) return existing
        val created = DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name) ?: return null
        val verified = findUniqueChild(tree, parent, name) ?: return null
        return if (verified.uri == created && verified.mimeType == Document.MIME_TYPE_DIR) verified else null
    }

    private fun indexListing(tree: Uri, directory: Uri): IndexListing? {
        val children = listChildren(tree, directory, MAX_INDEX_GENERATIONS)
        val generations = ArrayList<Long>()
        val seen = HashSet<Long>()
        var pending = false
        var unexpected = false
        children.forEach { child ->
            val generation = parseIndexName(child.name)
            when {
                generation != null && child.mimeType != Document.MIME_TYPE_DIR -> {
                    if (!seen.add(generation)) unexpected = true else generations += generation
                }
                child.name.startsWith(PENDING_PREFIX) -> pending = true
                else -> unexpected = true
            }
        }
        return IndexListing(generations, pending, unexpected)
    }

    private fun organizationListing(tree: Uri, directory: Uri): OrganizationListing? {
        val children = listChildren(tree, directory, MAX_ORGANIZATION_GENERATIONS)
        val generations = ArrayList<Long>()
        val seen = HashSet<Long>()
        var pending = false
        var unexpected = false
        children.forEach { child ->
            val generation = parseOrganizationName(child.name)
            when {
                generation != null && child.mimeType != Document.MIME_TYPE_DIR -> {
                    if (!seen.add(generation)) unexpected = true else generations += generation
                }
                child.name.startsWith(ORGANIZATION_PENDING_PREFIX) -> pending = true
                else -> unexpected = true
            }
        }
        return OrganizationListing(generations, pending, unexpected)
    }

    private suspend fun cleanupIndex(
        tree: Uri?,
        result: VaultIndexFileCommitResult,
        temporary: Uri?,
        committed: Uri?,
    ): VaultIndexFileCommitResult {
        var success = true
        if (temporary != null) success = delete(temporary) && success
        if (committed != null) success = delete(committed) && success
        return if (success) result else VaultIndexFileCommitResult.WriteFailed
    }

    private fun cleanupOrganization(
        tree: Uri?,
        result: VaultOrganizationStorageResult,
        temporary: Uri?,
        committed: Uri?,
    ): VaultOrganizationStorageResult {
        var success = true
        if (temporary != null) success = delete(temporary) && success
        if (committed != null) success = delete(committed) && success
        return if (success) result else VaultOrganizationStorageResult.CleanupFailed
    }

    private fun cleanupObject(
        tree: Uri?,
        result: VaultObjectCommitResult,
        temporary: Uri?,
    ): VaultObjectCommitResult {
        val clean = temporary == null || delete(temporary)
        return if (clean) result else VaultObjectCommitResult.CleanupFailed
    }

    private fun delete(uri: Uri): Boolean = try { DocumentsContract.deleteDocument(resolver, uri) }
    catch (_: Exception) { false }

    private fun findUniqueChild(tree: Uri, parent: Uri, name: String): Child? {
        val matches = listChildren(tree, parent, MAX_INDEX_GENERATIONS + 4).filter { it.name == name }
        if (matches.size > 1) throw IOException("Duplicate SAF document name.")
        return matches.singleOrNull()
    }

    private fun hasChildren(tree: Uri, parent: Uri): Boolean {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor = resolver.query(childrenUri, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?: throw IOException("The document provider returned no listing.")
        cursor.use { return it.moveToFirst() }
    }

    private fun listChildren(tree: Uri, parent: Uri?, max: Int): List<Child> {
        val parentId = parent?.let(DocumentsContract::getDocumentId) ?: DocumentsContract.getTreeDocumentId(tree)
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor = resolver.query(
            query,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS),
            null, null, null,
        ) ?: throw IOException("The document provider returned no listing.")
        val result = ArrayList<Child>()
        cursor.use { rows ->
            while (rows.moveToNext()) {
                if (result.size >= max) throw IOException("The SAF directory exceeds its bounded entry count.")
                val id = rows.getString(0) ?: throw IOException("Missing document id.")
                val name = rows.getString(1) ?: throw IOException("Missing document name.")
                val mime = rows.getString(2) ?: throw IOException("Missing document type.")
                result += Child(
                    DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime, rows.getLong(3).toInt(),
                )
            }
        }
        return result
    }

    private fun readBounded(uri: Uri, max: Int): ByteArray? {
        val input = resolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(max, 32 * 1024))
            val buffer = ByteArray(32 * 1024)
            var total = 0
            try {
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total = Math.addExact(total, count)
                    if (total > max) return null
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            } finally { buffer.fill(0) }
        }
    }

    private fun writeDocument(uri: Uri, bytes: ByteArray) {
        val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Document is not writable.")
        output.use { stream -> stream.write(bytes); stream.flush() }
    }

    private fun countBytes(uri: Uri): Long? {
        val input = resolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            try {
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count > 0) total = Math.addExact(total, count.toLong())
                }
                return total
            } finally { buffer.fill(0) }
        }
    }

    private fun randomSuffix(): String? {
        val bytes = try { random.generateBytes(16) } catch (_: Exception) { return null }
        return try { bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } }
        finally { bytes.fill(0) }
    }

    private fun indexName(generation: Long): String = String.format(Locale.ROOT, "index-%020d.vxi", generation)
    private fun organizationName(generation: Long): String = String.format(Locale.ROOT, "organization-%020d.vxo", generation)
    private fun objectName(id: VaultItemId): String = "${id.value}.nvc"

    private fun parseIndexName(name: String): Long? {
        if (!name.matches(Regex("index-[0-9]{20}\\.vxi"))) return null
        return name.substring(6, 26).toLongOrNull()?.takeIf { indexName(it) == name }
    }

    private fun parseOrganizationName(name: String): Long? {
        if (!name.matches(Regex("organization-[0-9]{20}\\.vxo"))) return null
        return name.substring(13, 33).toLongOrNull()?.takeIf { organizationName(it) == name }
    }

    private var selectionFailure = SelectionFailure.NONE
    private fun selectedTree(): Uri? {
        selectionFailure = SelectionFailure.NONE
        val tree = try { locations.selectedTreeUri() }
        catch (_: Exception) { selectionFailure = SelectionFailure.UNAVAILABLE; return null }
            ?: return null
        if (!DocumentsContract.isTreeUri(tree)) {
            selectionFailure = SelectionFailure.UNAVAILABLE
            return null
        }
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree }
        if (grant == null || !grant.isReadPermission || !grant.isWritePermission) {
            selectionFailure = SelectionFailure.DENIED
            return null
        }
        return tree
    }

    private fun organizationSelectionResult() = when (selectionFailure) {
        SelectionFailure.DENIED -> VaultOrganizationStorageResult.AccessDenied
        else -> VaultOrganizationStorageResult.Unavailable
    }

    private fun selectionResult() = when (selectionFailure) {
        SelectionFailure.DENIED -> VaultIndexFileCommitResult.AccessDenied
        else -> VaultIndexFileCommitResult.Unavailable
    }

    private data class Child(val uri: Uri, val name: String, val mimeType: String, val flags: Int)
    private data class Structure(val data: Uri)
    private data class IndexListing(val generations: List<Long>, val pending: Boolean, val unexpected: Boolean)
    private data class OrganizationListing(val generations: List<Long>, val pending: Boolean, val unexpected: Boolean)
    private enum class SelectionFailure { NONE, DENIED, UNAVAILABLE }

    private companion object {
        const val METADATA_NAME = "vault.nvmeta"
        const val DATA_DIRECTORY = "data"
        const val INDEX_DIRECTORY = "index"
        const val OBJECTS_DIRECTORY = "objects"
        const val ORGANIZATION_DIRECTORY = "organization"
        val CONTENT_DIRECTORIES = setOf(INDEX_DIRECTORY, OBJECTS_DIRECTORY, ORGANIZATION_DIRECTORY)
        const val MIME_TYPE = "application/octet-stream"
        const val PENDING_PREFIX = ".pending-"
        const val OBJECT_PENDING_PREFIX = ".pending-"
        const val MAX_ROOT_ENTRIES = 10_000
        // 25k covers the 20k item limit, all retained index generations, and a bounded orphan reserve.
        const val MAX_INDEX_GENERATIONS = 25_000
        const val MAX_ORGANIZATION_GENERATIONS = 256
        const val ORGANIZATION_PENDING_PREFIX = ".pending-organization-"
    }
}
