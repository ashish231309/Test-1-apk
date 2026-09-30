package com.ashishkumar.nivara.domain.security

/** Stable, non-sensitive failures exposed by security APIs; provider details are intentionally omitted. */
sealed class SecurityFailure(message: String) : Exception(message) {
    class InvalidEnvelope : SecurityFailure("Encrypted data is invalid.")
    class UnsupportedEnvelopeVersion : SecurityFailure("Encrypted data uses an unsupported format.")
    class UnsupportedAlgorithm : SecurityFailure("Encrypted data uses an unsupported algorithm.")
    class UnsupportedKdfVersion : SecurityFailure("Key derivation parameters are unsupported.")
    class AuthenticationFailed : SecurityFailure("Encrypted data could not be authenticated.")
    class InvalidKey : SecurityFailure("The cryptographic key is invalid.")
    class MissingKey : SecurityFailure("The required device key is unavailable.")
    class KeyInvalidated : SecurityFailure("The device key is no longer usable.")
    class KeyAlreadyExists : SecurityFailure("The device key already exists.")
    class InvalidParameters : SecurityFailure("Cryptographic parameters are invalid.")
    class KeyGenerationFailed : SecurityFailure("A cryptographic key could not be created.")
    class CryptoOperationFailed : SecurityFailure("The cryptographic operation could not be completed.")
}
