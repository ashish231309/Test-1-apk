# Nivara

Nivara is a native Android application intended to provide a privacy and security workspace. Authentication supports one primary PIN, password, or pattern, optional AndroidX biometric authentication as a secondary convenience method, and one in-memory session with an absolute timeout and Quick Lock. Stage 6 provides launcher-app discovery and Usage Access setup; Stage 7 adds package-based protected-app persistence, foreground-event detection, and an internal protection decision through one user-visible monitoring service. The App Lock overlay/authentication presentation, usage-history features, vault, and recovery remain out of scope.

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

The debug APK is written to `app/build/outputs/apk/debug/`. Run `./gradlew connectedDebugAndroidTest` on an API 28+ device or emulator for the PackageManager launcher-discovery, private protected-package preferences, Android Keystore, and AndroidX biometric availability instrumentation checks. The real `UsageStatsManager.queryEvents()` smoke test runs only when Usage Access is already granted on the test device and is skipped otherwise; it does not grant permission or assume a particular foreground package. Usage Access grant/revoke UX, exact foreground-event mapping, and foreground-service lifecycle still require manual device/emulator verification; these tests do not establish them. The interactive biometric CryptoObject flow requires a compatible device with a strong biometric enrolled and must also be verified manually; the availability check alone does not validate prompt/key authentication. Run `python3 tools/verify_nivara.py` for static architecture/permission checks. Open the root directory in Android Studio to sync and run the app on a device or emulator.

## Security foundation

Core cryptography, credential verification, session boundaries, persistence, and the Stage 6–7 App Lock decisions are documented in [SECURITY.md](SECURITY.md) and [docs/applock/README.md](docs/applock/README.md). The app persists one primary credential configuration, biometric enablement/throttling metadata, and only the protected package identifiers needed to restore App Lock policy. Session state and foreground-event cursors remain process-memory-only. Credentials, biometric templates, raw biometric Keystore keys, usage-event history, and a plaintext verifier are not stored.

## Project structure

The single `app` module keeps the project lightweight. Compose UI, navigation, and theme live under `ui`; security and credential contracts are in `domain`; JCA, Android Keystore, and DataStore implementations are in `data`; and `di` wires them through the application container. UI code depends on domain service boundaries rather than platform security classes.
