# Nivara

Nivara is a locally operated Android privacy workspace. It combines a single primary credential, optional biometric verification, session-bound access, App Lock for selected apps, a privacy-oriented launcher, and an encrypted vault backed by a folder the user selects through Android's Storage Access Framework (SAF).

## What it does

- **Credential and session security:** choose one PIN, password, or pattern. Android biometric authentication is optional and secondary to that credential. Sensitive actions use one process-memory session with a five-minute absolute timeout and Quick Lock; ordinary use does not refresh the timeout.
- **App Lock:** discover launchable apps, choose protected apps, and use Android Usage Access plus the user-granted overlay capability to detect and present authentication. Protection depends on Android background execution and device-maker behavior; it is not a platform-enforced kiosk.
- **Nivara launcher:** optionally choose Nivara as Android Home through the normal Home settings. Its drawer uses Android's launchable-app catalogue and Nivara's saved hidden-app preferences. Temporary reveal is explicit, authenticated, and session-bound.
- **External encrypted vault:** explicitly select a SAF folder, then import individual documents. Each file import stores an encrypted object using bounded-memory AES-GCM streaming. The authenticated index stores item metadata and wrapped per-item keys. Albums hold stable item references; Trash is reversible metadata state and does not remove or rewrite encrypted content.
- **Optional recovery:** configure a random recovery code while the existing vault key is available. The code wraps that same vault key; it does not restore credentials, biometrics, Android Keystore state, a session, or SAF access. After reinstall, explicitly select the existing vault folder, recover the key, and authenticate normally with a newly enrolled local credential.
- **Camouflage identity:** the app-drawer/Home presentation may use the neutral label “Home.” Nivara remains available through its ordinary recovery entry in the app drawer when another launcher is selected. This is not concealment from Android and does not change the app's package identity.

## Important limitations

- Hiding an app only omits it from Nivara's launcher. Hidden applications remain installed and functional outside Nivara and may still be visible or launchable through Android Settings, package-management surfaces, or other launchers.
- App Lock relies on user-granted Usage Access and overlay access. OEM behavior, Android lifecycle limits, and user changes to those capabilities can affect monitoring and presentation.
- Vault access requires the exact SAF folder and a working provider grant. Nivara does not scan paths, select a replacement folder, silently adopt or repair damaged data, or request broad storage access.
- Keep the recovery code private and separate from the device. If the code is lost and the original installation's Keystore key is unavailable, the vault key cannot be recovered. Recovery does not recreate the local credential or authenticate a user.
- The current viewer supports bounded previews for images and strict UTF-8 plain text. Video, audio, PDF, and other unsupported types can be classified but are not opened by a viewer. There is no plaintext file cache or automatic media staging.
- Trash supports restore only. There is no permanent deletion, secure erase, automatic expiry, cloud backup, account, or synchronization.
- Device/OEM-specific launcher, overlay, biometric, SAF, TalkBack, and media-provider behavior requires testing on the target device. Instrumented test compilation is not device runtime verification.

## Build and verify

Requirements: JDK 17 and Android SDK Platform 36 (API 36). Android Studio can configure the SDK location; no developer-specific path is committed. From the repository root:

```sh
./gradlew testDebugUnitTest assembleDebug assembleAndroidTest assembleRelease lint
python3 tools/verify_nivara.py
python3 -m unittest discover -s tools -p 'test_*.py'
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. The release build is generated under `app/build/outputs/apk/release/`; unless a private release-signing configuration is supplied outside the repository, Gradle produces an **unsigned** release APK. Do not distribute an unsigned APK as an installable release. Signing credentials and keystores must not be committed.

To execute Android tests, connect an API 28+ device/emulator and run:

```sh
./gradlew connectedDebugAndroidTest
```

Continuous integration compiles the Android test source set, builds debug and release variants, runs JVM tests, architecture/negative checks, and lint. Device-dependent tests are not executed by ordinary CI.

## Architecture and security

The app is a single Android module. Presentation consumes domain contracts and repositories; Android framework, Keystore, SAF, DataStore, Usage Access, and overlay integrations remain in platform/data boundaries. `SessionManager` is the only session authority. The vault uses one random content key, Android Keystore wrapping for the local installation, authenticated versioned metadata/index/organization records, and per-item wrapped keys for encrypted streamed objects. Recovery adds an optional authenticated wrapper for the same content key without changing the existing vault content format.

See [SECURITY.md](SECURITY.md) for the security model and [the vault guide](docs/vault/README.md) for SAF, encryption, recovery, reconnect, and data-integrity limitations. Additional App Lock, hidden-app, launcher, and camouflage details are in [docs/applock](docs/applock/README.md), [docs/apphide](docs/apphide/README.md), [docs/launcher](docs/launcher/README.md), and [docs/camouflage](docs/camouflage/README.md).
