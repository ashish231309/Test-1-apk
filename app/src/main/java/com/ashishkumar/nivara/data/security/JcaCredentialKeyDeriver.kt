package com.ashishkumar.nivara.data.security

import com.ashishkumar.nivara.domain.security.CredentialKeyDeriver
import com.ashishkumar.nivara.domain.security.KdfParameters
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.security.SecurityFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA-256 implementation. Derived bytes are returned to the caller and are never persisted here. */
class JcaCredentialKeyDeriver(
    private val random: SecureRandomSource,
) : CredentialKeyDeriver {
    override fun newSalt(parameters: KdfParameters): ByteArray = random.generateSalt(parameters.saltBytes)

    override suspend fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        parameters: KdfParameters,
    ): ByteArray {
        if (password.isEmpty() || salt.size != parameters.saltBytes) {
            throw SecurityFailure.InvalidParameters()
        }
        return withContext(Dispatchers.Default) {
            val passwordCopy = password.copyOf()
            val spec = try {
                PBEKeySpec(
                    passwordCopy,
                    salt.copyOf(),
                    parameters.iterations,
                    parameters.outputBytes * Byte.SIZE_BITS,
                )
            } catch (failure: IllegalArgumentException) {
                passwordCopy.fill('\u0000')
                throw SecurityFailure.InvalidParameters()
            }
            try {
                val derived = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
                    ?: throw SecurityFailure.CryptoOperationFailed()
                if (derived.size != parameters.outputBytes) {
                    derived.fill(0)
                    throw SecurityFailure.CryptoOperationFailed()
                }
                derived
            } catch (failure: SecurityFailure) {
                throw failure
            } catch (failure: GeneralSecurityException) {
                throw SecurityFailure.CryptoOperationFailed()
            } finally {
                spec.clearPassword()
                passwordCopy.fill('\u0000')
            }
        }
    }

    private companion object {
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    }
}
