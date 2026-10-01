package com.ashishkumar.nivara.data.vault

import android.graphics.Bitmap
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.security.session.AuthenticatedSession
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.content.VaultContentClassification
import com.ashishkumar.nivara.domain.vault.content.VaultContentClassifier
import com.ashishkumar.nivara.domain.vault.content.VaultContentCrypto
import com.ashishkumar.nivara.domain.vault.content.VaultContentCryptoResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentPresentationGateway
import com.ashishkumar.nivara.domain.vault.content.VaultImagePixels
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultObjectOpenResult
import com.ashishkumar.nivara.domain.vault.content.VaultPresentationHandle
import com.ashishkumar.nivara.domain.vault.content.VaultPresentationOpenResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Platform adapter for bounded in-memory previews; it never writes decrypted content to disk. */
class AndroidVaultContentPresentationRepository(
    private val storage: VaultContentStorage,
    private val crypto: VaultContentCrypto,
    private val index: VaultIndexRepository,
    private val sessions: SessionManager,
    private val random: SecureRandomSource,
) : VaultContentPresentationGateway, Closeable {
    private val resources = ConcurrentHashMap<VaultPresentationHandle, Resource>()
    private val operationMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionIdentityLock = Any()
    private var observedSession: AuthenticatedSession? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.sessionState.collect { state ->
                val currentSession = (state as? SessionState.Authenticated)?.session
                val previousSession = synchronized(sessionIdentityLock) {
                    observedSession.also { observedSession = currentSession }
                }
                if (currentSession == null || (previousSession != null && previousSession != currentSession)) {
                    closeAll()
                }
            }
        }
    }

    override suspend fun open(
        vaultId: VaultId,
        itemId: VaultItemId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultPresentationOpenResult = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            closeAll()
            if (!authorized(authorizationCheckpoint)) return@withContext VaultPresentationOpenResult.AuthorizationExpired
            val authenticatedItem = when (val indexed = index.inspect(vaultId)) {
                is VaultIndexRead.Ready -> indexed.items.firstOrNull { it.id == itemId }
                    ?: return@withContext VaultPresentationOpenResult.Missing
                else -> return@withContext VaultPresentationOpenResult.Unreadable
            }
            val classification = VaultContentClassifier.classify(authenticatedItem)
            // Audio/video decoding requires a seekable source. The current GCM object format is
            // sequential, and staging its full plaintext to disk or heap is intentionally forbidden.
            if (!authorized(authorizationCheckpoint)) return@withContext VaultPresentationOpenResult.AuthorizationExpired
            val objectStream = when (val opened = storage.openObjectResult(authenticatedItem.id)) {
                is VaultObjectOpenResult.Opened -> opened.stream
                VaultObjectOpenResult.Missing -> return@withContext VaultPresentationOpenResult.Missing
                VaultObjectOpenResult.AccessDenied,
                VaultObjectOpenResult.Unavailable -> return@withContext VaultPresentationOpenResult.Unreadable
            }
            try {
                if (classification.previewKind !in setOf(
                        VaultContentClassification.PreviewKind.IMAGE,
                        VaultContentClassification.PreviewKind.TEXT,
                    )
                ) return@withContext VaultPresentationOpenResult.Unsupported
                when (classification.previewKind) {
                    VaultContentClassification.PreviewKind.IMAGE -> openImage(
                        vaultId, authenticatedItem, classification, objectStream, authorizationCheckpoint,
                    )
                    VaultContentClassification.PreviewKind.TEXT -> openText(
                        vaultId, authenticatedItem, classification, objectStream, authorizationCheckpoint,
                    )
                    else -> VaultPresentationOpenResult.Unsupported
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: PreviewLimitException) {
                VaultPresentationOpenResult.Unsupported
            } catch (_: SecurityException) {
                VaultPresentationOpenResult.Unreadable
            } catch (_: IOException) {
                VaultPresentationOpenResult.CorruptFormat
            } catch (_: Exception) {
                VaultPresentationOpenResult.Failed
            } finally {
                runCatching { objectStream.close() }
            }
        }
    }

    private suspend fun openImage(
        vaultId: VaultId,
        item: VaultItem,
        classification: VaultContentClassification,
        input: java.io.InputStream,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultPresentationOpenResult {
        if (!imageMimeSupported(classification.mimeType) || item.originalSizeBytes > MAX_IMAGE_COMPRESSED_BYTES) {
            return VaultPresentationOpenResult.Unsupported
        }
        val quarantine = WipingBoundedOutputStream(MAX_IMAGE_COMPRESSED_BYTES)
        try {
            when (crypto.decryptObjectToQuarantine(vaultId, item, input, quarantine, authorizationCheckpoint)) {
                is VaultContentCryptoResult.Success -> {
                    if (!authorized(authorizationCheckpoint)) return VaultPresentationOpenResult.AuthorizationExpired
                    val encoded = quarantine.copyBytes()
                    quarantine.wipe()
                    try {
                        val bitmap = AndroidVaultImageDecoder.decode(encoded)
                            ?: return VaultPresentationOpenResult.CorruptFormat
                        val handle = newHandle() ?: run { bitmap.recycle(); return VaultPresentationOpenResult.Failed }
                        if (!register(handle, ImageResource(bitmap), authorizationCheckpoint)) {
                            return VaultPresentationOpenResult.AuthorizationExpired
                        }
                        VaultPresentationOpenResult.Ready(handle, classification.previewKind)
                    } finally {
                        encoded.fill(0)
                    }
                }
                is VaultContentCryptoResult.VaultUnavailable -> VaultPresentationOpenResult.Unreadable
                VaultContentCryptoResult.AuthenticationFailed -> VaultPresentationOpenResult.AuthenticationFailed
                is VaultContentCryptoResult.UnsupportedVersion -> VaultPresentationOpenResult.UnsupportedVersion
                VaultContentCryptoResult.AuthorizationExpired -> VaultPresentationOpenResult.AuthorizationExpired
                VaultContentCryptoResult.OperationFailed,
                VaultContentCryptoResult.SourceSizeMismatch -> VaultPresentationOpenResult.Unreadable
            }
        } finally {
            quarantine.wipe()
        }
    }

    private suspend fun openText(
        vaultId: VaultId,
        item: VaultItem,
        classification: VaultContentClassification,
        input: java.io.InputStream,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultPresentationOpenResult {
        if (item.originalSizeBytes > MAX_TEXT_BYTES) return VaultPresentationOpenResult.Unsupported
        val quarantine = WipingBoundedOutputStream(MAX_TEXT_BYTES)
        try {
            when (crypto.decryptObjectToQuarantine(vaultId, item, input, quarantine, authorizationCheckpoint)) {
                is VaultContentCryptoResult.Success -> {
                    if (!authorized(authorizationCheckpoint)) return VaultPresentationOpenResult.AuthorizationExpired
                    val bytes = quarantine.copyBytes()
                    quarantine.wipe()
                    try {
                        val decoded = try {
                            StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes))
                        } catch (_: Exception) {
                            return VaultPresentationOpenResult.CorruptFormat
                        }
                        val chars = CharArray(decoded.remaining())
                        decoded.get(chars)
                        if (decoded.hasArray()) decoded.array().fill('\u0000')
                        val handle = newHandle() ?: run {
                            chars.fill('\u0000')
                            return VaultPresentationOpenResult.Failed
                        }
                        if (!register(handle, TextResource(chars), authorizationCheckpoint)) {
                            chars.fill('\u0000')
                            return VaultPresentationOpenResult.AuthorizationExpired
                        }
                        VaultPresentationOpenResult.Ready(handle, classification.previewKind)
                    } finally {
                        bytes.fill(0)
                    }
                }
                is VaultContentCryptoResult.VaultUnavailable -> VaultPresentationOpenResult.Unreadable
                VaultContentCryptoResult.AuthenticationFailed -> VaultPresentationOpenResult.AuthenticationFailed
                is VaultContentCryptoResult.UnsupportedVersion -> VaultPresentationOpenResult.UnsupportedVersion
                VaultContentCryptoResult.AuthorizationExpired -> VaultPresentationOpenResult.AuthorizationExpired
                VaultContentCryptoResult.OperationFailed,
                VaultContentCryptoResult.SourceSizeMismatch -> VaultPresentationOpenResult.Unreadable
            }
        } finally {
            quarantine.wipe()
        }
    }

    override suspend fun image(handle: VaultPresentationHandle): VaultImagePixels? = withContext(Dispatchers.Default) {
        if (!authorized { true }) return@withContext null
        val pixels = (resources[handle] as? ImageResource)?.copyPixels() ?: return@withContext null
        var delivered = false
        try {
            if (!authorized { true }) return@withContext null
            delivered = true
            pixels
        } finally {
            if (!delivered) pixels.clear()
        }
    }

    override suspend fun text(handle: VaultPresentationHandle): String? = withContext(Dispatchers.Default) {
        if (!authorized { true }) return@withContext null
        val text = (resources[handle] as? TextResource)?.text() ?: return@withContext null
        text.takeIf { authorized { true } }
    }

    override fun close(handle: VaultPresentationHandle) {
        resources.remove(handle)?.close()
    }

    override fun close() {
        closeAll()
        scope.cancel()
    }

    private fun closeAll() {
        resources.entries.toList().forEach { (handle, _) -> close(handle) }
    }

    private suspend fun register(
        handle: VaultPresentationHandle,
        resource: Resource,
        checkpoint: suspend () -> Boolean,
    ): Boolean {
        resources[handle] = resource
        return try {
            if (authorized(checkpoint)) true else {
                close(handle)
                false
            }
        } catch (failure: CancellationException) {
            close(handle)
            throw failure
        }
    }

    private suspend fun authorized(checkpoint: suspend () -> Boolean): Boolean = try {
        sessions.currentState() is SessionState.Authenticated && sessions.mayAccessSensitiveContent() && checkpoint()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        false
    }

    private fun newHandle(): VaultPresentationHandle? {
        val bytes = try {
            random.generateBytes(16)
        } catch (_: Exception) {
            return null
        }
        return try {
            VaultPresentationHandle(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        } finally {
            bytes.fill(0)
        }
    }

    private fun imageMimeSupported(mime: String?): Boolean = when (mime) {
        "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/heic", "image/heif" -> true
        "image/avif" -> android.os.Build.VERSION.SDK_INT >= 34
        else -> false
    }

    private sealed interface Resource : Closeable

    private class ImageResource(private val bitmap: Bitmap) : Resource {
        @Synchronized fun copyPixels(): VaultImagePixels? {
            if (bitmap.isRecycled) return null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return VaultImagePixels(bitmap.width, bitmap.height, pixels)
        }

        @Synchronized override fun close() {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private class TextResource(private val chars: CharArray) : Resource {
        @Synchronized fun text() = String(chars)
        @Synchronized override fun close() = chars.fill('\u0000')
    }

    private class WipingBoundedOutputStream(private val limit: Int) : ByteArrayOutputStream(minOf(limit, 64 * 1024)) {
        override fun write(value: Int) {
            if (count >= limit) throw PreviewLimitException()
            super.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length < 0 || count.toLong() + length > limit) throw PreviewLimitException()
            super.write(buffer, offset, length)
        }

        fun copyBytes(): ByteArray = toByteArray()
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private class PreviewLimitException : IOException()

    companion object {
        const val MAX_IMAGE_COMPRESSED_BYTES = 16 * 1024 * 1024
        const val MAX_TEXT_BYTES = 256 * 1024
        const val MAX_IMAGE_DIMENSION = 65_536
        const val MAX_IMAGE_RENDER_DIMENSION = 1_536
    }
}
