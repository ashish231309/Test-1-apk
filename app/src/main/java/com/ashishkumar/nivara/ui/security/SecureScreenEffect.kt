package com.ashishkumar.nivara.ui.security

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/** Keeps sensitive/session-related Compose destinations protected by FLAG_SECURE while they are composed. */
@Composable
fun SecureScreenEffect() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) acquireSecureWindow(window)
        onDispose { if (window != null) releaseSecureWindow(window) }
    }
}

private data class SecureWindowLease(
    var references: Int,
    val wasAlreadySecure: Boolean,
)

private val secureWindowLock = Any()
private val secureWindowLeases = WeakHashMap<Window, SecureWindowLease>()

private fun acquireSecureWindow(window: Window) = synchronized(secureWindowLock) {
    val lease = secureWindowLeases[window]
    if (lease == null) {
        val alreadySecure = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        secureWindowLeases[window] = SecureWindowLease(references = 1, wasAlreadySecure = alreadySecure)
    } else {
        lease.references++
    }
}

private fun releaseSecureWindow(window: Window) = synchronized(secureWindowLock) {
    val lease = secureWindowLeases[window] ?: return@synchronized
    lease.references--
    if (lease.references <= 0) {
        if (!lease.wasAlreadySecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        secureWindowLeases.remove(window)
    }
}
