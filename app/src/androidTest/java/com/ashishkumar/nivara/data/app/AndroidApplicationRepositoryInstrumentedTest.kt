package com.ashishkumar.nivara.data.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.InstalledApplicationOrdering
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real PackageManager query on the test device; result depends on its installed launchers. */
@RunWith(AndroidJUnit4::class)
class AndroidApplicationRepositoryInstrumentedTest {
    @Test
    fun discoveryReturnsOnlyUniqueSortedLaunchableAppsAndExcludesNivara() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AndroidApplicationRepository(context).discoverLaunchableApplications()

        assertTrue("PackageManager discovery should return an available snapshot", result is ApplicationDiscoveryResult.Available)
        val apps = (result as ApplicationDiscoveryResult.Available).applications
        assertTrue(apps.all { it.isLaunchable })
        assertFalse(apps.any { it.packageName == context.packageName })
        assertEquals(apps.size, apps.map { it.packageName }.distinct().size)
        assertEquals(apps, InstalledApplicationOrdering.deterministic(apps))
    }
}
