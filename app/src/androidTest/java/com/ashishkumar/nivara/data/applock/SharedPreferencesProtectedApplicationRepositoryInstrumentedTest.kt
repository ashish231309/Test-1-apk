package com.ashishkumar.nivara.data.applock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ashishkumar.nivara.domain.applock.ProtectedApplication
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationLookup
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationUpdateResult
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Verifies application-private SharedPreferences persistence/corruption handling on Android. */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesProtectedApplicationRepositoryInstrumentedTest {
    @Test
    fun packageSetPersistsAtomicallyAndCorruptPayloadNeverBecomesAnEmptySet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "applock_test_${UUID.randomUUID()}"
        val repository = SharedPreferencesProtectedApplicationRepository(context, name)
        val first = ProtectedApplication("com.example.first")
        val second = ProtectedApplication("com.example.second")
        val preferences = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        val preferencesFile = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
        try {
            assertEquals(
                ProtectedApplicationsSnapshot.Available(emptySet()),
                repository.getProtectedApplications(),
            )
            val firstWrite = async(Dispatchers.IO) { repository.setProtected(first, protected = true) }
            val secondWrite = async(Dispatchers.IO) { repository.setProtected(second, protected = true) }
            assertEquals(ProtectedApplicationUpdateResult.UPDATED, firstWrite.await())
            assertEquals(ProtectedApplicationUpdateResult.UPDATED, secondWrite.await())

            val persisted = SharedPreferencesProtectedApplicationRepository(context, name)
                .getProtectedApplications()
            assertEquals(ProtectedApplicationsSnapshot.Available(setOf(first, second)), persisted)
            assertEquals(ProtectedApplicationLookup.Protected, repository.isProtected(first.packageName))
            assertEquals(ProtectedApplicationLookup.NotProtected, repository.isProtected("com.example.other"))

            assertEquals(ProtectedApplicationUpdateResult.UPDATED, repository.protect(first))
            assertEquals(
                ProtectedApplicationsSnapshot.Available(setOf(first, second)),
                repository.getProtectedApplications(),
            )
            assertEquals(ProtectedApplicationUpdateResult.UPDATED, repository.unprotect(first))
            assertEquals(ProtectedApplicationUpdateResult.UPDATED, repository.unprotect(first))
            assertEquals(ProtectedApplicationsSnapshot.Available(setOf(second)), repository.getProtectedApplications())

            assertTrue(preferences.edit().putString("protected_packages", "not a package").commit())
            assertEquals(ProtectedApplicationsSnapshot.Unavailable, repository.getProtectedApplications())
            assertEquals(
                ProtectedApplicationUpdateResult.UNAVAILABLE,
                repository.setProtected(first, protected = false),
            )
        } finally {
            preferences.edit().clear().commit()
            preferencesFile.delete()
            File(preferencesFile.path + ".bak").delete()
        }
    }
}
