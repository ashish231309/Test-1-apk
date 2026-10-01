package com.ashishkumar.nivara.domain.applock

/** Small transient identity extracted from Android usage events; no label or process identifier is retained. */
data class ForegroundApplication(val packageName: String) {
    init {
        require(packageName.isNotBlank() && packageName == packageName.trim())
    }
}

enum class ForegroundEventKind {
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
    DEVICE_LOCKED_OR_NON_INTERACTIVE,
}

data class ForegroundUsageEvent(
    val timestampMillis: Long,
    val packageName: String?,
    val kind: ForegroundEventKind,
)

sealed interface ForegroundDetectionResult {
    data class Foreground(val application: ForegroundApplication) : ForegroundDetectionResult
    data object NoForeground : ForegroundDetectionResult
    data class Unavailable(val reason: ForegroundUnavailableReason) : ForegroundDetectionResult
}

enum class ForegroundUnavailableReason {
    NO_USABLE_FOREGROUND_EVENT,
    PLATFORM_QUERY_FAILED,
}

interface ForegroundApplicationDetector {
    suspend fun detect(): ForegroundDetectionResult

    /** Clears only the detector's transient event cursor; it does not erase protected-app configuration. */
    fun reset()
}

/** Android-free event reducer. It retains only the currently observed package and event watermark. */
class ForegroundEventReducer {
    private var foregroundPackage: String? = null
    private var hasUsableBaseline = false

    fun reduce(events: Iterable<ForegroundUsageEvent>): ForegroundDetectionResult {
        events.sortedBy(ForegroundUsageEvent::timestampMillis).forEach { event ->
            when (event.kind) {
                ForegroundEventKind.ACTIVITY_RESUMED -> {
                    val packageName = event.packageName?.takeIf(String::isNotBlank) ?: return@forEach
                    foregroundPackage = packageName
                    hasUsableBaseline = true
                }
                ForegroundEventKind.ACTIVITY_PAUSED -> {
                    val packageName = event.packageName?.takeIf(String::isNotBlank) ?: return@forEach
                    if (foregroundPackage == packageName) {
                        foregroundPackage = null
                        hasUsableBaseline = true
                    }
                }
                ForegroundEventKind.DEVICE_LOCKED_OR_NON_INTERACTIVE -> {
                    foregroundPackage = null
                    hasUsableBaseline = true
                }
            }
        }

        if (!hasUsableBaseline) {
            return ForegroundDetectionResult.Unavailable(ForegroundUnavailableReason.NO_USABLE_FOREGROUND_EVENT)
        }
        return foregroundPackage?.let { ForegroundDetectionResult.Foreground(ForegroundApplication(it)) }
            ?: ForegroundDetectionResult.NoForeground
    }

    fun reset() {
        foregroundPackage = null
        hasUsableBaseline = false
    }
}
