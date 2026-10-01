package com.ashishkumar.nivara.data.applock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityRepository
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus
import com.ashishkumar.nivara.domain.applock.OverlaySettingsResult

/** Adapter for the user-granted TYPE_APPLICATION_OVERLAY capability. */
class AndroidOverlayCapabilityRepository(context: Context) : OverlayCapabilityRepository {
    private val applicationContext = context.applicationContext

    override fun status(): OverlayCapabilityStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return OverlayCapabilityStatus.UNAVAILABLE
        return try {
            if (Settings.canDrawOverlays(applicationContext)) {
                OverlayCapabilityStatus.GRANTED
            } else {
                OverlayCapabilityStatus.NOT_GRANTED
            }
        } catch (_: RuntimeException) {
            OverlayCapabilityStatus.UNAVAILABLE
        }
    }

    override fun openSettings(): OverlaySettingsResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return OverlaySettingsResult.FAILED
        return try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:${applicationContext.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(intent)
            OverlaySettingsResult.OPENED
        } catch (_: ActivityNotFoundException) {
            OverlaySettingsResult.FAILED
        } catch (_: SecurityException) {
            OverlaySettingsResult.FAILED
        } catch (_: RuntimeException) {
            OverlaySettingsResult.FAILED
        }
    }
}
