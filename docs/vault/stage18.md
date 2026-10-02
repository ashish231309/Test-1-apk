# Stage 18 — Secure Recovery, Reinstall & Vault Reconnection

## Architecture audit result

The full audit is recorded in [stage18-architecture-audit.md](stage18-architecture-audit.md). The short version: the existing identity is `VaultId`; Stage 13 metadata v1 contains exactly one Android-Keystore-wrapped random content key and an authenticated header; and Stage 2 supplies generic `WrappedKeyEnvelope`/AES-GCM/key-wrapping primitives only. Stage 2 has a `KeyProtection.RECOVERY` wire value and a secure-random 32-byte generator, but **there is no canonical application-level recovery envelope or flow to reuse**. Stage 18 adds a separate recovery sidecar and does not change the meaning or bytes of any existing envelope version.

## Recovery security model

Recovery is an explicit portability wrapper for the **same existing 256-bit content key**. It does not add another vault/content key, hidden master key, server/account/cloud dependency, key derived from the folder/URI/device/account/PIN, recovery PIN/password, plaintext key export, or crypto bypass. The normal hierarchy remains:

```text
new installation's local primary authentication ──> process-memory app session
                                                    (not recovered or created by vault recovery)

256-bit random recovery artifact ──Stage 2 KeyWrappingService / RECOVERY AAD──>
existing 256-bit vault content key ──> unchanged item-key envelopes, encrypted objects,
                                       authenticated index (including Trash), and albums
```

The recovery artifact is generated from `SecureRandomSource.generateRecoveryKeyBytes()` (32 bytes / 256 bits). The user-facing `NVR1` code is Base32 with a 20-bit CRC-derived typo checksum. The checksum detects input mistakes; it is **not** authentication. There is no human-secret KDF. The existing authenticated envelope uses a 128-bit AES-GCM tag, and successful parsing alone never authorizes a reconnect. Because the artifact is uniformly random with 256 bits of entropy (and cryptographic authentication still gates success), no separate guessing throttle is required or meaningful for it. Parsing is capped at 128 characters and is deterministic; it accepts only ASCII Base32 plus bounded separators/whitespace. A guessed PIN/password/pattern is never accepted as recovery material.

The code is shown once during setup. It is not placed in preferences, DataStore, app-private files, the recovery sidecar, logs, crash reports, clipboard, email/share intents, downloads, cloud, or backup. The in-memory setup transaction temporarily retains the recovery key only until confirmation/cancel/process death so the committed sidecar can be authenticated against the then-current metadata. Temporary byte and character arrays are cleared in `finally`/cancellation paths where practical. UI state is ordinary in-memory Compose/ViewModel state only and is cleared on success, cancel, and failed attempts; it is not saved in `SavedStateHandle`.

## Sidecar and storage rules

The optional external `vault.nvrec` record is independent from `vault.nvmeta`. Its strict v1 (`NVRC`) framing is:

```text
magic | version byte | 16-byte VaultId | 32-bit envelope length | Stage 2 WrappedKeyEnvelope bytes
```

The total record is bounded to 16 KiB and the wrapped envelope to 8 KiB. Unsupported versions remain explicitly unsupported; malformed, truncated, wrong-type, mismatched-identity, and oversized records fail closed. The embedded Stage 2 envelope must declare `KeyProtection.RECOVERY`. The actual encrypted wrapper uses the existing Stage 2 `KeyWrappingService`, with a distinct recovery purpose and the exact `VaultId` as AAD binding. No existing content bytes or base metadata version are rewritten.

SAF recognizes only the optional `vault.nvrec` file and its temporary sibling `vault.nvrec.pending` in addition to the established root and content layout. Setup is create-only: it writes a temporary sibling, reads it back, renames it, then checks the committed bytes and authenticates the recovery envelope against the current base metadata/header. A prior recovery record is never replaced. Recovery rotation is not supported; if rotation is needed, retain the old artifact until a separately reviewed and tested safe transition exists. An incomplete or damaged sidecar is reported and never silently removed or repaired.

## Setup flow

1. Use the existing authenticated vault UI and choose **Set up recovery** on a vault whose current local key/header verifies.
2. Nivara generates the 256-bit artifact and displays the one-time code. The user stores it privately and separately using their own deliberate method; Nivara has no automatic export/share/copy action.
3. Only after the user taps **I saved the code** does Stage 18 commit the recovery wrapper to `vault.nvrec`.
4. If the SAF write, readback, rename, metadata identity check, envelope authentication, or provider access fails, setup reports a failure and never overwrites `vault.nvmeta`, index, organization, trash, or content objects. A sidecar that was actually committed but cannot be verified is preserved for explicit diagnosis, not deleted by a cleanup path.

## Fresh-install recovery and reconnection flow

1. A reinstall has no old primary credential, biometric enrollment, process session, stored tree URI preference/grant, or app-private wrapped-key cache. The user must create/verify a **new local primary credential** through Nivara's ordinary flow and establish the ordinary session. Recovery does not recover the old PIN/password/pattern and does not establish a session.
2. The user explicitly selects/reselects the original folder through `ACTION_OPEN_DOCUMENT_TREE`. Only the persisted SAF read/write grant is used; a missing/revoked grant requires re-selection. A folder name, URI, or selection is not proof of vault identity.
3. The user explicitly submits the recovery code. Nivara only inspects the selected location; it never runs initialization or repair during recovery. Missing metadata, a foreign/damaged/unsupported vault, unavailable provider, absent recovery record, or wrong recovery material is a typed failure. No empty vault is shown for a failed recovery.
4. Nivara checks the recovery record's framing, version, protection, and `VaultId`; unwraps the existing content key with Stage 2; decrypts and authenticates the existing header and checks the same identity; then reads/authenticates the existing index and organization records before durable reconnection. A missing index is accepted only as the repository's genuine empty-vault state; objects without an index fail validation. Missing optional organization records remain missing/empty; present invalid/unsupported organization data fails closed. Non-zero existing content-inventory diagnostics fail recovery; no diagnostic triggers reconstruction, migration, pruning, or deletion.
5. Only after all required checks pass does Nivara wrap the **same** content key with the new installation's Android Keystore key and atomically store that ciphertext wrapper and `VaultId` in app-private preferences (`AndroidVaultKeyAccessStore`). The raw key and recovery artifact are not persisted. Readback is cryptographically checked; on failure, the prior local wrapper state is restored where possible. The external base metadata, item-key envelopes, objects, index/trash generations, albums, and content records are unchanged.
6. The app returns to ordinary vault status. Primary authentication/session boundaries still apply. Biometrics can be enrolled again locally but are not required for recovery.

Recovery validates the authenticated vault header and the existing index/organization ciphertexts plus the available object inventory. It does not decrypt every potentially large content object during reconnection; each object remains authenticated by the existing Stage 14/15 open/verification path when a user accesses it. No content object is rewritten or removed during recovery.

## Failure states and boundaries

- **Recovery required** means a structurally recognized recovery sidecar exists while the installation's local Keystore wrapper is unavailable. It does not claim the sidecar is authenticated until the user submits valid material.
- Wrong code/foreign vault, damaged or unsupported records, invalid content inventory, unavailable SAF, denied permission, and local persistence failure remain distinct generic UI outcomes. UI never shows raw identity bytes, URI/path, provider exception, or crypto internals.
- Recovery does not modify primary credential DataStore, biometric enrollment/attempts, `SessionManager`, or SAF grants. New installations require local primary credential enrollment/authentication and explicit tree selection.
- Missing or revoked SAF access is not repaired by recovery. No alternate root is selected.
- No recovery rotation, permanent delete, cloud/backup/sync, vault-content share/export, re-encryption, automatic migration, plaintext cache, index/org reconstruction, or Stage 19 UI polish is implemented.

## Verification status

Stage 18 adds Android-free recovery-code/record codec tests, repository/cryptography tests, ViewModel UI-state tests, negative architecture fixtures, and SAF contract coverage. The full GitHub Actions run for commit `db660edc098ca79dac43abcbe28b61ee772c0162` passed: architecture verifier and Python fixtures; `testDebugUnitTest`; debug, Android-test, and release APK assembly; and lint. [Run 36962412365](https://github.com/ashish231309/Test-1-apk/actions/runs/36962412365). Local static verification also passed (`tools/verify_nivara.py`; 96 Python tests). The sandbox has no Java runtime, so local Gradle execution was unavailable; the successful JDK 17 CI run is the compilation/test evidence. `connectedDebugAndroidTest` was not executed on a device or emulator, and no instrumented runtime success is claimed.
