package com.ashishkumar.nivara.data.apphide

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ashishkumar.nivara.domain.apphide.HiddenApplication
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationUpdateResult
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationsSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidHiddenApplicationRepositoryInstrumentedTest {
    @Test
    fun atomicFilePersistsAcrossRepositoryRecreationAndCorruptionFailsClosed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "hidden_test_${UUID.randomUUID()}.nvh"
        val file = File(context.noBackupFilesDir, name)
        val backup = File(file.path + ".bak")
        val camera = HiddenApplication("com.example.camera")
        try {
            assertEquals(
                HiddenApplicationsSnapshot.Available(emptySet()),
                AndroidHiddenApplicationRepository.createForFile(context, name).getHiddenApplications(),
            )
            assertEquals(
                HiddenApplicationUpdateResult.UPDATED,
                AndroidHiddenApplicationRepository.createForFile(context, name).hide(camera),
            )
            assertEquals(
                HiddenApplicationsSnapshot.Available(setOf(camera)),
                AndroidHiddenApplicationRepository.createForFile(context, name).getHiddenApplications(),
            )

            file.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
            assertEquals(
                HiddenApplicationsSnapshot.Unreadable,
                AndroidHiddenApplicationRepository.createForFile(context, name).getHiddenApplications(),
            )
            assertEquals(
                HiddenApplicationUpdateResult.UNREADABLE,
                AndroidHiddenApplicationRepository.createForFile(context, name).hide(HiddenApplication("com.example.notes")),
            )
        } finally {
            file.delete()
            backup.delete()
        }
    }
}
