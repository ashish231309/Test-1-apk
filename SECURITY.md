# Security foundation notes

This document describes the Stage 2 primitives and their boundaries. It is not a claim that the complete application has undergone an external security audit.

## Cryptographic formats and APIs

- Payload encryption is AES-256-GCM with a 128-bit tag and a 96-bit nonce. The `NIVR` binary envelope uses version 1 and algorithm ID 1; its length-prefixed ciphertext contains the JCA-produced tag, so the tag is not duplicated. Parsers reject unknown versions/algorithms, invalid lengths, and truncated input.
- Each encryption gets a fresh nonce from the JCA `SecureRandom` source for ordinary AES keys. AndroidKeyStore keys keep randomized encryption enabled and use the platform provider-generated IV. GCM's 96-bit random nonces have a negligible but non-zero collision probability; future high-volume/per-key use must retain this bound or introduce durable, concurrency-safe nonce allocation before raising usage limits.
- The envelope's version and algorithm identifier, a domain separator, caller-supplied purpose and binding bytes are authenticated as AAD. Callers must supply the same non-secret context when decrypting. No low-level purpose is inferred from UI state.
- Wrapped keys have a separate `NVKW` versioned container, which identifies credential-derived, recovery, or AndroidKeyStore protection. The protection type, purpose and binding are also bound into the inner GCM authentication. The intended design is a random 256-bit content key protected by independent wrapping keys; no user credential is treated as a vault/content key.
- Credential derivation is PBKDF2-HMAC-SHA-256, parameter version 1, 600,000 iterations, a unique 16-byte generated salt, and 32-byte output. The suspend API performs derivation on `Dispatchers.Default`. Callers own returned derived bytes and must clear them when no longer needed; only non-secret KDF parameters and salt should be retained for later derivation.

## Key and secret handling

- AndroidKeyStore aliases are namespaced and validated. Keys are AES-256, GCM-only, no-padding, encrypt/decrypt keys with randomized encryption enabled. Key bytes are not exported. Hardware-backed storage depends on the device/provider and is not guaranteed by this implementation.
- Content and recovery key material is generated with JCA `SecureRandom`; no raw recovery key, credential, derived key, or content key is persisted by this stage. There is no database or preference storage for security state.
- Mutable temporary buffers under application control are cleared where practical. JVM providers, `SecretKeySpec`, `Cipher`, JIT compilation, garbage collection, and platform internals may make additional copies that cannot be reliably overwritten. Therefore this implementation does not claim perfect memory erasure. Avoid converting secrets to `String`, keep their lifetime short, and clear caller-owned password/key arrays after use.
- Security exceptions expose stable generic messages and intentionally omit provider exceptions and sensitive values. Security code does not log key, password, PIN, derived-key, or recovery material.

## Scope and follow-up

No credential enrollment, vault persistence, recovery flow, or UI is included. A future credential stage must version/persist only the KDF salt and parameters alongside a protected key envelope, run derivation away from the main thread, and clear temporary credential/derived arrays. Persistence and migration policy must be designed before storing those envelopes.
