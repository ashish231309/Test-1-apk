package com.ashishkumar.nivara.domain.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageAccessStatusMappingTest {
    @Test
    fun completedGrantedCheckMapsToGranted() {
        assertEquals(UsageAccessStatus.GRANTED, UsageAccessStatusMapping.fromGranted(granted = true))
    }

    @Test
    fun completedDeniedCheckMapsToNotGranted() {
        assertEquals(UsageAccessStatus.NOT_GRANTED, UsageAccessStatusMapping.fromGranted(granted = false))
    }
}
