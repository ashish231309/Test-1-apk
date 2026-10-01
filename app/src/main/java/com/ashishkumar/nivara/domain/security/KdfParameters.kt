package com.ashishkumar.nivara.domain.security

/** Persist these non-secret parameters alongside a future protected key, never the derived key itself. */
data class KdfParameters(
    val version: Int = CURRENT_VERSION,
    val iterations: Int = DEFAULT_ITERATIONS,
    val saltBytes: Int = DEFAULT_SALT_BYTES,
    val outputBytes: Int = DEFAULT_OUTPUT_BYTES,
) {
    init {
        if (version != CURRENT_VERSION) throw SecurityFailure.UnsupportedKdfVersion()
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS ||
            saltBytes !in MIN_SALT_BYTES..MAX_SALT_BYTES || outputBytes != DEFAULT_OUTPUT_BYTES
        ) {
            throw SecurityFailure.InvalidParameters()
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_ITERATIONS = 600_000
        const val MIN_ITERATIONS = 600_000
        const val MAX_ITERATIONS = 10_000_000
        const val DEFAULT_SALT_BYTES = 16
        const val MIN_SALT_BYTES = 16
        const val MAX_SALT_BYTES = 64
        const val DEFAULT_OUTPUT_BYTES = 32

        val DEFAULT = KdfParameters()
    }
}
