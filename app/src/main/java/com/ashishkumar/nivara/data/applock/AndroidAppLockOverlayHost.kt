package com.ashishkumar.nivara.data.applock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context.INPUT_METHOD_SERVICE
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ashishkumar.nivara.domain.applock.AppLockOverlayFeedback
import com.ashishkumar.nivara.domain.applock.AppLockPresentationState
import com.ashishkumar.nivara.domain.applock.ProtectionRequest
import com.ashishkumar.nivara.domain.credentials.CredentialRules
import com.ashishkumar.nivara.domain.credentials.InvalidCredentialInput
import com.ashishkumar.nivara.domain.credentials.PatternCanonicalizer
import com.ashishkumar.nivara.domain.credentials.PrimaryCredentialType
import kotlin.math.hypot
import kotlin.math.min

internal interface AppLockOverlayActions {
    fun onPrimaryCredential(requestId: Long, credential: CharArray)
    fun onPatternInputRejected(requestId: Long)
    fun onBiometric(requestId: Long)
    fun onReturnToNivara(requestId: Long)
    fun onWindowAttachFailed(requestId: Long)
}

/** Owns the sole WindowManager application-overlay view and makes attach/removal idempotent. */
internal class AndroidAppLockOverlayHost(
    context: Context,
    private val actions: AppLockOverlayActions,
) {
    private val applicationContext = context.applicationContext
    private val windowManager = applicationContext.getSystemService(WindowManager::class.java)
    private var overlayView: AppLockOverlayView? = null
    private var currentRequestId: Long? = null
    private var attached = false

    fun render(state: AppLockPresentationState) {
        when (state) {
            AppLockPresentationState.Idle -> remove()
            is AppLockPresentationState.Dismissing -> remove(state.request.requestId)
            is AppLockPresentationState.Showing -> {
                if (ensureAttached(state.request)) overlayView?.render(state)
            }
            is AppLockPresentationState.Authenticating -> {
                if (currentRequestId == state.request.requestId) overlayView?.render(state)
            }
            is AppLockPresentationState.Failed -> remove(state.request.requestId)
        }
    }

    fun remove(requestId: Long? = null) {
        if (requestId != null && currentRequestId != requestId) return
        val old = overlayView
        overlayView = null
        currentRequestId = null
        if (old != null) old.clearSensitiveInput()
        if ((attached || old?.isAttachedToWindow == true) && old != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(old)
            } catch (_: IllegalArgumentException) {
                // WindowManager may have detached it while the service was stopping.
            } catch (_: RuntimeException) {
                // Cleanup is best-effort and repeat-safe across system window failures.
            }
        }
        attached = false
    }

    fun isAttached(): Boolean = attached

    private fun ensureAttached(request: ProtectionRequest): Boolean {
        if (currentRequestId == request.requestId && attached && overlayView?.isAttachedToWindow == true) return true
        if (currentRequestId != request.requestId || overlayView?.isAttachedToWindow != true) remove()
        val manager = windowManager ?: run {
            actions.onWindowAttachFailed(request.requestId)
            return false
        }
        val view = AppLockOverlayView(applicationContext, request, actions)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.72f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            packageName = applicationContext.packageName
        }
        overlayView = view
        currentRequestId = request.requestId
        return try {
            manager.addView(view, params)
            attached = true
            view.requestInitialFocus()
            true
        } catch (_: SecurityException) {
            remove(request.requestId)
            actions.onWindowAttachFailed(request.requestId)
            false
        } catch (_: WindowManager.BadTokenException) {
            remove(request.requestId)
            actions.onWindowAttachFailed(request.requestId)
            false
        } catch (_: RuntimeException) {
            remove(request.requestId)
            actions.onWindowAttachFailed(request.requestId)
            false
        }
    }
}

private class AppLockOverlayView(
    context: Context,
    private val request: ProtectionRequest,
    private val actions: AppLockOverlayActions,
) : FrameLayout(context) {
    private val field = EditText(context)
    private val patternInput: PatternGridView
    private val primaryButton = Button(context)
    private val biometricButton = Button(context)
    private val returnButton = Button(context)
    private val feedbackView = TextView(context)
    private var currentType: PrimaryCredentialType? = null
    private var isBusy = false

    init {
        isFocusableInTouchMode = true
        isClickable = true
        setBackgroundColor(Color.argb(218, 0, 0, 0))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isSaveEnabled = false

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(22), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        val panelParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(20)
            rightMargin = dp(20)
        }
        addView(panel, panelParams)

        panel.addView(TextView(context).apply {
            text = "Nivara App Lock"
            textSize = 22f
            setTextColor(Color.rgb(31, 45, 42))
            gravity = Gravity.CENTER
        }, matchWidthWrap())
        panel.addView(TextView(context).apply {
            text = "Authenticate to continue"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }, matchWidthWrap())

        field.apply {
            hint = "Primary credential"
            singleLine = true
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            visibility = View.GONE
        }
        panel.addView(field, matchWidthWrap())

        patternInput = PatternGridView(context).apply {
            visibility = View.GONE
            contentDescription = "Primary pattern input"
        }
        panel.addView(patternInput, LinearLayout.LayoutParams(MATCH_PARENT, dp(260)).apply {
            topMargin = dp(6)
        })

        feedbackView.apply {
            textSize = 14f
            setTextColor(Color.rgb(150, 35, 35))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
            visibility = View.GONE
        }
        panel.addView(feedbackView, matchWidthWrap())

        primaryButton.text = "Unlock"
        panel.addView(primaryButton, matchWidthWrap())
        biometricButton.text = "Use biometrics"
        panel.addView(biometricButton, matchWidthWrap())
        returnButton.text = "Return to Nivara"
        panel.addView(returnButton, matchWidthWrap())

        primaryButton.setOnClickListener { submitPrimary() }
        biometricButton.setOnClickListener { if (!isBusy) actions.onBiometric(request.requestId) }
        returnButton.setOnClickListener { actions.onReturnToNivara(request.requestId) }
        patternInput.onIncomplete = { actions.onPatternInputRejected(request.requestId) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                feedbackView.text = "Authentication is required to continue. Use the buttons to authenticate or return to Nivara."
                feedbackView.visibility = View.VISIBLE
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun render(state: AppLockPresentationState) {
        when (state) {
            is AppLockPresentationState.Showing -> {
                isBusy = false
                configureCredentialType(state.primaryCredentialType)
                feedbackView.text = feedbackText(state.feedback, state.retryAfterMillis)
                feedbackView.visibility = if (state.feedback == null && state.primaryCredentialType != null) View.GONE else View.VISIBLE
                val prerequisitesOkay = state.feedback != AppLockOverlayFeedback.PROTECTION_STATUS_UNAVAILABLE
                primaryButton.isEnabled = state.primaryCredentialType != null && prerequisitesOkay
                primaryButton.text = if (state.primaryCredentialType == null) "Primary credential unavailable" else "Unlock"
                biometricButton.isEnabled = prerequisitesOkay
                biometricButton.visibility = View.VISIBLE
                returnButton.isEnabled = true
            }
            is AppLockPresentationState.Authenticating -> {
                isBusy = true
                primaryButton.isEnabled = false
                biometricButton.isEnabled = false
                returnButton.isEnabled = true
                primaryButton.text = "Checking…"
                feedbackView.text = "Authentication in progress."
                feedbackView.visibility = View.VISIBLE
            }
            else -> Unit
        }
    }

    fun requestInitialFocus() {
        requestFocus()
        if (currentType != null && currentType != PrimaryCredentialType.PATTERN) {
            field.requestFocus()
            field.post {
                (context.getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun clearSensitiveInput() {
        field.text?.clear()
        patternInput.clearPattern()
        isBusy = true
    }

    private fun configureCredentialType(type: PrimaryCredentialType?) {
        if (currentType == type) return
        field.text?.clear()
        patternInput.clearPattern()
        currentType = type
        when (type) {
            PrimaryCredentialType.PIN -> {
                field.visibility = View.VISIBLE
                patternInput.visibility = View.GONE
                field.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                field.filters = arrayOf(InputFilter.LengthFilter(CredentialRules.PIN_MAX_LENGTH))
            }
            PrimaryCredentialType.PASSWORD -> {
                field.visibility = View.VISIBLE
                patternInput.visibility = View.GONE
                field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                field.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                field.filters = arrayOf(InputFilter.LengthFilter(CredentialRules.PASSWORD_MAX_LENGTH))
            }
            PrimaryCredentialType.PATTERN -> {
                field.visibility = View.GONE
                patternInput.visibility = View.VISIBLE
            }
            null -> {
                field.visibility = View.GONE
                patternInput.visibility = View.GONE
            }
        }
    }

    private fun submitPrimary() {
        if (isBusy) return
        val type = currentType ?: return
        val credential = if (type == PrimaryCredentialType.PATTERN) {
            val points = patternInput.takePoints()
            try {
                PatternCanonicalizer.canonicalize(points)
            } catch (_: InvalidCredentialInput) {
                actions.onPatternInputRejected(request.requestId)
                return
            } finally {
                points.fill(0)
            }
        } else {
            val editable = field.text
            CharArray(editable.length) { editable[it] }.also { editable.clear() }
        }
        isBusy = true
        (context.getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken, 0)
        actions.onPrimaryCredential(request.requestId, credential)
    }

    private fun feedbackText(feedback: AppLockOverlayFeedback?, retryAfterMillis: Long?): String = when (feedback) {
        null -> if (currentType == null) "Set up or review the primary credential in Nivara." else ""
        AppLockOverlayFeedback.PRIMARY_CREDENTIAL_REJECTED -> "That credential was not accepted. Try again."
        AppLockOverlayFeedback.PRIMARY_CREDENTIAL_TEMPORARILY_BLOCKED -> {
            val seconds = (((retryAfterMillis ?: 1_000L) + 999L) / 1_000L).coerceAtLeast(1)
            "Too many attempts. Try again in $seconds seconds."
        }
        AppLockOverlayFeedback.BIOMETRIC_CANCELLED -> "Biometric authentication was cancelled. Use your primary credential or retry."
        AppLockOverlayFeedback.BIOMETRIC_FAILED -> "Biometric authentication did not complete. Your primary credential remains available."
        AppLockOverlayFeedback.BIOMETRIC_UNAVAILABLE -> "Biometrics are unavailable. Use your primary credential."
        AppLockOverlayFeedback.PROTECTION_STATUS_UNAVAILABLE -> "Nivara cannot verify the active protection request. Authentication is paused; return to Nivara or wait for detection to recover."
        AppLockOverlayFeedback.PATTERN_INCOMPLETE -> "Draw a connected pattern through at least four points."
        AppLockOverlayFeedback.AUTHENTICATION_SUPERSEDED -> "The request changed. Re-enter your credential to continue."
        AppLockOverlayFeedback.PRIMARY_CREDENTIAL_UNAVAILABLE -> "Primary credential configuration is unavailable. Open Nivara to review setup."
    }

    private fun matchWidthWrap() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = dp(6)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class PatternGridView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val points = ArrayList<Int>(9)
    private var drawing = false
    var onIncomplete: (() -> Unit)? = null
    var onPatternChanged: (() -> Unit)? = null

    init {
        isSaveEnabled = false
        isFocusable = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val step = size / 3f
        val radius = size * 0.045f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.025f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(49, 92, 82)
        for (index in 1 until points.size) {
            val a = center(points[index - 1], left, top, step)
            val b = center(points[index], left, top, step)
            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
        }
        paint.style = Paint.Style.FILL
        points.forEach { id ->
            val center = center(id, left, top, step)
            canvas.drawCircle(center.first, center.second, radius * 1.45f, paint)
        }
        paint.color = Color.rgb(49, 92, 82)
        for (id in 0..8) {
            if (id !in points) {
                val center = center(id, left, top, step)
                canvas.drawCircle(center.first, center.second, radius, paint)
            }
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val step = size / 3f
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                clearPattern()
                drawing = true
                addPoint(event.x, event.y, left, top, step)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!drawing) return false
                for (index in 0 until event.historySize) {
                    addPoint(event.getHistoricalX(index), event.getHistoricalY(index), left, top, step)
                }
                addPoint(event.x, event.y, left, top, step)
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (!drawing) return false
                addPoint(event.x, event.y, left, top, step)
                drawing = false
                performClick()
                invalidate()
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                drawing = false
                clearPattern()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onPatternChanged?.invoke()
        return true
    }

    fun takePoints(): IntArray = points.toIntArray().also { clearPattern() }

    fun clearPattern() {
        points.clear()
        invalidate()
    }

    private fun addPoint(x: Float, y: Float, left: Float, top: Float, step: Float) {
        var bestId = -1
        var bestDistance = Float.MAX_VALUE
        for (id in 0..8) {
            val center = center(id, left, top, step)
            val distance = hypot(x - center.first, y - center.second)
            if (distance < bestDistance) {
                bestDistance = distance
                bestId = id
            }
        }
        if (bestId >= 0 && bestDistance <= step * 0.42f && bestId !in points) {
            points += bestId
            invalidate()
        }
    }

    private fun center(id: Int, left: Float, top: Float, step: Float): Pair<Float, Float> =
        left + (id % 3 + 0.5f) * step to top + (id / 3 + 0.5f) * step
}
