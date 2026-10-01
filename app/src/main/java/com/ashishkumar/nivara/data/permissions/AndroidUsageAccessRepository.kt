package com.ashishkumar.nivara.data.permissions

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessSettingsResult
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatusMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidUsageAccessRepository(context: Context) : UsageAccessRepository {
    private val applicationContext = context.applicationContext

    override suspend fun status(): UsageAccessStatus = withContext(Dispatchers.IO) {
        val appOps = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return@withContext UsageAccessStatus.UNAVAILABLE
        if (applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) !is UsageStatsManager) {
            return@withContext UsageAccessStatus.UNAVAILABLE
        }

        try {
            UsageAccessStatusMapping.fromGranted(
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    applicationContext.packageName,
                ) == AppOpsManager.MODE_ALLOWED,
            )
        } catch (_: SecurityException) {
            UsageAccessStatus.UNAVAILABLE
        } catch (_: RuntimeException) {
            UsageAccessStatus.UNAVAILABLE
        }
    }

    override fun openSettings(): UsageAccessSettingsResult = try {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        applicationContext.startActivity(intent)
        UsageAccessSettingsResult.OPENED
    } catch (_: ActivityNotFoundException) {
        UsageAccessSettingsResult.UNAVAILABLE
    } catch (_: SecurityException) {
        UsageAccessSettingsResult.UNAVAILABLE
    } catch (_: RuntimeException) {
        UsageAccessSettingsResult.UNAVAILABLE
    }
}
