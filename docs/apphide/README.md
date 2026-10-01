# Nivara hidden-app state (Stage 10)

## What “hidden” means in this stage

Stage 10 records the user's hidden/visible preference for a future Nivara custom launcher. The management screen is intentionally explicit: **this preference does not hide an app from Android Settings, the Package Manager, the stock launcher, or another launcher.** Stage 10 does not disable, uninstall, suspend, or modify any other application's components. The eventual omission from Nivara's app drawer belongs to Stage 11.

There is no camouflage, secret entry sequence, fake identity, vault, scheduled hiding, or recovery mechanism in this stage. The app stays installed and usable by Android and other launchers.

## Ownership and Stage 11 contract

- `HiddenApplication` is Android-free and contains only an exact `packageName`. Package names are validated as non-empty well-formed application identifiers and compared case-sensitively. Labels, icons, launcher titles, and activity names never define identity.
- `HiddenApplicationRepository` is the only owner of the persistent hidden set. `AndroidHiddenApplicationRepository` is the single application-scoped binding in `NivaraContainer`; both the Stage 10 management ViewModel and future Stage 11 launcher code should consume this contract.
- The UI depends on the repository interface. It does not know the file path or use `AtomicFile`, the codec, or a storage adapter. Stage 11 must request the set through the same repository and must not parse a file or create another hidden-state store.
- The view model composes each discovered `InstalledApplication` with nullable hidden membership and nullable App Lock protected membership. A null means that dimension could not be read; it is not interpreted as visible/unprotected. App Lock and hidden membership remain independent.

## Persistence format and recovery behavior

- One binary file, `nivara_hidden_applications.nvh`, lives in Nivara's `noBackupFilesDir` and is written with Android `AtomicFile`. It contains only a versioned header, an explicitly length-delimited deterministic package list, and a SHA-256 checksum over the encoded body. It contains no labels, icons, timestamps, credentials, session data, or usage history. The checksum detects accidental corruption; it is **not** encryption or keyed tamper protection.
- The codec accepts only the supported version and validates header, payload lengths, count bounds, strict UTF-8, package structure, duplicate package identities, exact file length, and checksum. Truncated, trailing, unknown-version, duplicate, malformed, or checksum-invalid content is `Unreadable`.
- A missing base file and backup on a readable first run means `Available(emptySet())`. A corrupt existing file is `Unreadable`; an I/O/access failure is `Unavailable`. Neither condition is presented as “nothing is hidden”, and neither permits a mutation that would overwrite unknown state.
- Mutations are serialized by the repository mutex. It reads before modifying and delegates writes to `AtomicFile`; a failed write calls `failWrite` and returns `UNAVAILABLE`, leaving the prior complete file authoritative. The management layer re-reads after returned mutation outcomes and after a thrown write failure; the requested UI state is not treated as confirmed until repository readback.
- Clearing Nivara's app data or uninstalling Nivara removes this private file. If an app is uninstalled, disabled, or temporarily absent from launcher discovery, its hidden package entry is retained. If the same package name is later installed and launchable again, its existing preference applies in Nivara's future launcher until the user explicitly unhides it or removes the stale preference. The stock Android launcher is unaffected either way.

## Discovery, catalogue, and management

- The management destination reuses Stage 6's `ApplicationRepository` and its launcher-only discovery behavior. It adds no package scanner, broad visibility permission, or `QUERY_ALL_PACKAGES` declaration.
- The All and Hidden sections, search, and sorting are presentation filters. Search delegates to the existing `InstalledApplicationSearch` contract (trimmed, case-insensitive label and package matching); sorting delegates to `InstalledApplicationOrdering` (A–Z or the existing deterministic Z–A mode). Neither changes persistent state.
- A successful refresh with no launchable apps is distinct from discovery failure. After a successful catalogue has been seen, a later discovery failure keeps that last successful list available for context, clearly marks it stale, and disables hide/show mutations until a fresh catalogue is available. It never prunes hidden preferences.
- A hidden entry missing from a successful current catalogue is shown as a generic stale saved preference without revealing its package identifier. It remains stored automatically. The user can explicitly forget that exact saved preference after a fresh discovery and hidden-state read; this does not claim the app is installed or alter Package Manager state.
- Empty readable hidden state is shown as no applications hidden. An unreadable/unavailable repository instead shows an explicit error and never renders the Hidden section as empty. Search no-results and a device with no discoverable apps are separate states.

## Configuration authentication and App Lock independence

- Reading discovery, hidden state, and readiness does not require authentication. Hide/show or forgetting a stale hidden preference requires a currently authenticated existing `SessionManager` session, rechecked via `currentState()` just before the repository operation. An expired or Quick-Locked session refuses the mutation and the user is sent to Nivara Home's existing credential flow. This UI does not verify credentials, enroll, create sessions, or extend session timeouts.
- `HiddenApplicationRepository` is not coupled to `ProtectedApplicationRepository`. Hiding does not protect, unhide does not unprotect, and Stage 7/8 detection/presentation do not consult hidden state. All four combinations—neither, App Lock only, hidden only, and both—are valid.
- No hidden-app PIN/password, biometric key, session, timeout, counter, failure tracker, or per-app unlock flag exists.

## Privacy and platform limits

No package names are logged, sent over a network, copied into Intent extras, or reported to analytics. No new Android permission is added. No accessibility service, device-admin/device-owner API, package-manager component disabling, system-launcher interception, or automatic launcher installation is used. The private file and its checksum do not make the preference cryptographically secret; Android Settings, package tools, backups outside this no-backup directory, or privileged software may reveal installed applications. “Hidden” currently means only **recorded as hidden for Nivara's future launcher**.

## Verification status

JVM tests cover domain identity, codec round trips and corruption cases, repository serialization/idempotency/failure behavior, management filtering and session policy, stale packages, refresh failures, and separation from App Lock. An instrumented test covers Android `AtomicFile` persistence and corrupt-file fail-closed behavior. These tests have not been executed on Android hardware/emulator in this Stage 10 change unless the completion report explicitly says otherwise. No Android-wide or stock-launcher hiding behavior is claimed or intended.
