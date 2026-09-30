# Nivara

Nivara is a native Android application intended to provide a privacy and security workspace. Current authentication supports one primary PIN, password, or pattern, plus optional AndroidX biometric authentication as a secondary convenience method. Vault, recovery, sessions/app lock, and other later-stage features remain out of scope.

## Technology

- Kotlin and native Android
- Jetpack Compose with Material 3
- AndroidX Navigation Compose, BiometricPrompt, and Preferences DataStore
- Kotlin Coroutines for off-main-thread key derivation
- Gradle Kotlin DSL with a version catalog
- Minimum Android version: Android 9 (API 28)

## Build and test

Install JDK 17 and Android SDK Platform 35, then configure the Android SDK path in your local environment or Android Studio. From the project root, use the Gradle wrapper:

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Run `./gradlew connectedDebugAndroidTest` on an API 28+ device or emulator for the Android Keystore and AndroidX biometric availability instrumentation checks. The interactive biometric CryptoObject flow requires a compatible device with a strong biometric enrolled and must be verified manually; the availability check alone does not validate prompt/key authentication. Open the root directory in Android Studio to sync and run the app on a device or emulator.

## Security foundation

Core cryptography, credential verification, persistence boundaries, and the current envelope/KDF design are documented in [SECURITY.md](SECURITY.md). The app persists one primary credential configuration and separate biometric enablement/throttling metadata. It does not store credentials, biometric templates, raw biometric Keystore keys, or a plaintext verifier.

## Project structure

The single `app` module keeps the project lightweight. Compose UI, navigation, and theme live under `ui`; security and credential contracts are in `domain`; JCA, Android Keystore, and DataStore implementations are in `data`; and `di` wires them through the application container. UI code depends on domain service boundaries rather than platform security classes.
