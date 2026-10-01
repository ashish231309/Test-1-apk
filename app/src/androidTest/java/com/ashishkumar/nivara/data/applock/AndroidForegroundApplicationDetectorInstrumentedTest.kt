package com.ashishkumar.nivara.data.applock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ashishkumar.nivara.domain.applock.ForegroundDetectionResult
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus
import com.ashishkumar.nivara.data.permissions.AndroidUsageAccessRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses actual AppOps/UsageStatsManager state; it does not fake or grant Usage Access. */
@RunWith(AndroidJUnit4::class)
class AndroidForegroundApplicationDetectorInstrumentedTest {
    @Test
    fun grantedAccessCanQueryUsageEventsWithoutAssumingAnyInstalledApp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val access = AndroidUsageAccessRepository(context).status()
        assumeTrue("Grant Usage Access on this test device to run the real UsageEvents query", access == UsageAccessStatus.GRANTED)

        val result = AndroidForegroundApplicationDetector(context).detect()
        assertNotNull(result)
        if (result is ForegroundDetectionResult.Foreground) {
            assertTrue(result.application.packageName.isNotBlank())
        }
    }
}
