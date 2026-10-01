# Nivara

Nivara is a native Android application intended to provide a privacy and security workspace. Authentication supports one primary PIN, password, or pattern, optional AndroidX biometric authentication as a secondary convenience method, and one in-memory session with an absolute timeout and Quick Lock. Stages 6–7 provide launcher-app discovery, Usage Access setup, package-based protected-app persistence, foreground-event detection, and a user-visible monitoring service. Stage 8 adds a Nivara-owned secure overlay and routes authentication through the existing credential, biometric, and SessionManager authorities. App Lock search/sorting/settings (Stage 9), usage-history features, vault, and recovery remain out of scope.

## Technology

- Kotlin and native Android
- Jetpack Compose with Material 3
- AndroidX Navigation Compose, Lifecycle ViewModel, BiometricPrompt, Preferences DataStore, and foreground-service support
- Kotlin Coroutines for off-main-thread key derivation and platform discovery
- Gradle Kotlin DSL with a version catalog
- Minimum Android version: Android 9 (API 28)

## Build and test

Install JDK 17 and Android SDK Platform 35, then configure the Android SDK path in your local environment or Android Studio. From the project root, use the Gradle wrapper:

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Run `./gradlew connectedDebugAndroidTest` on an API 28+ device or emulator for the PackageManager launcher-discovery, private protected-package preferences, Android Keystore, and AndroidX biometric availability instrumentation checks. The real `UsageStatsManager.queryEvents()` smoke test runs only when Usage Access is already granted on the test device and is skipped otherwise; it does not grant permission or assume a particular foreground package. Usage Access grant/revoke UX, exact foreground-event mapping, foreground-service lifecycle, overlay Settings handoff and window security, touch/Back/home behavior, and background biometric launch still require manual device/emulator verification; these tests do not establish them. The interactive biometric CryptoObject flow requires a compatible device with a strong biometric enrolled and must also be verified manually; the availability check alone does not validate prompt/key authentication. Stage 5 process-death/session-timeout behavior and Stages 6–8 permission, package identity, Settings, and overlay lifecycle checks remain unverified on a runtime device. Run `python3 tools/verify_nivara.py` for static architecture/permission checks. Open the root directory in Android Studio to sync and run the app on a device or emulator.

## Security foundation

Core cryptography, credential verification, session boundaries, persistence, and the Stage 6–8 App Lock architecture are documented in [SECURITY.md](SECURITY.md) and [docs/applock/README.md](docs/applock/README.md). The app persists one primary credential configuration, biometric enablement/throttling metadata, and only the protected package identifiers needed to restore App Lock policy. Session state, presentation request identity, and foreground-event cursors remain process-memory-only. Credentials, biometric templates, raw biometric Keystore keys, usage-event history, per-package unlock state, and a plaintext verifier are not stored. Overlay protection requires the user-granted `SYSTEM_ALERT_WINDOW` capability and is subject to Android background-execution and OEM limitations; it is not a platform-enforced kiosk.

## Project structure

The single `app` module keeps the project lightweight. Compose UI, navigation, and theme live under `ui`; security and credential contracts are in `domain`; JCA, Android Keystore, and DataStore implementations are in `data`; and `di` wires them through the application container. UI code depends on domain service boundaries rather than platform security classes.
