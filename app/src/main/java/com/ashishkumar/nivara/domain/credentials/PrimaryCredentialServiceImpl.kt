package com.ashishkumar.nivara.domain.credentials

import com.ashishkumar.nivara.domain.security.Aes256Key
import com.ashishkumar.nivara.domain.security.CredentialKeyDeriver
import com.ashishkumar.nivara.domain.security.CryptoContext
import com.ashishkumar.nivara.domain.security.KeyProtection
import com.ashishkumar.nivara.domain.security.KeyWrappingService
import com.ashishkumar.nivara.domain.security.KdfParameters
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.security.SecurityFailure
import com.ashishkumar.nivara.domain.security.SensitiveBytes
import com.ashishkumar.nivara.domain.security.WrappedKeyEnvelope
import kotlinx.coroutines.CancellationException

/** Domain use case built only from Stage 2 security contracts and the credential-store boundary. */
class DefaultPrimaryCredentialService(
    private val store: PrimaryCredentialStore,
    private val keyDeriver: CredentialKeyDeriver,
    private val keyWrapping: KeyWrappingService,
    private val random: SecureRandomSource,
    private val clock: CredentialClock,
) : PrimaryCredentialService {
    override suspend fun status(): CredentialServiceStatus = try {
        val configuration = store.load()
        if (configuration == null) CredentialServiceStatus.NotConfigured
        else CredentialServiceStatus.Configured(configuration.type)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        CredentialServiceStatus.InvalidConfiguration
    }

    override suspend fun enroll(
        type: PrimaryCredentialType,
        credential: CharArray,
        confirmation: CharArray,
    ): EnrollmentResult {
        try {
            if (store.load() != null) return EnrollmentResult.AlreadyConfigured
            val rejection = CredentialRules.rejection(type, credential)
            if (rejection != null) return EnrollmentResult.Rejected(rejection)
            if (!CredentialRules.securelyMatches(credential, confirmation)) {
                return EnrollmentResult.ConfirmationMismatch
            }
            val stored = createStoredCredential(type, credential)
            return if (store.installIfAbsent(stored)) EnrollmentResult.Enrolled(type)
            else EnrollmentResult.AlreadyConfigured
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            return EnrollmentResult.StorageFailure
        } finally {
            credential.fill('\u0000')
            confirmation.fill('\u0000')
        }
    }

    override suspend fun authenticate(credential: CharArray): AuthenticationResult =
        authenticateInternal(credential)

    override suspend fun changePrimary(
        currentCredential: CharArray,
        newType: PrimaryCredentialType,
        newCredential: CharArray,
        confirmation: CharArray,
    ): CredentialChangeResult {
        try {
            when (val authentication = authenticateInternal(currentCredential)) {
                AuthenticationResult.NotConfigured -> return CredentialChangeResult.NotConfigured
                AuthenticationResult.Failed -> return CredentialChangeResult.AuthenticationFailed
                is AuthenticationResult.TemporarilyBlocked ->
                    return CredentialChangeResult.TemporarilyBlocked(authentication.retryAfterMillis)
                AuthenticationResult.InvalidConfiguration -> return CredentialChangeResult.InvalidConfiguration
                is AuthenticationResult.Authenticated -> Unit
            }
            val rejection = CredentialRules.rejection(newType, newCredential)
            if (rejection != null) return CredentialChangeResult.Rejected(rejection)
            if (!CredentialRules.securelyMatches(newCredential, confirmation)) {
                return CredentialChangeResult.ConfirmationMismatch
            }
            val stored = createStoredCredential(newType, newCredential)
            store.replace(stored)
            return CredentialChangeResult.Changed(newType)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            return CredentialChangeResult.StorageFailure
        } finally {
            currentCredential.fill('\u0000')
            newCredential.fill('\u0000')
            confirmation.fill('\u0000')
        }
    }

    private suspend fun authenticateInternal(credential: CharArray): AuthenticationResult {
        try {
            val stored = store.load() ?: return AuthenticationResult.NotConfigured
            val now = clock.nowEpochMillis().coerceAtLeast(0)
            val retryAfter = AttemptThrottlePolicy.retryAfter(store.attempts(), now)
            if (retryAfter > 0) return AuthenticationResult.TemporarilyBlocked(retryAfter)

            if (CredentialRules.rejection(stored.type, credential) != null) {
                return recordFailure(now)
            }
            val salt = stored.salt
            val verifierEnvelopeBytes = stored.wrappedVerifier
            var derivedBytes: ByteArray? = null
            var unwrappedVerifier: ByteArray? = null
            try {
                val generatedDerived = keyDeriver.deriveKey(credential, salt, stored.kdf)
                derivedBytes = generatedDerived
                val sensitiveDerived = SensitiveBytes(generatedDerived)
                generatedDerived.fill(0)
                derivedBytes = null
                val wrappingKey = try {
                    sensitiveDerived.withBytes { Aes256Key.fromBytes(it) }
                } finally {
                    sensitiveDerived.close()
                }
                val wrapped = WrappedKeyEnvelope.decode(verifierEnvelopeBytes)
                unwrappedVerifier = keyWrapping.unwrap(
                    wrapped,
                    wrappingKey,
                    KeyProtection.CREDENTIAL_DERIVED,
                    verificationContext(stored.type),
                )
                if (unwrappedVerifier.size != Aes256Key.KEY_BYTES) {
                    return AuthenticationResult.InvalidConfiguration
                }
                store.resetAttempts()
                return AuthenticationResult.Authenticated(stored.type)
            } catch (failure: SecurityFailure.AuthenticationFailed) {
                return recordFailure(now)
            } catch (failure: SecurityFailure) {
                return AuthenticationResult.InvalidConfiguration
            } finally {
                salt.fill(0)
                verifierEnvelopeBytes.fill(0)
                derivedBytes?.fill(0)
                unwrappedVerifier?.fill(0)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: CredentialPersistenceFailure) {
            return AuthenticationResult.InvalidConfiguration
        } catch (failure: SecurityFailure) {
            return AuthenticationResult.InvalidConfiguration
        } catch (failure: Exception) {
            return AuthenticationResult.InvalidConfiguration
        } finally {
            credential.fill('\u0000')
        }
    }

    private suspend fun recordFailure(now: Long): AuthenticationResult {
        val state = store.recordFailure(now)
        val wait = AttemptThrottlePolicy.retryAfter(state, now)
        return if (wait > 0) AuthenticationResult.TemporarilyBlocked(wait)
        else AuthenticationResult.Failed
    }

    private suspend fun createStoredCredential(
        type: PrimaryCredentialType,
        credential: CharArray,
    ): StoredPrimaryCredential {
        val parameters = KdfParameters.DEFAULT
        val salt = keyDeriver.newSalt(parameters)
        var verifier: ByteArray? = null
        var derived: ByteArray? = null
        try {
            val generatedVerifier = random.generateAes256KeyBytes()
            verifier = generatedVerifier
            val generatedDerived = keyDeriver.deriveKey(credential, salt, parameters)
            derived = generatedDerived
            val sensitiveDerived = SensitiveBytes(generatedDerived)
            generatedDerived.fill(0)
            derived = null
            val wrappingKey = try {
                sensitiveDerived.withBytes { Aes256Key.fromBytes(it) }
            } finally {
                sensitiveDerived.close()
            }
            val wrapped = keyWrapping.wrap(
                generatedVerifier,
                wrappingKey,
                KeyProtection.CREDENTIAL_DERIVED,
                verificationContext(type),
            )
            val encodedEnvelope = wrapped.encode()
            return try {
                StoredPrimaryCredential(type, parameters, salt, encodedEnvelope)
            } finally {
                encodedEnvelope.fill(0)
            }
        } finally {
            salt.fill(0)
            verifier?.fill(0)
            derived?.fill(0)
        }
    }

    private fun verificationContext(type: PrimaryCredentialType): CryptoContext = CryptoContext(
        purpose = "nivara.primary-credential-verifier.v1",
        binding = byteArrayOf(type.verifierContextId),
    )
}
