package com.ashishkumar.nivara.domain.applock

/** Exact package-only check shared by overlay creation and post-authentication result validation. */
object ProtectedTargetValidator {
    fun isCurrent(
        request: ProtectionRequest,
        foregroundPackage: String?,
        nivaraPackageName: String,
        protectedApplications: Set<ProtectedApplication>,
        launchablePackageNames: Set<String>,
        allowNivaraForeground: Boolean = false,
    ): Boolean {
        if (foregroundPackage != request.packageName &&
            !(allowNivaraForeground && foregroundPackage == nivaraPackageName)
        ) return false
        if (request.packageName == nivaraPackageName) return false
        return protectedApplications.any { it.packageName == request.packageName } &&
            request.packageName in launchablePackageNames
    }
}
