package com.ashishkumar.nivara.domain.security

import java.nio.charset.StandardCharsets

/** Non-secret purpose and caller binding authenticated with the ciphertext as AES-GCM associated data. */
class CryptoContext(purpose: String, binding: ByteArray = byteArrayOf()) {
    val purpose: String = purpose.also {
        if (it.isBlank() || it.toByteArray(StandardCharsets.UTF_8).size > MAX_PURPOSE_BYTES) {
            throw SecurityFailure.InvalidParameters()
        }
    }

    private val bindingBytes = binding.copyOf().also {
        if (it.size > MAX_BINDING_BYTES) throw SecurityFailure.InvalidParameters()
    }

    val binding: ByteArray get() = bindingBytes.copyOf()

    companion object {
        const val MAX_PURPOSE_BYTES = 1_024
        const val MAX_BINDING_BYTES = 64 * 1_024
    }
}
