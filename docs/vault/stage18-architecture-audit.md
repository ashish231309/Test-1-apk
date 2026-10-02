# Historical vault recovery architecture assessment

**Status:** This document preserves the pre-recovery assessment and design rationale. It is not the current implementation status; see the [vault guide](README.md) and [recovery flow reference](stage18.md) for current behavior. The assessment records the base vault and crypto boundaries that were inspected before recovery was implemented. Recovery subsequently reused existing wrapping/AES-GCM primitives in a distinct optional sidecar and retained the existing content-key hierarchy and encrypted-content formats.

## Findings by boundary

### Vault identity and base metadata

- The implemented non-secret identity type is `VaultId` (a 128-bit random identifier encoded as 32 lowercase hex characters). There is no implemented `VaultIdentity` type.
- `VaultMetadataCodec` format v1 (`NIVLT13M`) is strict, length-delimited, big-endian, bounded to 16 KiB, and carries the `VaultId`, one Stage 2 `WrappedKeyEnvelope`, and one Stage 2 encrypted header. The wrapped-key and header subrecords are each bounded to 8 KiB; malformed, truncated, trailing, and unsupported outer-version records are distinguished.
- The header plaintext is a small versioned record binding the same `VaultId`; it is AES-GCM authenticated. The base wrapped envelope uses `KeyProtection.ANDROID_KEYSTORE` and the existing content-key purpose plus vault identity as authenticated context.
- The random AES-256 content key is the existing root of the Stage 13–17 vault key hierarchy. There is no content-key export or second content key in the existing format. If the installation's Keystore key is missing or invalidated, existing metadata cannot presently unwrap the content key.

### SAF location, storage, initialization, and failure semantics

- `SafVaultStorage` is the sole production `VaultStorage`; `DefaultVaultRepositoryTest.FakeStorage` is the test fake. The SAF location store keeps the selected tree URI in Android-private preferences. The URI never enters domain models or UI state.
- Selection is an Android Storage Access Framework tree grant with persisted read/write permission. Selection validates a directory and its grant; a different root is rejected while a previous root remains selected. Storage checks the persisted grant on every access. No raw filesystem path or broad storage permission is used.
- Before Stage 18, the allowed root layout is exactly `vault.nvmeta`, the initialization temporary `vault.nvmeta.pending`, and `data/`. Content storage uses only bounded SAF document listings under `data/` (`index/`, `objects/`, `organization/`) and fails closed on unexpected structure, duplicate or wrong-type entries, inaccessible providers, and pending writes.
- Initialization is create-only and expects an empty root. It creates `data/`, writes and verifies temporary metadata, renames it, and verifies the final metadata. It must not be used as a recovery/repair operation. Existing or damaged vault locations must not be initialized over.
- SAF provider failures are mapped to typed access-denied/unavailable/write/rename/readback outcomes. Recovery must keep these distinctions and must not clean up existing vault records as an error-recovery shortcut.

### Stage 2 crypto and historical/documented recovery semantics

- Stage 2 (`fdfe05d`, “Stage 2: add core cryptography and key management”) provides secure random bytes, AES-GCM envelopes, generic key wrapping, and PBKDF2 credential derivation. `WrappedKeyEnvelope` is outer version 1 (`NVKW`), identifies `CREDENTIAL_DERIVED`, `RECOVERY`, or `ANDROID_KEYSTORE` protection, and carries the versioned authenticated-encryption envelope. The wrapping service binds protection, caller-supplied purpose, and binding into authenticated context; it checks the unwrapped key is exactly 32 bytes.
- `SecureRandomSource.generateRecoveryKeyBytes()` is a generic 32-byte secure-random convenience. `KeyProtection.RECOVERY` is an existing wire discriminator, but it does not implement recovery semantics by itself.
- Audit of Stage 2 source/history, all current production call sites, and current tests/docs found **no canonical application-level recovery envelope, recovery-code encoding, recovery KDF, recovery metadata slot, recovery repository/flow, reconnect procedure, or production `KeyProtection.RECOVERY` call site**. Therefore Stage 18 must not claim to reuse a pre-existing canonical recovery-envelope implementation. It can reuse Stage 2 wrapping/encryption primitives without changing their wire meaning.
- The current root vault documentation explicitly says the Stage 13 metadata has only the installation-bound Keystore wrapper and reinstall recovery is not implemented. Stage 2 itself explicitly contains no vault persistence or recovery flow.

### Index, objects, organization, trash, and version bounds

- Content objects use Stage 14's per-item random keys and authenticated streaming encryption; item keys are themselves wrapped under the existing vault content key. Recovery must retain the content key and must not rewrite objects or item keys.
- The authenticated index is generation-named and encrypted. `VaultIndexCodec` currently writes v2 (accepts legacy v1), has an 8 MiB plaintext bound and a 20,000-item bound, rejects duplicate item IDs, trailing data, invalid rows, and unsupported versions. Each row carries item metadata and an encrypted item-key envelope; Stage 17 lifecycle/trash state and the optional import digest are in this index. Trash/restore changes only a new authenticated index generation; it does not move or rewrite encrypted objects.
- Index write behavior is immutable-generation/readback oriented. Failed writes do not replace the preceding generation; successful writes prune older index generations according to the established repository policy. Recovery must inspect the current authenticated index and preserve all generations/records; it must not initialize an empty index, rebuild, prune, or “repair” it.
- Organization metadata is independently encrypted/authenticated and generation-named. `VaultOrganizationCodec` v1 is bounded to 8 MiB, 1,000 albums, 20,000 memberships per album, and 100,000 aggregate memberships. Albums contain stable item-ID references. If organization storage is absent, existing repository semantics treat it as an empty organization; a present malformed/unsupported record fails closed. Recovery validation must preserve absence and must not rewrite it.
- Existing item/object diagnostics report missing content, unindexed objects, and unfinished objects without mutating them. A recovery gate should require successful authenticated index and organization reads, while never treating diagnostics as authority to repair or delete data.

### Credentials, biometrics, and sessions

- The primary PIN/password/pattern and its KDF metadata, salt, wrapped verifier, and deterministic attempt-throttle state are held in the app's credential DataStore. Recovery must neither export nor reset/replace this data and must not reproduce the old credential.
- Biometric enrollment/invalidated state and its independent attempt throttle are local installation state backed by DataStore and the platform biometric/Keystore integration. Biometrics are optional and cannot serve as a recovery factor. A reinstall/new installation must enroll biometrics again if desired.
- `DefaultSessionManager` is process-memory only, uses an absolute timeout, and is established only through the ordinary primary or biometric authentication paths. Quick Lock and timeout clear it. Vault initialization and normal sensitive UI actions require the existing session gate. Recovery must not create a `SessionManager` session, bypass the primary credential, or persist session state.
- Recovery material is a 256-bit random artifact, not a human-derived phrase/PIN/password. A bounded parser/checksum is for input error detection; the checksum is not authentication. The AES-GCM envelope tag verifies a candidate. The final implementation additionally serializes recovery attempts and applies a process-memory-only exponential retry delay (one second to a 30-second cap) after failed envelope authentication; malformed input and provider errors remain typed and bounded. This defense-in-depth state is not persisted and resets after process recreation.

### Vault UI and app lifecycle

- `VaultViewModel` uses explicit vault/index/organization states and checks `SessionManager` before initialization, sensitive reads, and mutations. It stores no URI/path/key bytes. `VaultScreen` and `VaultRootConfigurationScreen` have a SAF-only root picker and explicit initialize/status actions; there is no recovery route today.
- `MainActivity` registers root/source SAF result contracts and passes typed results into the existing navigation graph. Reconnection must return through this flow. A fresh install has neither the old persisted URI grant nor the old local credential/biometric/session state; the user must explicitly reselect the exact tree.
- Recovery UI should be small and functional: explicitly start recovery on a selected root, accept a recovery artifact, show generic typed outcomes without raw IDs/crypto/provider details/URI, and explicitly confirm saving a newly generated artifact during setup. Secret material must not be placed in saved state, logs, ordinary preferences, crash reports, clipboard, or automatic sharing/export. Clear mutable arrays and remove one-time code state on completion/cancel/background where practical.

## Compatible Stage 18 design decision

1. Preserve the v1 base metadata, Stage 2 envelope formats, existing content key, item keys, encrypted objects, index generations, album records, and trash lifecycle exactly.
2. Add a distinct, strict, bounded, versioned optional recovery sidecar (not a reinterpretation of `vault.nvmeta` or any Stage 2 envelope version). It carries the authenticated recovery-wrapped **existing** content-key envelope and the non-secret `VaultId`. Use the existing generic `KeyWrappingService`, `KeyProtection.RECOVERY`, secure randomness, and an AAD purpose bound to the exact `VaultId`; do not derive the recovery key from location, installation, credentials, or server/account state.
3. Make setup create-only and explicit: the existing authenticated/session-gated vault is inspected, a 256-bit random recovery key is generated, a one-time human-transfer code is shown, and the user confirms before a temporary-write/readback/rename commits the sidecar. No rotation/replacement is implemented. Cancellation before commit leaves the vault unchanged; a failed or damaged sidecar is reported, never silently repaired.
4. On recovery, explicitly parse the bounded recovery artifact; inspect rather than initialize the selected SAF tree; decode the existing base metadata and sidecar; require matching identity and `RECOVERY` protection; unwrap the sidecar; authenticate/decrypt the existing metadata header and match its `VaultId`; then validate the existing index and organization with that recovered key. Only after all checks pass, wrap the same recovered content key under the new installation's Android Keystore key and persist that **ciphertext wrapper** as app-private reconnect state. Do not persist the recovery artifact or raw key. Failed recovery must not update app-private key state.
5. Recovery success reconnects vault key access only. It does not recover or create primary credentials, biometrics, session state, or SAF grants. The user explicitly reselects the tree when URI grants/preferences are absent; the normal primary-authentication flow remains required to enter the vault UI.
6. No recovery rotation, permanent delete, backup, sync, cloud/server/account recovery, vault-content sharing/export, re-encryption, automatic migration, or index/org reconstruction. Stage 19 presentation changes do not add these capabilities or alter vault/recovery semantics.

## Evidence paths inspected

- `domain/vault/VaultContracts.kt`, `VaultMetadataCodec.kt`, `VaultRecoveryMaterial.kt` (Stage 18 draft), `domain/vault/content/VaultIndexCodec.kt`, `VaultOrganizationCodec.kt`, `VaultItem.kt`, `VaultOrganization.kt`
- `data/vault/SafVaultStorage.kt`, `SafVaultContentStorage.kt`, `VaultRootSelectionHandler.kt`, `DefaultVaultRepository.kt`, `DefaultVaultIndexRepository.kt`, `DefaultVaultOrganizationRepository.kt`
- `domain/security/KeyWrapping.kt`, `CryptoContracts.kt`, `data/security/AesGcmKeyWrappingService.kt`, Stage 2 commit `fdfe05d`
- `data/credentials/DataStorePrimaryCredentialStore.kt`, `domain/security/session/DefaultSessionManager.kt`, `domain/biometrics/DefaultBiometricAuthenticator.kt`, `data/biometrics/DataStoreBiometricStateStore.kt`
- `MainActivity.kt`, `ui/NivaraApp.kt`, `ui/vault/VaultViewModel.kt`, `VaultScreen.kt`, `VaultRootConfigurationScreen.kt`
- `README.md`, `SECURITY.md`, `docs/vault/README.md`, and `docs/vault/stage17.md`
