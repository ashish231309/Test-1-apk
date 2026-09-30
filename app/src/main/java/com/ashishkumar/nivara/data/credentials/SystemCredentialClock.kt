package com.ashishkumar.nivara.data.credentials

import com.ashishkumar.nivara.domain.credentials.CredentialClock

/** Wall time is used only for persisted temporary throttling, never for cryptographic material. */
class SystemCredentialClock : CredentialClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
