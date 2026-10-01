package com.ashishkumar.nivara.data.apphide

import android.content.Context
import com.ashishkumar.nivara.domain.apphide.HiddenApplication
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationRepository
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationUpdateResult
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The sole repository owner used by Stage 10 management and intended Stage 11 launcher consumers. */
class AndroidHiddenApplicationRepository internal constructor(
    private val fileStore: HiddenApplicationFileStore,
) : HiddenApplicationRepository {
    private val mutex = Mutex()

    override suspend fun getHiddenApplications(): HiddenApplicationsSnapshot =
        withContext(Dispatchers.IO) { mutex.withLock { readLocked() } }

    override suspend fun hide(application: HiddenApplication): HiddenApplicationUpdateResult =
        update(application, hidden = true)

    override suspend fun unhide(application: HiddenApplication): HiddenApplicationUpdateResult =
        update(application, hidden = false)

    private suspend fun update(
        application: HiddenApplication,
        hidden: Boolean,
    ): HiddenApplicationUpdateResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readLocked()
            val available = current as? HiddenApplicationsSnapshot.Available
                ?: return@withLock when (current) {
                    HiddenApplicationsSnapshot.Unreadable -> HiddenApplicationUpdateResult.UNREADABLE
                    HiddenApplicationsSnapshot.Unavailable -> HiddenApplicationUpdateResult.UNAVAILABLE
                    is HiddenApplicationsSnapshot.Available -> error("unreachable")
                }
            val updated = available.applications.toMutableSet()
            val changed = if (hidden) updated.add(application) else updated.remove(application)
            if (!changed) return@withLock HiddenApplicationUpdateResult.ALREADY_IN_STATE

            val encoded = try {
                HiddenApplicationsCodec.encode(updated)
            } catch (_: Exception) {
                return@withLock HiddenApplicationUpdateResult.UNAVAILABLE
            }
            val written = try {
                fileStore.write(encoded)
            } catch (_: Exception) {
                false
            }
            if (written) HiddenApplicationUpdateResult.UPDATED
            else HiddenApplicationUpdateResult.UNAVAILABLE
        }
    }

    private fun readLocked(): HiddenApplicationsSnapshot = try {
        when (val read = fileStore.read()) {
            HiddenApplicationFileRead.Missing -> HiddenApplicationsSnapshot.Available(emptySet())
            HiddenApplicationFileRead.Unreadable -> HiddenApplicationsSnapshot.Unreadable
            HiddenApplicationFileRead.Unavailable -> HiddenApplicationsSnapshot.Unavailable
            is HiddenApplicationFileRead.Data -> HiddenApplicationsCodec.decode(read.bytes)
                ?.let { HiddenApplicationsSnapshot.Available(it) }
                ?: HiddenApplicationsSnapshot.Unreadable
        }
    } catch (_: Exception) {
        HiddenApplicationsSnapshot.Unavailable
    }

    companion object {
        /** The default singleton binding is also the stable contract Stage 11 will consume. */
        fun create(context: Context): AndroidHiddenApplicationRepository =
            AndroidHiddenApplicationRepository(AndroidAtomicHiddenApplicationStore(context))

        /** Isolated file names are useful for Android persistence tests. */
        fun createForFile(context: Context, fileName: String): AndroidHiddenApplicationRepository =
            AndroidHiddenApplicationRepository(AndroidAtomicHiddenApplicationStore(context, fileName))
    }
}
