package com.ashishkumar.nivara.domain.applock

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundEventReducerTest {
    @Test
    fun newestRelevantEventWinsEvenWhenInputIsOutOfOrder() {
        val result = ForegroundEventReducer().reduce(
            listOf(
                ForegroundUsageEvent(300, "com.example.b", ForegroundEventKind.ACTIVITY_RESUMED),
                ForegroundUsageEvent(100, "com.example.a", ForegroundEventKind.ACTIVITY_RESUMED),
                ForegroundUsageEvent(200, "com.example.a", ForegroundEventKind.ACTIVITY_PAUSED),
            ),
        )

        assertEquals(
            ForegroundDetectionResult.Foreground(ForegroundApplication("com.example.b")),
            result,
        )
    }

    @Test
    fun pauseOnlyClearsThePackageCurrentlyRecordedAsForeground() {
        val reducer = ForegroundEventReducer()
        reducer.reduce(listOf(ForegroundUsageEvent(1, "com.example.a", ForegroundEventKind.ACTIVITY_RESUMED)))

        val result = reducer.reduce(
            listOf(
                ForegroundUsageEvent(2, "com.example.b", ForegroundEventKind.ACTIVITY_RESUMED),
                ForegroundUsageEvent(3, "com.example.a", ForegroundEventKind.ACTIVITY_PAUSED),
            ),
        )

        assertEquals(ForegroundDetectionResult.Foreground(ForegroundApplication("com.example.b")), result)
    }

    @Test
    fun launcherAndSystemPackagesAreOrdinaryCandidatesButOnlyExplicitProtectedMatchesCanRequireAuth() {
        val candidate = ForegroundDetectionResult.Foreground(ForegroundApplication("com.android.systemui"))
        val decision = ProtectionDecider.decide(
            foreground = (candidate as ForegroundDetectionResult.Foreground).application,
            protectedApplications = emptySet(),
            sessionState = com.ashishkumar.nivara.domain.security.session.SessionState.Unauthenticated,
            nivaraPackageName = "com.example.nivara",
        )

        assertEquals(ProtectionDecision.NoProtectionRequired, decision)
    }

    @Test
    fun nonInteractiveScreenClearsForegroundAndNoInitialEventIsUnavailable() {
        val empty = ForegroundEventReducer().reduce(emptyList())
        assertEquals(
            ForegroundDetectionResult.Unavailable(ForegroundUnavailableReason.NO_USABLE_FOREGROUND_EVENT),
            empty,
        )

        val reducer = ForegroundEventReducer()
        reducer.reduce(listOf(ForegroundUsageEvent(1, "com.example.a", ForegroundEventKind.ACTIVITY_RESUMED)))
        assertEquals(
            ForegroundDetectionResult.NoForeground,
            reducer.reduce(listOf(ForegroundUsageEvent(2, null, ForegroundEventKind.DEVICE_LOCKED_OR_NON_INTERACTIVE))),
        )
    }

    @Test
    fun pauseWithoutAObservedForegroundDoesNotPretendTheDeviceIsIdle() {
        val result = ForegroundEventReducer().reduce(
            listOf(ForegroundUsageEvent(1, "com.example.unknown", ForegroundEventKind.ACTIVITY_PAUSED)),
        )
        assertEquals(
            ForegroundDetectionResult.Unavailable(ForegroundUnavailableReason.NO_USABLE_FOREGROUND_EVENT),
            result,
        )
    }

    @Test
    fun blankPackageResumeDoesNotCreateAForegroundIdentity() {
        val result = ForegroundEventReducer().reduce(
            listOf(ForegroundUsageEvent(1, "  ", ForegroundEventKind.ACTIVITY_RESUMED)),
        )
        assertEquals(
            ForegroundDetectionResult.Unavailable(ForegroundUnavailableReason.NO_USABLE_FOREGROUND_EVENT),
            result,
        )
    }
}
