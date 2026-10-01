package com.ashishkumar.nivara.data.apphide

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

internal sealed interface HiddenApplicationFileRead {
    data object Missing : HiddenApplicationFileRead
    data class Data(val bytes: ByteArray) : HiddenApplicationFileRead
    data object Unreadable : HiddenApplicationFileRead
    data object Unavailable : HiddenApplicationFileRead
}

/** Byte-file seam for JVM repository tests; the repository, not this adapter, owns hidden-state semantics. */
internal interface HiddenApplicationFileStore {
    fun read(): HiddenApplicationFileRead
    fun write(bytes: ByteArray): Boolean
}

/** One app-private AtomicFile, excluded from Android backup; no labels or icons are persisted. */
internal class AndroidAtomicHiddenApplicationStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : HiddenApplicationFileStore {
    private val baseFile: File
    private val atomicFile: AtomicFile

    init {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName)
        baseFile = File(context.applicationContext.noBackupFilesDir, fileName)
        atomicFile = AtomicFile(baseFile)
    }

    override fun read(): HiddenApplicationFileRead {
        val parent = baseFile.parentFile ?: return HiddenApplicationFileRead.Unavailable
        if (!parent.exists() || !parent.isDirectory || !parent.canRead() || parent.list() == null) {
            return HiddenApplicationFileRead.Unavailable
        }
        val backupFile = File(baseFile.path + ".bak")
        if (!baseFile.exists() && !backupFile.exists()) return HiddenApplicationFileRead.Missing
        return try {
            atomicFile.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > HiddenApplicationsCodec.MAX_FILE_LENGTH) return HiddenApplicationFileRead.Unreadable
                    output.write(buffer, 0, read)
                }
                HiddenApplicationFileRead.Data(output.toByteArray())
            }
        } catch (_: FileNotFoundException) {
            if (!parent.canRead() || parent.list() == null) HiddenApplicationFileRead.Unavailable
            else if (!baseFile.exists() && !backupFile.exists()) HiddenApplicationFileRead.Missing
            else HiddenApplicationFileRead.Unavailable
        } catch (_: Exception) {
            HiddenApplicationFileRead.Unavailable
        }
    }

    override fun write(bytes: ByteArray): Boolean {
        var stream: FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            stream.write(bytes)
            atomicFile.finishWrite(stream)
            stream = null
            true
        } catch (_: Exception) {
            stream?.let { failedStream -> runCatching { atomicFile.failWrite(failedStream) } }
            false
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "nivara_hidden_applications.nvh"
    }
}
