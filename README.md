# Nivara

Nivara is a native Android application intended to provide a privacy and security workspace. Authentication supports one primary PIN, password, or pattern, optional AndroidX biometric authentication as a secondary convenience method, and one in-memory session with an absolute timeout and Quick Lock. Stage 6 adds a read-only App Lock preparation screen for launchable-app discovery and Usage Access status. App Lock enforcement, overlays, usage-history features, vault, recovery, and other later-stage features remain out of scope.

## Technology

- Kotlin and native Android
- Jetpack Compose with Material 3
- AndroidX Navigation Compose, Lifecycle ViewModel, BiometricPrompt, and Preferences DataStore
- Kotlin Coroutines for off-main-thread key derivation and platform discovery
- Gradle Kotlin DSL with a version catalog
- Minimum Android version: Android 9 (API 28)

## Build and test

Install JDK 17 and Android SDK Platform 35, then configure the Android SDK path in your local environment or Android Studio. From the project root, use the Gradle wrapper:

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Run `./gradlew connectedDebugAndroidTest` on an API 28+ device or emulator for the PackageManager launcher-discovery, Android Keystore, and AndroidX biometric availability instrumentation checks. The interactive Usage Access Settings handoff and grant/revoke/re-check flow must be verified manually on a device or emulator; the discovery test does not verify Usage Access or Settings behavior. The interactive biometric CryptoObject flow requires a compatible device with a strong biometric enrolled and must also be verified manually; the availability check alone does not validate prompt/key authentication. Open the root directory in Android Studio to sync and run the app on a device or emulator.

## Security foundation

Core cryptography, credential verification, session boundaries, persistence, app discovery, and the Usage Access/overlay decisions are documented in [SECURITY.md](SECURITY.md). The app persists one primary credential configuration and separate biometric enablement/throttling metadata. Session state exists only in process memory and is discarded on process recreation; app-discovery snapshots and setup capability state are also memory-only. Credentials, biometric templates, raw biometric Keystore keys, and a plaintext verifier are not stored.

## Project structure

The single `app` module keeps the project lightweight. Compose UI, navigation, and theme live under `ui`; security and credential contracts are in `domain`; JCA, Android Keystore, and DataStore implementations are in `data`; and `di` wires them through the application container. UI code depends on domain service boundaries rather than platform security classes.
