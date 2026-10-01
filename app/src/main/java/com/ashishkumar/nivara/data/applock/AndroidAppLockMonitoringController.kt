package com.ashishkumar.nivara.data.applock

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ashishkumar.nivara.domain.applock.AppLockDegradedReason
import com.ashishkumar.nivara.domain.applock.AppLockMonitor
import com.ashishkumar.nivara.domain.applock.AppLockMonitoringController
import com.ashishkumar.nivara.domain.applock.MonitoringStartResult
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityRepository
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationRepository
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot
import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus
import kotlinx.coroutines.CancellationException

/** Starts the sole app-lock service only after a real protected set and Usage Access are present. */
class AndroidAppLockMonitoringController(
    context: Context,
    private val usageAccessRepository: UsageAccessRepository,
    private val overlayCapabilityRepository: OverlayCapabilityRepository,
    private val protectedApplicationRepository: ProtectedApplicationRepository,
    private val appLockMonitor: AppLockMonitor,
) : AppLockMonitoringController {
    private val applicationContext = context.applicationContext

    override suspend fun start(): MonitoringStartResult {
        val usageStatus = try {
            usageAccessRepository.status()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            appLockMonitor.reportUnavailable(AppLockDegradedReason.USAGE_ACCESS_UNAVAILABLE)
            return MonitoringStartResult.USAGE_ACCESS_UNAVAILABLE
        }
        when (usageStatus) {
            UsageAccessStatus.GRANTED -> Unit
            UsageAccessStatus.NOT_GRANTED -> {
                appLockMonitor.reportUnavailable(AppLockDegradedReason.USAGE_ACCESS_NOT_GRANTED)
                return MonitoringStartResult.USAGE_ACCESS_NOT_GRANTED
            }
            UsageAccessStatus.UNAVAILABLE -> {
                appLockMonitor.reportUnavailable(AppLockDegradedReason.USAGE_ACCESS_UNAVAILABLE)
                return MonitoringStartResult.USAGE_ACCESS_UNAVAILABLE
            }
        }
        val overlayStatus = try {
            overlayCapabilityRepository.status()
        } catch (_: RuntimeException) {
            OverlayCapabilityStatus.UNAVAILABLE
        }
        when (overlayStatus) {
            OverlayCapabilityStatus.GRANTED -> Unit
            OverlayCapabilityStatus.NOT_GRANTED -> {
                appLockMonitor.reportUnavailable(AppLockDegradedReason.OVERLAY_PERMISSION_NOT_GRANTED)
                return MonitoringStartResult.OVERLAY_PERMISSION_NOT_GRANTED
            }
            OverlayCapabilityStatus.UNAVAILABLE -> {
                appLockMonitor.reportUnavailable(AppLockDegradedReason.OVERLAY_PERMISSION_UNAVAILABLE)
                return MonitoringStartResult.OVERLAY_PERMISSION_UNAVAILABLE
            }
        }

        val snapshot = try {
            protectedApplicationRepository.getProtectedApplications()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            appLockMonitor.reportUnavailable(AppLockDegradedReason.PROTECTED_APPLICATIONS_UNAVAILABLE)
            return MonitoringStartResult.PROTECTED_APPLICATIONS_UNAVAILABLE
        }
        val applications = when (snapshot) {
            is ProtectedApplicationsSnapshot.Available -> snapshot.applications
            ProtectedApplicationsSnapshot.Unavailable -> {
                appLockMonitor.reportUnavailable(AppLockDegradedReason.PROTECTED_APPLICATIONS_UNAVAILABLE)
                return MonitoringStartResult.PROTECTED_APPLICATIONS_UNAVAILABLE
            }
        }
        if (applications.isEmpty()) {
            appLockMonitor.reportNoProtectedApplications()
            stop()
            return MonitoringStartResult.NO_PROTECTED_APPLICATIONS
        }

        return try {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, AppLockDetectionService::class.java),
            )
            MonitoringStartResult.START_REQUESTED
        } catch (_: RuntimeException) {
            // Includes Android's foreground-service background-start and permission restrictions.
            appLockMonitor.reportUnavailable(AppLockDegradedReason.SERVICE_START_UNAVAILABLE)
            MonitoringStartResult.START_UNAVAILABLE
        }
    }

    override fun stop() {
        appLockMonitor.stop()
        try {
            applicationContext.stopService(Intent(applicationContext, AppLockDetectionService::class.java))
        } catch (_: RuntimeException) {
            // Service stop is best-effort; its own destruction also cancels the single monitoring job.
        }
    }
}
