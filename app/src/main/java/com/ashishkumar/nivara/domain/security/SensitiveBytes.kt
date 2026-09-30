package com.ashishkumar.nivara.domain.security

/** Short-lived mutable secret holder. The callback must not retain its array beyond the call. */
class SensitiveBytes(bytes: ByteArray) : AutoCloseable {
    private var value: ByteArray? = bytes.copyOf()

    internal fun <T> withBytes(block: (ByteArray) -> T): T {
        val current = value ?: throw IllegalStateException("Sensitive bytes have been cleared.")
        return block(current)
    }

    override fun close() {
        value?.fill(0)
        value = null
    }
}
