package com.ashishkumar.nivara.data.applock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ashishkumar.nivara.MainActivity
import com.ashishkumar.nivara.NivaraApplication
import com.ashishkumar.nivara.R
import com.ashishkumar.nivara.domain.applock.AppLockDetectionState
import com.ashishkumar.nivara.domain.applock.AppLockMonitor
import com.ashishkumar.nivara.domain.applock.AppLockPresentationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Owns the existing monitor service and coordinates the process-scoped presentation consumer. */
class AppLockDetectionService : Service() {
    private val appLockMonitor: AppLockMonitor
        get() = (application as NivaraApplication).container.appLockMonitor
    private val presentationController: AndroidAppLockPresentationController
        get() = (application as NivaraApplication).container.appLockPresentationController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var presentationJob: Job? = null
    private var lastNotificationKey: String? = null
    private var presentationAttentionShown = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val starting = AppLockDetectionState.Starting
        lastNotificationKey = notificationKeyFor(starting)
        promoteToForeground(notificationFor(starting))
        presentationController.start()
        appLockMonitor.start()
        presentationJob = serviceScope.launch {
            presentationController.state.collect { state ->
                val attention = state is AppLockPresentationState.Failed
                if (attention != presentationAttentionShown) {
                    presentationAttentionShown = attention
                    if (attention) {
                        getSystemService(NotificationManager::class.java)
                            ?.notify(NOTIFICATION_ID, notificationFor(AppLockDetectionState.Degraded(
                                com.ashishkumar.nivara.domain.applock.AppLockDegradedReason.SERVICE_START_UNAVAILABLE,
                            )))
                    } else {
                        getSystemService(NotificationManager::class.java)
                            ?.notify(NOTIFICATION_ID, notificationFor(appLockMonitor.state.value))
                    }
                }
            }
        }
        notificationJob = serviceScope.launch {
            appLockMonitor.state.collect { state ->
                if (state == AppLockDetectionState.NoProtectedApplications) {
                    stopMonitoring()
                } else {
                    val key = notificationKeyFor(state)
                    if (key != lastNotificationKey) {
                        lastNotificationKey = key
                        if (!presentationAttentionShown) {
                            getSystemService(NotificationManager::class.java)
                                ?.notify(NOTIFICATION_ID, notificationFor(state))
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITORING) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        // start() is idempotent; duplicate start requests share the one process-scoped polling job.
        appLockMonitor.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notificationJob?.cancel()
        presentationJob?.cancel()
        presentationController.stop()
        serviceScope.cancel()
        appLockMonitor.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun stopMonitoring() {
        presentationController.stop()
        appLockMonitor.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationKeyFor(state: AppLockDetectionState): String = when (state) {
        AppLockDetectionState.Stopped -> "stopped"
        AppLockDetectionState.Starting -> "starting"
        AppLockDetectionState.NoProtectedApplications -> "no_protected_applications"
        is AppLockDetectionState.Monitoring -> "monitoring"
        is AppLockDetectionState.Degraded -> "degraded"
    }

    private fun notificationFor(state: AppLockDetectionState): android.app.Notification {
        val statusText = when (state) {
            AppLockDetectionState.Stopped -> getString(R.string.app_lock_monitor_stopped)
            AppLockDetectionState.Starting -> getString(R.string.app_lock_monitor_starting)
            AppLockDetectionState.NoProtectedApplications -> getString(R.string.app_lock_monitor_no_apps)
            is AppLockDetectionState.Monitoring -> getString(R.string.app_lock_monitor_active)
            is AppLockDetectionState.Degraded -> getString(R.string.app_lock_monitor_attention)
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            STOP_SERVICE_REQUEST_CODE,
            Intent(this, AppLockDetectionService::class.java).setAction(ACTION_STOP_MONITORING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.nivara_icon)
            .setContentTitle(getString(R.string.app_lock_monitor_notification_title))
            .setContentText(statusText)
            .setContentIntent(openAppIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(0, getString(R.string.app_lock_monitor_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_lock_monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.app_lock_monitor_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "nivara_app_lock_monitor"
        private const val NOTIFICATION_ID = 731
        private const val OPEN_APP_REQUEST_CODE = 732
        private const val STOP_SERVICE_REQUEST_CODE = 733
        private const val ACTION_STOP_MONITORING = "nivara.action.STOP_APP_LOCK_MONITORING"
    }
}
