package com.ashishkumar.nivara

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherContractInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test
    fun exportedActivityResolvesTheStandardHomeIntentWithinNivara() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setPackage(context.packageName)
        val candidates = context.packageManager.queryIntentActivities(homeIntent, 0)
        val launcher = candidates.single { it.activityInfo.name == LauncherActivity::class.java.name }

        assertTrue(launcher.activityInfo.exported)
        assertEquals(
            LauncherActivity::class.java.name,
            context.packageManager.getActivityInfo(
                ComponentName(context, LauncherActivity::class.java),
                PackageManager.GET_META_DATA,
            ).name,
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun ordinaryAppDrawerEntryResolvesExistingMainActivityForRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appEntry = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val candidates = context.packageManager.queryIntentActivities(appEntry, 0)
        val recovery = candidates.single { it.activityInfo.name == MainActivity::class.java.name }

        assertTrue(recovery.activityInfo.exported)
        assertEquals(
            MainActivity::class.java.name,
            context.packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java),
                PackageManager.GET_META_DATA,
            ).name,
        )
        assertTrue(context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setPackage(context.packageName),
            0,
        ).none { it.activityInfo.name == MainActivity::class.java.name })
    }
}
