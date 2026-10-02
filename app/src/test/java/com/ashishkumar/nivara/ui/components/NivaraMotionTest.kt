package com.ashishkumar.nivara.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class NivaraMotionTest {
    @Test
    fun usesShortRecommendedDurationsWhenSystemAnimationsAreEnabled() {
        assertEquals(140, NivaraMotion.durationMillis(NivaraMotion.QUICK_MILLIS, animationsEnabled = true))
        assertEquals(190, NivaraMotion.durationMillis(NivaraMotion.STATE_MILLIS, animationsEnabled = true))
    }

    @Test
    fun honorsPlatformReducedAnimationPreferenceWithoutChangingNormalDurations() {
        assertEquals(0, NivaraMotion.durationMillis(NivaraMotion.STATE_MILLIS, animationsEnabled = false))
        assertEquals(0, NivaraMotion.durationMillis(-1, animationsEnabled = false))
        assertEquals(0, NivaraMotion.durationMillis(-1, animationsEnabled = true))
    }
}
