package com.ashishkumar.nivara.data.vault

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.vault.content.VaultSourceDocumentProvider
import com.ashishkumar.nivara.domain.vault.content.VaultSourceMetadata
import com.ashishkumar.nivara.domain.vault.content.VaultSourceMetadataRead
import com.ashishkumar.nivara.domain.vault.content.VaultSourceOpenResult
import com.ashishkumar.nivara.domain.vault.content.VaultSourceSelectionId
import com.ashishkumar.nivara.domain.vault.content.VaultSourceSelectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Transient one-shot SAF URI registry. URIs never enter domain contracts, saved state, or preferences. */
class PendingSourceDocuments(private val random: SecureRandomSource) {
    private val pending = ConcurrentHashMap<VaultSourceSelectionId, Uri>()

    fun add(uri: Uri): VaultSourceSelectionId? {
        repeat(3) {
            val bytes = try { random.generateBytes(16) } catch (_: Exception) { return null }
            val id = try {
                VaultSourceSelectionId(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
            } finally { bytes.fill(0) }
            if (pending.putIfAbsent(id, uri) == null) return id
        }
        return null
    }

    fun peek(id: VaultSourceSelectionId): Uri? = pending[id]
    fun consume(id: VaultSourceSelectionId): Uri? = pending.remove(id)
    fun discard(id: VaultSourceSelectionId) { pending.remove(id) }
}

class VaultSourcePickerContract(
    private val pending: PendingSourceDocuments,
) : ActivityResultContract<Unit, VaultSourceSelectionResult>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): VaultSourceSelectionResult {
        if (resultCode != Activity.RESULT_OK) return VaultSourceSelectionResult.Cancelled
        val uri = intent?.data ?: return VaultSourceSelectionResult.Unavailable
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return VaultSourceSelectionResult.Unavailable
        }
        if ((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            return VaultSourceSelectionResult.AccessDenied
        }
        val id = pending.add(uri) ?: return VaultSourceSelectionResult.Unavailable
        return VaultSourceSelectionResult.Selected(id)
    }
}

class AndroidVaultSourceDocumentProvider(
    context: Context,
    private val pending: PendingSourceDocuments,
) : VaultSourceDocumentProvider {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun metadata(sourceId: VaultSourceSelectionId): VaultSourceMetadataRead = withContext(Dispatchers.IO) {
        val uri = pending.peek(sourceId) ?: return@withContext VaultSourceMetadataRead.Unavailable
        try {
            var displayName: String? = null
            var size: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) displayName = cursor.getString(nameColumn)
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                    }
                } ?: return@withContext VaultSourceMetadataRead.Unavailable
            val mime = resolver.getType(uri)
            if (displayName.isNullOrBlank() || (size != null && size!! < 0)) {
                return@withContext VaultSourceMetadataRead.Unavailable
            }
            VaultSourceMetadataRead.Available(VaultSourceMetadata(displayName!!, mime, size))
        } catch (_: SecurityException) {
            VaultSourceMetadataRead.AccessDenied
        } catch (_: FileNotFoundException) {
            VaultSourceMetadataRead.Unavailable
        } catch (_: Exception) {
            VaultSourceMetadataRead.Unavailable
        }
    }

    override suspend fun open(sourceId: VaultSourceSelectionId): VaultSourceOpenResult = withContext(Dispatchers.IO) {
        val uri = pending.peek(sourceId) ?: return@withContext VaultSourceOpenResult.Unavailable
        try {
            val input = resolver.openInputStream(uri)
            if (input == null) VaultSourceOpenResult.Unavailable else VaultSourceOpenResult.Opened(input)
        } catch (_: SecurityException) {
            VaultSourceOpenResult.AccessDenied
        } catch (_: FileNotFoundException) {
            VaultSourceOpenResult.Unavailable
        } catch (_: Exception) {
            VaultSourceOpenResult.Unavailable
        }
    }

    override fun discard(sourceId: VaultSourceSelectionId) { pending.discard(sourceId) }
}
