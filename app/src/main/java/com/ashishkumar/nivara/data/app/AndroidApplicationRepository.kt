package com.ashishkumar.nivara.data.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.app.InstalledApplication
import com.ashishkumar.nivara.domain.app.InstalledApplicationOrdering
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Queries only apps declaring a launcher activity. Results are kept in-memory by the caller. */
class AndroidApplicationRepository(context: Context) : ApplicationRepository {
    private val applicationContext = context.applicationContext

    override suspend fun discoverLaunchableApplications(): ApplicationDiscoveryResult =
        withContext(Dispatchers.IO) {
            try {
                val packageManager = applicationContext.packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val launcherActivities = packageManager.queryIntentActivities(launcherIntent, 0)
                val applicationsByPackage = LinkedHashMap<String, InstalledApplication>()

                launcherActivities.forEach { resolveInfo ->
                    try {
                        val application = resolve(resolveInfo, packageManager) ?: return@forEach
                        if (application.packageName != applicationContext.packageName) {
                            applicationsByPackage.putIfAbsent(application.packageName, application)
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        // A package may disappear or become inaccessible while this snapshot is built.
                    }
                }

                ApplicationDiscoveryResult.Available(
                    InstalledApplicationOrdering.deterministic(applicationsByPackage.values),
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                ApplicationDiscoveryResult.Unavailable
            }
        }

    private fun resolve(
        resolveInfo: ResolveInfo,
        packageManager: PackageManager,
    ): InstalledApplication? {
        val activityInfo = resolveInfo.activityInfo ?: return null
        if (!activityInfo.enabled) return null
        val packageName = activityInfo.packageName.takeIf(String::isNotBlank) ?: return null
        @Suppress("DEPRECATION")
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        if (!applicationInfo.enabled) return null
        val label = try {
            packageManager.getApplicationLabel(applicationInfo)?.toString()
        } catch (_: Exception) {
            null
        }
        return InstalledApplication(packageName, label, isLaunchable = true)
    }
}
