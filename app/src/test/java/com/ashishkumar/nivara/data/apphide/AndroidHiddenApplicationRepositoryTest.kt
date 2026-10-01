package com.ashishkumar.nivara.data.apphide

import com.ashishkumar.nivara.domain.apphide.HiddenApplication
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationUpdateResult
import com.ashishkumar.nivara.domain.apphide.HiddenApplicationsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHiddenApplicationRepositoryTest {
    @Test
    fun missingFileIsReadableEmptyAndStateSurvivesRepositoryRecreation() = runBlocking {
        val file = MemoryFileStore()
        val firstRepository = AndroidHiddenApplicationRepository(file)
        assertEquals(HiddenApplicationsSnapshot.Available(emptySet()), firstRepository.getHiddenApplications())

        val camera = HiddenApplication("com.example.camera")
        assertEquals(HiddenApplicationUpdateResult.UPDATED, firstRepository.hide(camera))
        assertEquals(
            HiddenApplicationsSnapshot.Available(setOf(camera)),
            AndroidHiddenApplicationRepository(file).getHiddenApplications(),
        )
    }

    @Test
    fun repeatedHideAndUnhideAreIdempotentAndDeduplicateByExactPackage() = runBlocking {
        val file = MemoryFileStore()
        val repository = AndroidHiddenApplicationRepository(file)
        val app = HiddenApplication("com.example.camera")

        assertEquals(HiddenApplicationUpdateResult.UPDATED, repository.hide(app))
        assertEquals(HiddenApplicationUpdateResult.ALREADY_IN_STATE, repository.hide(app))
        assertEquals(1, file.writes)
        assertEquals(HiddenApplicationUpdateResult.UPDATED, repository.unhide(app))
        assertEquals(HiddenApplicationUpdateResult.ALREADY_IN_STATE, repository.unhide(app))
        assertEquals(2, file.writes)
        assertEquals(HiddenApplicationsSnapshot.Available(emptySet()), repository.getHiddenApplications())
    }

    @Test
    fun unreadableOrUnavailableStateIsNeverTreatedAsEmptyOrOverwritten() = runBlocking {
        val file = MemoryFileStore().apply { state = HiddenApplicationFileRead.Data(byteArrayOf(1, 2, 3)) }
        val repository = AndroidHiddenApplicationRepository(file)
        val app = HiddenApplication("com.example.camera")

        assertEquals(HiddenApplicationsSnapshot.Unreadable, repository.getHiddenApplications())
        assertEquals(HiddenApplicationUpdateResult.UNREADABLE, repository.hide(app))
        assertEquals(HiddenApplicationUpdateResult.UNREADABLE, repository.unhide(app))
        assertEquals(0, file.writes)

        file.state = HiddenApplicationFileRead.Unavailable
        assertEquals(HiddenApplicationsSnapshot.Unavailable, repository.getHiddenApplications())
        assertEquals(HiddenApplicationUpdateResult.UNAVAILABLE, repository.hide(app))
        assertEquals(0, file.writes)
    }

    @Test
    fun failedWritePreservesThePreviousPersistentSnapshot() = runBlocking {
        val existing = HiddenApplication("com.example.notes")
        val file = MemoryFileStore().apply {
            state = HiddenApplicationFileRead.Data(HiddenApplicationsCodec.encode(setOf(existing)))
            failWrites = true
        }
        val repository = AndroidHiddenApplicationRepository(file)

        assertEquals(
            HiddenApplicationUpdateResult.UNAVAILABLE,
            repository.hide(HiddenApplication("com.example.camera")),
        )
        assertEquals(HiddenApplicationsSnapshot.Available(setOf(existing)), repository.getHiddenApplications())
        assertEquals(1, file.writes)
    }

    @Test
    fun concurrentMutationsSerializeWithoutLosingDistinctPackages() = runBlocking {
        val file = MemoryFileStore()
        val repository = AndroidHiddenApplicationRepository(file)
        val apps = (0 until 64).map { HiddenApplication("com.example.app$it") }

        val results = apps.map { app -> async(Dispatchers.Default) { repository.hide(app) } }.awaitAll()
        assertTrue(results.all { it == HiddenApplicationUpdateResult.UPDATED })
        assertEquals(HiddenApplicationsSnapshot.Available(apps.toSet()), repository.getHiddenApplications())
    }

    private class MemoryFileStore : HiddenApplicationFileStore {
        var state: HiddenApplicationFileRead = HiddenApplicationFileRead.Missing
        var failWrites = false
        var writes = 0

        override fun read(): HiddenApplicationFileRead = state

        override fun write(bytes: ByteArray): Boolean {
            writes++
            if (failWrites) return false
            state = HiddenApplicationFileRead.Data(bytes.copyOf())
            return true
        }
    }
}
