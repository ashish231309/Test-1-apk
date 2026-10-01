package com.ashishkumar.nivara.data.applock

import android.content.Context
import android.content.SharedPreferences
import com.ashishkumar.nivara.domain.applock.ProtectedApplication
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationLookup
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationRepository
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationUpdateResult
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Private, single-file configuration store. It stores package identifiers only. */
class SharedPreferencesProtectedApplicationRepository(
    context: Context,
    private val preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : ProtectedApplicationRepository {
    private val applicationContext = context.applicationContext
    private val updateMutex = Mutex()

    init {
        require(preferencesName.isNotBlank() && '/' !in preferencesName)
    }

    private val preferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }
    private val preferencesFile: File by lazy {
        File(applicationContext.applicationInfo.dataDir, "shared_prefs/$preferencesName.xml")
    }
    private val backupFile: File by lazy { File(preferencesFile.path + ".bak") }

    override suspend fun getProtectedApplications(): ProtectedApplicationsSnapshot =
        withContext(Dispatchers.IO) { updateMutex.withLock { readLocked() } }

    override suspend fun isProtected(packageName: String): ProtectedApplicationLookup {
        val application = try {
            ProtectedApplication(packageName)
        } catch (_: IllegalArgumentException) {
            return ProtectedApplicationLookup.NotProtected
        }
        return when (val snapshot = getProtectedApplications()) {
            is ProtectedApplicationsSnapshot.Available -> {
                if (application in snapshot.applications) ProtectedApplicationLookup.Protected
                else ProtectedApplicationLookup.NotProtected
            }
            ProtectedApplicationsSnapshot.Unavailable -> ProtectedApplicationLookup.Unavailable
        }
    }

    override suspend fun setProtected(
        application: ProtectedApplication,
        protected: Boolean,
    ): ProtectedApplicationUpdateResult = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            val existing = readLocked() as? ProtectedApplicationsSnapshot.Available
                ?: return@withLock ProtectedApplicationUpdateResult.UNAVAILABLE
            val updated = existing.applications.toMutableSet()
            if (protected) updated.add(application) else updated.remove(application)
            val written = try {
                preferences.edit()
                    .putInt(KEY_FORMAT_VERSION, FORMAT_VERSION)
                    .putString(KEY_PACKAGES, ProtectedApplicationsCodec.encode(updated))
                    .commit()
            } catch (_: Exception) {
                false
            }
            if (written) ProtectedApplicationUpdateResult.UPDATED
            else ProtectedApplicationUpdateResult.UNAVAILABLE
        }
    }

    private fun readLocked(): ProtectedApplicationsSnapshot {
        // A missing file and backup are the well-defined first-install empty configuration. Existing
        // or interrupted-commit data without the versioned payload is degraded, not treated as empty.
        return try {
            if (!preferencesFile.exists() && !backupFile.exists()) {
                return ProtectedApplicationsSnapshot.Available(emptySet())
            }
            if (preferences.getInt(KEY_FORMAT_VERSION, INVALID_FORMAT_VERSION) != FORMAT_VERSION) {
                return ProtectedApplicationsSnapshot.Unavailable
            }
            val encoded = preferences.getString(KEY_PACKAGES, null)
                ?: return ProtectedApplicationsSnapshot.Unavailable
            val decoded = ProtectedApplicationsCodec.decode(encoded)
                ?: return ProtectedApplicationsSnapshot.Unavailable
            ProtectedApplicationsSnapshot.Available(decoded)
        } catch (_: Exception) {
            ProtectedApplicationsSnapshot.Unavailable
        }
    }

    companion object {
        const val DEFAULT_PREFERENCES_NAME = "nivara_protected_applications"
        private const val KEY_FORMAT_VERSION = "format_version"
        private const val KEY_PACKAGES = "protected_packages"
        private const val FORMAT_VERSION = 1
        private const val INVALID_FORMAT_VERSION = -1
    }
}
