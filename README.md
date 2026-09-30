# Nivara

Nivara is a native Android application intended to provide a privacy and security workspace. The initial release establishes the app foundation; security features will be introduced incrementally in later releases.

## Technology

- Kotlin and native Android
- Jetpack Compose with Material 3
- AndroidX Navigation Compose
- Gradle Kotlin DSL with a version catalog
- Minimum Android version: Android 9 (API 28)

## Build and test

Install JDK 17 and Android SDK Platform 35, then configure the Android SDK path in your local environment or Android Studio. From the project root, use the Gradle wrapper:

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Run `./gradlew connectedDebugAndroidTest` on an API 28+ device or emulator to exercise the Android Keystore instrumentation test. Open the root directory in Android Studio to sync and run the app on a device or emulator.

## Security foundation

Core cryptography, key-management boundaries, and the current envelope/KDF design are documented in [SECURITY.md](SECURITY.md). No credentials, vault content, or recovery state are persisted by the current app.

## Project structure

The single `app` module keeps the project lightweight. Compose UI, navigation, and theme live under `ui`; security contracts and value types are in `domain/security`; JCA and Android Keystore implementations are in `data/security`; and `di` wires them through the application container. UI code does not depend on Android security implementation classes.
