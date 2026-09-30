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

The debug APK is written to `app/build/outputs/apk/debug/`. Open the root directory in Android Studio to sync and run the app on a device or emulator.

## Project structure

The single `app` module keeps the initial project lightweight. The Compose UI, navigation, and theme are separated under `ui`; Android entry-point code stays at the application package root. New domain, data, and platform/security code should be added when a real feature needs it rather than as empty scaffolding.
