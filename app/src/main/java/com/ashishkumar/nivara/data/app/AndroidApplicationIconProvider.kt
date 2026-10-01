package com.ashishkumar.nivara.data.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** Presentation-only PackageManager icon lookup. Icons are neither persisted nor used as application identity. */
class AndroidApplicationIconProvider(context: Context) {
    private val packageManager = context.applicationContext.packageManager

    fun loadIcon(packageName: String): Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
