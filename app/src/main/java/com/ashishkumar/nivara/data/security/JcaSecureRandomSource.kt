package com.ashishkumar.nivara.data.security

import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.security.SecurityFailure
import java.security.SecureRandom

/** JCA SecureRandom-backed material source. Android 9+ supplies a platform CSPRNG. */
class JcaSecureRandomSource : SecureRandomSource {
    private val secureRandom = SecureRandom()

    override fun generateBytes(size: Int): ByteArray {
        if (size !in 1..MAX_RANDOM_BYTES) throw SecurityFailure.InvalidParameters()
        return ByteArray(size).also(secureRandom::nextBytes)
    }

    private companion object {
        const val MAX_RANDOM_BYTES = 1 shl 20
    }
}
