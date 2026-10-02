# Nivara hidden-app state

## What “hidden” means

Nivara stores each app's hidden/visible preference for use in its own launcher. **This preference does not hide an app from Android Settings, the Package Manager, the stock launcher, or another launcher.** Hidden-app management and the launcher do not disable, uninstall, suspend, or modify any other application's components.

There is no secret entry sequence, fake identity, vault, scheduled hiding, or alternate recovery mechanism. Camouflage and the ordinary app-drawer entry are described separately.

## Ownership and repository contract

- `HiddenApplication` is Android-free and contains only an exact `packageName`. Package names are validated as non-empty well-formed application identifiers and compared case-sensitively. Labels, icons, launcher titles, and activity names never define identity.
- `HiddenApplicationRepository` is the only owner of the persistent hidden set. `AndroidHiddenApplicationRepository` is the single application-scoped binding in `NivaraContainer`; hidden-app management and the launcher both consume this same contract.
- The management UI and launcher presentation do not know the file path or use `AtomicFile`, the codec, or a storage adapter. The launcher reads current hidden membership from `HiddenApplicationRepository`; it never parses `.nvh` directly or creates a second hidden-state store.
- The launcher composes each discovered `InstalledApplication` with hidden membership in an in-memory presentation snapshot. Hidden and App Lock protected membership are separate dimensions; one does not mutate the other.

## Persistence and fail-closed behavior

- One binary file, `nivara_hidden_applications.nvh`, lives in Nivara's `noBackupFilesDir` and is written with Android `AtomicFile`. It contains only a versioned header, an explicitly length-delimited deterministic package list, and a SHA-256 checksum over the encoded body. It contains no labels, icons, timestamps, credentials, session data, or usage history. The checksum detects accidental corruption; it is **not** encryption or keyed tamper protection, and the preference is not cryptographically secret.
- The codec accepts only the supported version and validates header, payload lengths, count bounds, strict UTF-8, package structure, duplicate package identities, exact file length, and checksum. Truncated, trailing, unknown-version, duplicate, malformed, or checksum-invalid content is `Unreadable`.
- A missing base file and backup on a readable first run means `Available(emptySet())`. A corrupt existing file is `Unreadable`; an I/O/access failure is `Unavailable`. Neither condition is presented as “nothing is hidden”, and neither permits a mutation that would overwrite unknown state.
- Mutations are serialized by the repository mutex. It reads before modifying and delegates writes to `AtomicFile`; a failed write calls `failWrite` and returns `UNAVAILABLE`, leaving the prior complete file authoritative. Management re-reads after mutation outcomes before presenting confirmed state.
- Clearing Nivara's app data or uninstalling Nivara removes this private file. If an app is uninstalled, disabled, or temporarily absent from launcher discovery, its hidden package entry is retained. If the same package name is later installed and launchable again, its existing preference applies until the user explicitly unhides it or removes the stale preference.

## Launcher behavior

- The launcher uses the existing application discovery `ApplicationRepository` launchable catalogue and the same `HiddenApplicationRepository`. In its normal app drawer, discovered hidden packages are omitted; visible apps remain listed with their installed label, icon, and launch action.
- `Available(hiddenSet)` permits filtering. `Unreadable` or `Unavailable` never means “nothing hidden”: the launcher shows a safe state and no app entries. Discovery failure is also distinct from an empty catalogue. The visible drawer is not built from a “show all” fallback.
- Launcher refresh reads discovery and hidden state when Home starts/resumes, when the drawer opens, and on explicit retry. It does not poll continuously. The UI snapshot is temporary presentation data, not a replacement owner or authoritative cache.
- Search is intentionally omitted from the initial launcher drawer; ordering delegates to `InstalledApplicationOrdering`. Search can later reuse `InstalledApplicationSearch` without introducing a second search contract.
- A visible row resolves its launch intent for the exact discovered package. Missing or no-longer-launchable apps produce a generic message and refresh; the launcher does not modify the other app. Icons come from the shared `AndroidApplicationIconProvider`, fall back to a neutral label initial, and are never identity.
- Selecting Nivara as Android Home is a user action through the device's ordinary Home-app settings. Nivara does not change the default launcher automatically. The launcher is still a normal activity and can be left using ordinary Android navigation.

## Temporary authenticated reveal

- The Home screen has a clear **Show hidden apps for this session** control. It is not a secret gesture or recovery path. If there are hidden launchable apps, reveal requires a valid existing `SessionManager.currentState()`; without one, Nivara opens its normal settings/credential activity, where the existing verify flow can establish a session.
- After re-reading discovery and hidden state, the launcher re-checks the existing session immediately before enabling reveal. Reveal does not call `hide`, `unhide`, write the `.nvh` file, authenticate credentials itself, or establish a second session.
- The currently revealed state exists only as an in-memory reference to the exact authenticated `SessionManager` state in the launcher ViewModel, remains subject to the shared absolute session timeout, and is not saved in instance state, preferences, DataStore, or disk. It is cleared on `SessionManager` becoming unauthenticated, Quick Lock, or process recreation. A normal refresh does not extend the session or automatically re-enable a reveal that the user turned off.
- Quick Lock invokes only `SessionManager.lockNow()`. When the session expires or is locked, hidden apps are filtered out again after a repository refresh. If a revealed app is also App Lock protected, launching it does not unprotect it or create an App Lock unlock flag; existing App Lock detection, overlay, and shared-session semantics remain in force.

## Configuration authentication and independence

- Reading discovery, hidden state, and readiness does not require authentication. Hiding or unhiding an app, or forgetting a stale preference, requires a currently authenticated existing `SessionManager` session, rechecked before repository mutation. No hidden-app PIN/password, biometric key, session, timeout, counter, failure tracker, or per-app unlock flag exists.
- `HiddenApplicationRepository` is not coupled to `ProtectedApplicationRepository`. Hiding does not protect, unhide does not unprotect, and App Lock detection/presentation do not consult hidden state. All four combinations—neither, App Lock only, hidden only, and both—are valid.

## Privacy and platform limits

No package names are logged, sent over a network, copied into Intent extras, or reported to analytics. No new Android permission, accessibility service, device-admin/device-owner API, package-manager component disabling, or system-launcher interception is used. Hidden apps remain visible to Android Settings, package tools, privileged software, and other applications with the relevant visibility; “hidden” means only **omitted from Nivara's normal app drawer unless explicitly revealed during the existing authenticated session**.

## Verification status

JVM tests cover hidden-state domain/codec/repository behavior and launcher filtering, fail-closed states, reveal, session expiry, Quick Lock, and process-memory semantics. Static verification covers the exported Home intent exception and persistence/auth boundaries. The Android Home chooser, external app launches, icon rendering, activity lifecycle, session expiry, Quick Lock, and reveal have not been runtime-verified unless the completion report explicitly states otherwise. No system-wide invisibility is claimed or intended.
