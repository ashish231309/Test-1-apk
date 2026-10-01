package com.ashishkumar.nivara.data.applock

import android.content.Context
import android.content.Intent
import com.ashishkumar.nivara.MainActivity
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.applock.AppLockAuthenticationRouteResult
import com.ashishkumar.nivara.domain.applock.AppLockAuthenticationRouter
import com.ashishkumar.nivara.domain.applock.AppLockDetectionState
import com.ashishkumar.nivara.domain.applock.AppLockOverlayFeedback
import com.ashishkumar.nivara.domain.applock.AppLockPresentationFailure
import com.ashishkumar.nivara.domain.applock.AppLockPresentationState
import com.ashishkumar.nivara.domain.applock.AppLockPresentationStateMachine
import com.ashishkumar.nivara.domain.applock.AppLockMonitor
import com.ashishkumar.nivara.domain.applock.AuthenticationFactor
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityRepository
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationLookup
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationRepository
import com.ashishkumar.nivara.domain.applock.ProtectedApplicationsSnapshot
import com.ashishkumar.nivara.domain.applock.ProtectedTargetValidator
import com.ashishkumar.nivara.domain.applock.ProtectionDecision
import com.ashishkumar.nivara.domain.applock.ProtectionRequest
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticator
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.credentials.CredentialServiceStatus
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialService
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Application-scoped presentation consumer. The detection service forwards current monitor state here;
 * this coordinator owns request identity, overlay lifecycle, and Stage 3/4-to-Stage 5 routing.
 */
class AndroidAppLockPresentationController(
    context: Context,
    private val monitor: AppLockMonitor,
    private val overlayCapabilityRepository: OverlayCapabilityRepository,
    private val protectedApplicationRepository: ProtectedApplicationRepository,
    private val applicationRepository: ApplicationRepository,
    private val primaryCredentialService: PrimaryCredentialService,
    private val sessionManager: SessionManager,
) : AppLockOverlayActions {
    private val applicationContext = context.applicationContext
    private val nivaraPackageName = applicationContext.packageName
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val machine = AppLockPresentationStateMachine()
    private val authenticationRouter = AppLockAuthenticationRouter(primaryCredentialService, sessionManager)
    private val overlayHost = AndroidAppLockOverlayHost(applicationContext, this)
    private val mutableState = MutableStateFlow<AppLockPresentationState>(AppLockPresentationState.Idle)
    val state = mutableState.asStateFlow()

    private var monitorJob: Job? = null
    private var primaryAuthenticationJob: Job? = null
    private var primaryAuthenticationRequestId: Long? = null
    private var activeFactorAuthenticationRequestId: Long? = null
    private var biometricHostRequestId: Long? = null
    private var suppressedPackageUntilForegroundChanges: String? = null

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            monitor.state.collect { detection ->
                try {
                    handleDetectionState(detection)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    machine.currentRequest()?.let {
                        machine.showFeedback(it.requestId, AppLockOverlayFeedback.PROTECTION_STATUS_UNAVAILABLE)
                        publish()
                    }
                }
            }
        }
    }

    /** Called by the detection service on stop; removal and cancellation are repeat-safe. */
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        primaryAuthenticationJob?.cancel()
        primaryAuthenticationJob = null
        primaryAuthenticationRequestId = null
        suppressedPackageUntilForegroundChanges = null
        invalidateCurrentRequest()
    }

    fun isCurrentRequest(requestId: Long): Boolean = machine.isCurrent(requestId)

    fun currentBiometricRequest(): ProtectionRequest? {
        val state = machine.state as? AppLockPresentationState.Authenticating ?: return null
        return state.request.takeIf { state.factor == AuthenticationFactor.BIOMETRIC }
    }

    fun beginBiometric(requestId: Long) {
        if (!machine.beginAuthentication(requestId, AuthenticationFactor.BIOMETRIC)) return
        activeFactorAuthenticationRequestId = requestId
        biometricHostRequestId = requestId
        publish()
        try {
            applicationContext.startActivity(
                Intent(applicationContext, AppLockBiometricActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (_: RuntimeException) {
            activeFactorAuthenticationRequestId = null
            biometricHostRequestId = null
            machine.showFeedback(requestId, AppLockOverlayFeedback.BIOMETRIC_UNAVAILABLE)
            publish()
        }
    }

    suspend fun authenticateBiometric(requestId: Long, authenticator: BiometricAuthenticator) {
        if (!isCurrentRequest(requestId)) return
        try {
            val route = authenticationRouter.authenticateBiometric(
                authenticate = authenticator::authenticate,
                requestStillValid = { requestStillValid(requestId) },
            )
            handleBiometricRoute(requestId, route)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.BIOMETRIC_UNAVAILABLE)
        } finally {
            if (activeFactorAuthenticationRequestId == requestId) activeFactorAuthenticationRequestId = null
            if (biometricHostRequestId == requestId) biometricHostRequestId = null
        }
    }

    fun biometricUnavailable(requestId: Long) {
        if (activeFactorAuthenticationRequestId == requestId) activeFactorAuthenticationRequestId = null
        if (biometricHostRequestId == requestId) biometricHostRequestId = null
        showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.BIOMETRIC_UNAVAILABLE)
    }

    fun biometricHostCancelled(requestId: Long) {
        if (activeFactorAuthenticationRequestId == requestId) activeFactorAuthenticationRequestId = null
        if (biometricHostRequestId == requestId) biometricHostRequestId = null
        showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.BIOMETRIC_CANCELLED)
    }

    override fun onPrimaryCredential(requestId: Long, credential: CharArray) {
        if (!machine.beginAuthentication(requestId, AuthenticationFactor.PRIMARY)) {
            credential.fill('\u0000')
            return
        }
        activeFactorAuthenticationRequestId = requestId
        publish()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!requestStillValid(requestId)) {
                    showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.PROTECTION_STATUS_UNAVAILABLE)
                    return@launch
                }
                val route = authenticationRouter.authenticatePrimary(credential) {
                    requestStillValid(requestId)
                }
                handlePrimaryRoute(requestId, route)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.PROTECTION_STATUS_UNAVAILABLE)
            } finally {
                credential.fill('\u0000')
                if (primaryAuthenticationRequestId == requestId) {
                    primaryAuthenticationJob = null
                    primaryAuthenticationRequestId = null
                }
                if (activeFactorAuthenticationRequestId == requestId) activeFactorAuthenticationRequestId = null
            }
        }
        primaryAuthenticationJob?.cancel()
        primaryAuthenticationJob = job
        primaryAuthenticationRequestId = requestId
        job.start()
    }

    override fun onPatternInputRejected(requestId: Long) {
        showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.PATTERN_INCOMPLETE)
    }

    override fun onBiometric(requestId: Long) {
        beginBiometric(requestId)
    }

    override fun onReturnToNivara(requestId: Long) {
        if (!machine.isCurrent(requestId)) return
        try {
            applicationContext.startActivity(
                Intent(applicationContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            suppressedPackageUntilForegroundChanges = machine.currentRequest()?.packageName
            invalidateCurrentRequest()
        } catch (_: RuntimeException) {
            showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.PROTECTION_STATUS_UNAVAILABLE)
        }
    }

    override fun onWindowAttachFailed(requestId: Long) {
        scope.launch {
            if (machine.fail(requestId, AppLockPresentationFailure.WINDOW_MANAGER_UNAVAILABLE)) publish()
        }
    }

    private suspend fun handleDetectionState(detection: AppLockDetectionState) {
        when (detection) {
            is AppLockDetectionState.Monitoring -> {
                if (suppressedPackageUntilForegroundChanges != detection.foregroundPackage) {
                    suppressedPackageUntilForegroundChanges = null
                }
                val required = detection.decision as? ProtectionDecision.AuthenticationRequired
                if (required != null && detection.foregroundPackage == required.packageName) {
                    if (suppressedPackageUntilForegroundChanges == required.packageName) return
                    handleRequiredPackage(required.packageName)
                } else if (detection.foregroundPackage != nivaraPackageName) {
                    suppressedPackageUntilForegroundChanges = null
                    invalidateCurrentRequest()
                } else {
                    val current = machine.currentRequest()
                    val authenticatingBiometricHost = current != null &&
                        biometricHostRequestId == current.requestId &&
                        activeFactorAuthenticationRequestId == current.requestId &&
                        (machine.state as? AppLockPresentationState.Authenticating)?.let {
                            it.request.requestId == current.requestId && it.factor == AuthenticationFactor.BIOMETRIC
                        } == true
                    if (current != null && !authenticatingBiometricHost) invalidateCurrentRequest()
                }
            }
            is AppLockDetectionState.Degraded -> {
                val request = machine.currentRequest()
                val capability = overlayStatus()
                if (request != null && capability != OverlayCapabilityStatus.GRANTED) {
                    failOverlayPermission(request.requestId, capability)
                } else if (request != null && machine.state !is AppLockPresentationState.Failed) {
                    machine.showFeedback(request.requestId, AppLockOverlayFeedback.PROTECTION_STATUS_UNAVAILABLE)
                    publish()
                }
            }
            AppLockDetectionState.NoProtectedApplications,
            AppLockDetectionState.Stopped -> invalidateCurrentRequest()
            AppLockDetectionState.Starting -> Unit
        }
    }

    private suspend fun handleRequiredPackage(packageName: String) {
        val existing = machine.currentRequest()
        if (existing != null && existing.packageName != packageName) invalidateCurrentRequest()
        val current = machine.currentRequest()
        if (current != null && current.packageName == packageName) {
            val state = machine.state
            if (state is AppLockPresentationState.Failed) {
                val status = overlayStatus()
                val canRetry = status == OverlayCapabilityStatus.GRANTED &&
                    state.reason in setOf(
                        AppLockPresentationFailure.OVERLAY_PERMISSION_NOT_GRANTED,
                        AppLockPresentationFailure.OVERLAY_PERMISSION_UNAVAILABLE,
                    )
                if (canRetry && machine.retry(current.requestId)) {
                    prepareAndShow(current)
                }
            } else {
                val overlayStatus = overlayStatus()
                if (overlayStatus != OverlayCapabilityStatus.GRANTED) {
                    failOverlayPermission(current.requestId, overlayStatus)
                } else if (sessionManager.currentState() is SessionState.Authenticated) {
                    invalidateCurrentRequest()
                } else if (!requestStillTargetsProtectedPackage(current)) {
                    invalidateCurrentRequest()
                } else if (state is AppLockPresentationState.Showing &&
                    state.feedback == AppLockOverlayFeedback.PROTECTION_STATUS_UNAVAILABLE
                ) {
                    if (machine.clearFeedback(current.requestId)) publish()
                } else if (!overlayHost.isAttached()) {
                    machine.showFeedback(current.requestId, AppLockOverlayFeedback.PROTECTION_STATUS_UNAVAILABLE)
                    publish()
                }
            }
            return
        }

        val detection = monitor.state.value as? AppLockDetectionState.Monitoring ?: return
        val decision = detection.decision as? ProtectionDecision.AuthenticationRequired ?: return
        if (decision.packageName != packageName || detection.foregroundPackage != packageName) return
        if (sessionManager.currentState() is SessionState.Authenticated) return

        val request = machine.requestAuthentication(packageName) ?: return
        when (overlayStatus()) {
            OverlayCapabilityStatus.GRANTED -> {
                publish()
                prepareAndShow(request)
            }
            OverlayCapabilityStatus.NOT_GRANTED -> failOverlayPermission(
                request.requestId,
                OverlayCapabilityStatus.NOT_GRANTED,
            )
            OverlayCapabilityStatus.UNAVAILABLE -> failOverlayPermission(
                request.requestId,
                OverlayCapabilityStatus.UNAVAILABLE,
            )
        }
    }

    private suspend fun prepareAndShow(request: ProtectionRequest) {
        if (!requestStillTargetsProtectedPackage(request)) {
            invalidateCurrentRequest()
            return
        }
        val currentSession = sessionManager.currentState()
        if (currentSession is SessionState.Authenticated) {
            invalidateCurrentRequest()
            return
        }
        val status = try {
            primaryCredentialService.status()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
        val resolvedType = (status as? CredentialServiceStatus.Configured)?.type
        machine.setPrimaryCredentialType(request.requestId, resolvedType)
        if (status !is CredentialServiceStatus.Configured) {
            machine.showFeedback(request.requestId, AppLockOverlayFeedback.PRIMARY_CREDENTIAL_UNAVAILABLE)
        }
        if (!requestStillTargetsProtectedPackage(request)) {
            invalidateCurrentRequest()
            return
        }
        publish()
    }

    private suspend fun requestStillValid(requestId: Long): Boolean {
        if (!machine.isCurrent(requestId)) return false
        return try {
            monitor.refreshNow()
            val request = machine.currentRequest() ?: return false
            if (request.requestId != requestId) return false
            requestStillTargetsProtectedPackage(request)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun requestStillTargetsProtectedPackage(request: ProtectionRequest): Boolean {
        if (!machine.isCurrent(request.requestId) || overlayStatus() != OverlayCapabilityStatus.GRANTED) return false
        val detection = monitor.state.value as? AppLockDetectionState.Monitoring ?: return false
        if (!ProtectedTargetValidator.isCurrent(
                request = request,
                foregroundPackage = detection.foregroundPackage,
                nivaraPackageName = nivaraPackageName,
                protectedApplications = when (val snapshot = protectedApplicationRepository.getProtectedApplications()) {
                    is ProtectedApplicationsSnapshot.Available -> snapshot.applications
                    ProtectedApplicationsSnapshot.Unavailable -> return false
                },
                launchablePackageNames = when (val snapshot = applicationRepository.discoverLaunchableApplications()) {
                    is ApplicationDiscoveryResult.Available -> snapshot.applications
                        .asSequence()
                        .filter { it.isLaunchable }
                        .map { it.packageName }
                        .toSet()
                    ApplicationDiscoveryResult.Unavailable -> return false
                },
                allowNivaraForeground = biometricHostRequestId == request.requestId &&
                    activeFactorAuthenticationRequestId == request.requestId &&
                    (machine.state as? AppLockPresentationState.Authenticating)?.let {
                        it.request.requestId == request.requestId && it.factor == AuthenticationFactor.BIOMETRIC
                    } == true,
            )
        ) return false
        return protectedApplicationRepository.isProtected(request.packageName) == ProtectedApplicationLookup.Protected
    }

    private suspend fun handlePrimaryRoute(
        requestId: Long,
        route: AppLockAuthenticationRouteResult<AuthenticationResult>,
    ) {
        when (route) {
            AppLockAuthenticationRouteResult.ExistingSession -> {
                if (isAuthenticatedForCurrentRequest(requestId)) dismissRequest(requestId)
                else showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.AUTHENTICATION_SUPERSEDED)
            }
            AppLockAuthenticationRouteResult.RequestInvalidated -> {
                if (machine.isCurrent(requestId) && !requestStillTargetsProtectedPackage(machine.currentRequest()!!)) {
                    invalidateCurrentRequest()
                } else {
                    showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.AUTHENTICATION_SUPERSEDED)
                }
            }
            is AppLockAuthenticationRouteResult.Completed -> {
                val completion = route.completion
                if (completion.outcome is AuthenticationResult.Authenticated && completion.sessionEstablished) {
                    if (isAuthenticatedForCurrentRequest(requestId)) {
                        dismissRequest(requestId)
                    } else {
                        sessionManager.lockNow()
                        if (machine.isCurrent(requestId)) invalidateCurrentRequest()
                    }
                } else if (completion.outcome is AuthenticationResult.Authenticated) {
                    showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.AUTHENTICATION_SUPERSEDED)
                } else {
                    showPrimaryOutcome(requestId, completion.outcome)
                }
            }
        }
    }

    private suspend fun handleBiometricRoute(
        requestId: Long,
        route: AppLockAuthenticationRouteResult<BiometricAuthenticationResult>,
    ) {
        when (route) {
            AppLockAuthenticationRouteResult.ExistingSession -> {
                if (isAuthenticatedForCurrentRequest(requestId)) dismissRequest(requestId)
                else showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.AUTHENTICATION_SUPERSEDED)
            }
            AppLockAuthenticationRouteResult.RequestInvalidated -> {
                if (machine.isCurrent(requestId) && !requestStillTargetsProtectedPackage(machine.currentRequest()!!)) {
                    invalidateCurrentRequest()
                } else {
                    showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.BIOMETRIC_CANCELLED)
                }
            }
            is AppLockAuthenticationRouteResult.Completed -> {
                val completion = route.completion
                if (completion.outcome == BiometricAuthenticationResult.Authenticated && completion.sessionEstablished) {
                    if (isAuthenticatedForCurrentRequest(requestId)) {
                        dismissRequest(requestId)
                    } else {
                        sessionManager.lockNow()
                        if (machine.isCurrent(requestId)) invalidateCurrentRequest()
                    }
                } else if (completion.outcome == BiometricAuthenticationResult.Authenticated) {
                    showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.AUTHENTICATION_SUPERSEDED)
                } else {
                    showBiometricOutcome(requestId, completion.outcome)
                }
            }
        }
    }

    private suspend fun isAuthenticatedForCurrentRequest(requestId: Long): Boolean {
        if (!machine.isCurrent(requestId)) return false
        val request = machine.currentRequest()?.takeIf { it.requestId == requestId } ?: return false
        return try {
            sessionManager.currentState() is SessionState.Authenticated &&
                requestStillTargetsProtectedPackage(request)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
    }

    private fun showPrimaryOutcome(requestId: Long, outcome: AuthenticationResult) {
        when (outcome) {
            AuthenticationResult.Failed -> showFeedbackIfCurrent(requestId, AppLockOverlayFeedback.PRIMARY_CREDENTIAL_REJECTED)
            is AuthenticationResult.TemporarilyBlocked -> {
                if (machine.showFeedback(
                        requestId,
                        AppLockOverlayFeedback.PRIMARY_CREDENTIAL_TEMPORARILY_BLOCKED,
                        outcome.retryAfterMillis,
                    )
                ) publish()
            }
            AuthenticationResult.NotConfigured,
            AuthenticationResult.InvalidConfiguration -> showFeedbackIfCurrent(
                requestId,
                AppLockOverlayFeedback.PRIMARY_CREDENTIAL_UNAVAILABLE,
            )
            is AuthenticationResult.Authenticated -> showFeedbackIfCurrent(
                requestId,
                AppLockOverlayFeedback.AUTHENTICATION_SUPERSEDED,
            )
        }
    }

    private fun showBiometricOutcome(requestId: Long, outcome: BiometricAuthenticationResult) {
        val feedback = when (outcome) {
            BiometricAuthenticationResult.UserCancelled,
            BiometricAuthenticationResult.PrimaryCredentialRequired -> AppLockOverlayFeedback.BIOMETRIC_CANCELLED
            BiometricAuthenticationResult.Failed -> AppLockOverlayFeedback.BIOMETRIC_FAILED
            is BiometricAuthenticationResult.TemporarilyBlocked,
            BiometricAuthenticationResult.SystemLockedOut,
            is BiometricAuthenticationResult.Unavailable,
            BiometricAuthenticationResult.Invalidated,
            BiometricAuthenticationResult.SystemError,
            BiometricAuthenticationResult.NotEnabled,
            BiometricAuthenticationResult.PersistenceFailure -> AppLockOverlayFeedback.BIOMETRIC_UNAVAILABLE
            BiometricAuthenticationResult.Authenticated -> AppLockOverlayFeedback.AUTHENTICATION_SUPERSEDED
        }
        showFeedbackIfCurrent(requestId, feedback)
    }

    private fun showFeedbackIfCurrent(requestId: Long, feedback: AppLockOverlayFeedback) {
        if (machine.showFeedback(requestId, feedback)) publish()
    }

    private fun dismissRequest(requestId: Long) {
        cancelPrimaryAuthentication(requestId)
        if (!machine.beginDismiss(requestId)) return
        publish()
        machine.finishDismiss(requestId)
        publish()
    }

    private fun invalidateCurrentRequest() {
        val request = machine.invalidate()
        if (request == null) {
            overlayHost.remove()
            mutableState.value = AppLockPresentationState.Idle
            return
        }
        cancelPrimaryAuthentication(request.requestId)
        publish()
        machine.finishDismiss(request.requestId)
        publish()
    }

    private fun cancelPrimaryAuthentication(requestId: Long) {
        if (primaryAuthenticationRequestId == requestId) {
            primaryAuthenticationJob?.cancel()
            primaryAuthenticationJob = null
            primaryAuthenticationRequestId = null
        }
        if (activeFactorAuthenticationRequestId == requestId) activeFactorAuthenticationRequestId = null
        if (biometricHostRequestId == requestId) biometricHostRequestId = null
    }

    private fun failOverlayPermission(requestId: Long, status: OverlayCapabilityStatus) {
        val reason = when (status) {
            OverlayCapabilityStatus.NOT_GRANTED -> AppLockPresentationFailure.OVERLAY_PERMISSION_NOT_GRANTED
            OverlayCapabilityStatus.UNAVAILABLE -> AppLockPresentationFailure.OVERLAY_PERMISSION_UNAVAILABLE
            OverlayCapabilityStatus.GRANTED -> return
        }
        cancelPrimaryAuthentication(requestId)
        if (machine.fail(requestId, reason)) publish()
    }

    private fun overlayStatus(): OverlayCapabilityStatus = try {
        overlayCapabilityRepository.status()
    } catch (_: RuntimeException) {
        OverlayCapabilityStatus.UNAVAILABLE
    }

    private fun publish() {
        val value = machine.state
        mutableState.value = value
        overlayHost.render(value)
    }
}
