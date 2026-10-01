package com.ashishkumar.nivara.data.vault

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.activity.result.contract.ActivityResultContract
import com.ashishkumar.nivara.domain.vault.VaultRootSelectionResult

/** Android-only location preference. The persisted document-tree URI never crosses into domain or UI state. */
internal interface VaultLocationStore {
    fun selectedTreeUri(): Uri?
    fun saveSelectedTree(uri: Uri): Boolean
}

internal class AndroidVaultLocationStore(context: Context) : VaultLocationStore {
    private val preferences = context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    override fun selectedTreeUri(): Uri? = preferences.getString(KEY_TREE_URI, null)
        ?.takeIf(String::isNotBlank)
        ?.let(Uri::parse)

    override fun saveSelectedTree(uri: Uri): Boolean =
        preferences.edit().putString(KEY_TREE_URI, uri.toString()).commit()

    private companion object {
        const val STORE_NAME = "nivara_vault_location"
        const val KEY_TREE_URI = "selected_document_tree_uri"
    }
}

/** The activity-result bridge consumes Android Uri/flags in data and returns only a domain-safe outcome. */
class VaultRootSelectionHandler internal constructor(
    context: Context,
    private val locations: VaultLocationStore,
) {
    private val applicationContext = context.applicationContext

    fun accept(uri: Uri, grantedFlags: Int): VaultRootSelectionResult {
        val selected = try {
            locations.selectedTreeUri()
        } catch (_: Exception) {
            return VaultRootSelectionResult.PERSISTENCE_FAILURE
        }
        if (selected != null && selected != uri) {
            return VaultRootSelectionResult.DIFFERENT_ROOT_ALREADY_SELECTED
        }
        if (!DocumentsContract.isTreeUri(uri)) return VaultRootSelectionResult.INVALID_SELECTION
        val accessFlags = grantedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (accessFlags != requiredFlags) return VaultRootSelectionResult.ACCESS_DENIED

        when (inspectSelectedTree(uri)) {
            TreeValidation.Valid -> Unit
            TreeValidation.NotDirectory -> return VaultRootSelectionResult.INVALID_SELECTION
            TreeValidation.AccessDenied -> return VaultRootSelectionResult.ACCESS_DENIED
            TreeValidation.Unavailable -> return VaultRootSelectionResult.UNAVAILABLE
        }

        val resolver = applicationContext.contentResolver
        val alreadyPersisted = try {
            resolver.persistedUriPermissions.any { it.uri == uri }
        } catch (_: SecurityException) {
            return VaultRootSelectionResult.ACCESS_DENIED
        } catch (_: Exception) {
            return VaultRootSelectionResult.UNAVAILABLE
        }
        try {
            resolver.takePersistableUriPermission(uri, accessFlags)
        } catch (_: SecurityException) {
            return VaultRootSelectionResult.ACCESS_DENIED
        } catch (_: IllegalArgumentException) {
            return VaultRootSelectionResult.ACCESS_DENIED
        }
        if (!locations.saveSelectedTree(uri)) {
            if (!alreadyPersisted) {
                try {
                    resolver.releasePersistableUriPermission(uri, accessFlags)
                } catch (_: SecurityException) {
                    // Selection is reported as failed; the inaccessible grant is never used as a root.
                }
            }
            return VaultRootSelectionResult.PERSISTENCE_FAILURE
        }
        return if (selected == null) VaultRootSelectionResult.SELECTED else VaultRootSelectionResult.RECONNECTED
    }

    private fun inspectSelectedTree(treeUri: Uri): TreeValidation = try {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        applicationContext.contentResolver.query(
            documentUri,
            arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return TreeValidation.Unavailable
            val mime = cursor.getString(0)
            val flags = cursor.getLong(1).toInt()
            if (mime != Document.MIME_TYPE_DIR) TreeValidation.NotDirectory
            else if ((flags and Document.FLAG_DIR_SUPPORTS_CREATE) == 0) TreeValidation.NotDirectory
            else TreeValidation.Valid
        } ?: TreeValidation.Unavailable
    } catch (_: SecurityException) {
        TreeValidation.AccessDenied
    } catch (_: IllegalArgumentException) {
        TreeValidation.NotDirectory
    } catch (_: Exception) {
        TreeValidation.Unavailable
    }

    private sealed interface TreeValidation {
        data object Valid : TreeValidation
        data object NotDirectory : TreeValidation
        data object AccessDenied : TreeValidation
        data object Unavailable : TreeValidation
    }
}

/** Starts the Android Storage Access Framework folder picker without returning a Uri to Compose. */
class VaultRootPickerContract(
    private val selectionHandler: VaultRootSelectionHandler,
) : ActivityResultContract<Unit, VaultRootSelectionResult>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )

    override fun parseResult(resultCode: Int, intent: Intent?): VaultRootSelectionResult {
        if (resultCode != Activity.RESULT_OK) return VaultRootSelectionResult.CANCELLED
        val uri = intent?.data ?: return VaultRootSelectionResult.INVALID_SELECTION
        return selectionHandler.accept(uri, intent.flags)
    }
}
