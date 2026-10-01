# Nivara

Nivara is a native Android privacy and security workspace with one primary PIN, password, or pattern, optional AndroidX biometric authentication, and one process-memory session with an absolute timeout and Quick Lock. Stages 6–9 provide shared launchable-app discovery and App Lock configuration, detection, and secure authentication. Stage 10 adds a durable hidden-app preference; Stage 11 adds a selectable Android Home activity, Nivara home surface, and app drawer that omits hidden applications. Hidden apps remain installed and functional in Android and other launchers. A normal, explicit authenticated reveal is temporary and session-bound. Nivara does not automatically become the default Home application. Stage 12 identity camouflage and recovery entry are not implemented.

## Custom launcher and Android Home selection

Nivara offers a standard Android Home activity and app drawer using the existing `ApplicationRepository` and `HiddenApplicationRepository`. Hidden applications are omitted from Nivara's normal drawer only; they remain installed and launchable elsewhere. If hidden-state storage cannot be confirmed, Nivara displays no drawer entries rather than exposing an unknown hidden app. An explicit **Show hidden apps for this session** action uses the existing `SessionManager`; Quick Lock, session expiry, or process recreation clears the temporary reveal. App Lock remains independently configured and monitored.

To try Nivara as Home, choose it manually through Android's normal Home settings (**Settings → Apps → Default apps → Home app** on many devices; wording varies by Android version and device maker). Nivara does not change the default automatically. The Home activity and app selection have not been runtime-verified on a device/emulator in this change. Search is intentionally omitted from the initial drawer; it can later reuse the existing `InstalledApplicationSearch` contract.

Stage 12 — App Identity Camouflage & Recovery Entry — is not implemented. Nivara remains recognizable and has a normal settings route from its Home screen.

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

The debug APK is written to `app/build/outputs/apk/debug/`. Run `./gradlew connectedDebugAndroidTest` on an API 28+ device or emulator for PackageManager discovery/icons, repository storage, Android Keystore, biometric availability, and the launcher Home-intent contract. The launcher instrumented contract test checks that the exported Home component resolves when scoped to Nivara; it does not select Nivara as the user's default Home. The app intentionally omits drawer search in this stage and retains the shared name ordering. JVM tests cover Stage 9 management, Stage 10 hidden state, and Stage 11 filtering/reveal/session/fail-closed behavior. The real `UsageStatsManager.queryEvents()` smoke test runs only when Usage Access is already granted; it does not grant permission or assume a foreground package. Home-app selection, real Android Home resolution, external application launching, icon rendering in the drawer, activity lifecycle, session expiry, Quick Lock, TalkBack, Usage Access UX, overlay behavior, and interactive biometric authentication still require device/emulator verification. Run `python3 tools/verify_nivara.py`, `python3 -m unittest tools/test_verify_hidden_architecture.py`, and `python3 -m unittest tools/test_verify_launcher_architecture.py` for static and negative architecture checks. Open the root directory in Android Studio to sync and run the app on a device or emulator.

## Security foundation

Core cryptography, credential verification, session boundaries, persistence, and App Lock architecture are documented in [SECURITY.md](SECURITY.md) and [docs/applock/README.md](docs/applock/README.md). Stage 10 hidden-state policy is in [docs/apphide/README.md](docs/apphide/README.md), and the launcher lifecycle/Home contract is in [docs/launcher/README.md](docs/launcher/README.md). The app stores a separate package-only hidden preference; temporary reveal exists only in the launcher ViewModel's memory and uses the existing `SessionManager`. The launcher does not log or transmit package names, persist reveal state, disable applications, or hide them from Android or other launchers. Overlay protection still requires the user-granted `SYSTEM_ALERT_WINDOW` capability and remains subject to Android background-execution and OEM limitations; it is not a platform-enforced kiosk. Stage 12 camouflage and recovery are not implemented.

## Project structure

The single `app` module keeps the project lightweight. Compose UI, navigation, and theme live under `ui`; security and credential contracts are in `domain`; JCA, Android Keystore, and DataStore implementations are in `data`; and `di` wires them through the application container. UI code depends on domain service boundaries rather than platform security classes.
