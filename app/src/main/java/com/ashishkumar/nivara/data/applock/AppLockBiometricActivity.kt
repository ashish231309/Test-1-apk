package com.ashishkumar.nivara.data.applock

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.ashishkumar.nivara.NivaraApplication
import com.ashishkumar.nivara.domain.applock.AppLockPresentationState
import com.ashishkumar.nivara.domain.applock.AuthenticationFactor
import com.ashishkumar.nivara.domain.biometrics.BiometricStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Translucent, non-exported host required by AndroidX BiometricPrompt's FragmentActivity contract. */
class AppLockBiometricActivity : FragmentActivity() {
    private val container by lazy { (application as NivaraApplication).container }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var promptJob: Job? = null
    private var stateMonitorJob: Job? = null
    private var requestId: Long? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContentView(View(this).apply { setBackgroundColor(Color.TRANSPARENT) })

        if (savedInstanceState != null) {
            container.appLockPresentationController.currentBiometricRequest()?.requestId?.let(
                container.appLockPresentationController::biometricHostCancelled,
            )
            finish()
            return
        }
        val state = container.appLockPresentationController.state.value as? AppLockPresentationState.Authenticating
        if (state == null || state.factor != AuthenticationFactor.BIOMETRIC ||
            !container.appLockPresentationController.isCurrentRequest(state.request.requestId)
        ) {
            finish()
            return
        }
        requestId = state.request.requestId
        stateMonitorJob = activityScope.launch {
            container.appLockPresentationController.state.collect { presentation ->
                val currentRequestId = when (presentation) {
                    AppLockPresentationState.Idle -> null
                    is AppLockPresentationState.Showing -> presentation.request.requestId
                    is AppLockPresentationState.Authenticating -> presentation.request.requestId
                    is AppLockPresentationState.Dismissing -> presentation.request.requestId
                    is AppLockPresentationState.Failed -> presentation.request.requestId
                }
                val invalidated = currentRequestId != state.request.requestId
                val failed = presentation is AppLockPresentationState.Failed &&
                    presentation.request.requestId == state.request.requestId
                val unavailable = presentation is AppLockPresentationState.Showing &&
                    presentation.request.requestId == state.request.requestId &&
                    presentation.feedback == com.ashishkumar.nivara.domain.applock.AppLockOverlayFeedback.PROTECTION_STATUS_UNAVAILABLE
                if (invalidated || failed || unavailable) {
                    promptJob?.cancel()
                    if (!isFinishing) finish()
                }
            }
        }
        promptJob = activityScope.launch {
            val id = state.request.requestId
            try {
                val authenticator = container.biometricAuthenticator(this@AppLockBiometricActivity)
                when (authenticator.status()) {
                    BiometricStatus.Enabled -> {
                        container.appLockPresentationController.authenticateBiometric(id, authenticator)
                    }
                    else -> container.appLockPresentationController.biometricUnavailable(id)
                }
                completed = true
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                container.appLockPresentationController.biometricUnavailable(id)
                completed = true
            } finally {
                if (!completed) container.appLockPresentationController.biometricHostCancelled(id)
                finish()
            }
        }
    }

    override fun onStop() {
        if (!isFinishing) {
            promptJob?.cancel()
            requestId?.let(container.appLockPresentationController::biometricHostCancelled)
        }
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        promptJob?.cancel()
        requestId?.let(container.appLockPresentationController::biometricHostCancelled)
        finish()
    }

    override fun onDestroy() {
        promptJob?.cancel()
        activityScope.cancel()
        super.onDestroy()
    }
}
