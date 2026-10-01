# App identity camouflage and recovery

## One fixed identity

Stage 12 presents one fixed benign identity: **Home**, with a neutral home icon. There is no identity picker or per-profile preference; there is no profile persistence. This avoids persisting a profile choice and keeps identity presentation separate from app data, authentication, hidden-app settings, and App Lock. The Home screen uses the same benign heading; opening its **Nivara settings** button intentionally enters the existing Nivara experience.

This is a presentation choice only. The Android namespace and application ID remain `com.ashishkumar.nivara`; the package is still installed, addressable, and visible to Android and privileged software. Camouflage does not provide invisibility, encryption, or anti-forensics; it is not a security boundary. Android Settings, launchers, package tools, system administrators, malware with sufficient access, and anyone inspecting the APK can identify or manage the package.

## Components and app-drawer recovery

There is exactly one Android Home component: the existing exported `LauncherActivity`, with its Stage 11 `ACTION_MAIN` + `CATEGORY_HOME` + `CATEGORY_DEFAULT` filter unchanged. Nivara never selects itself as the default Home app and does not disable or alias this activity.

The existing `MainActivity` has an ordinary `ACTION_MAIN` + `CATEGORY_LAUNCHER` entry labelled **Home**. This is the recovery entry, not another Home-role activity: it has no `CATEGORY_HOME`. It opens Nivara's existing navigation and authentication flow. The two exported activities serve separate platform roles; no activity aliases or component state are used. Their icons and labels use the same fixed identity. The existing narrowly scoped `MAIN`/`LAUNCHER` package-visibility query is unchanged; no new permission or broader package visibility was added.

This app-drawer route works when another app is selected as Home, and also when Nivara itself is selected. If Nivara is already the selected Home app, its Home screen additionally offers **Nivara settings**, an explicit route to the same existing `MainActivity`. If Home selection changes, open the ordinary **Home** app entry from the device's app drawer to return. Android and OEMs control launcher presentation and may display installed apps differently, so the exact location and naming of the app drawer can vary.

Recovery remains an ordinary launcher action; it uses no secret gesture, code, PIN, alternate credential, or persistent authenticated recovery state. Hidden-app preferences affect only Nivara's launcher drawer and do not hide or disable this recovery component. The recovery entry remains available after process recreation, Quick Lock, and session expiry. Opening Nivara's existing security/settings routes follows their ordinary `SessionManager` and primary-credential/biometric rules; this stage does not create an authentication bypass. Authentication state is not persisted by camouflage.

## Independence and limitations

Camouflage does not read or modify `HiddenApplicationRepository` or `ProtectedApplicationRepository`, does not change installed-app/component state, and does not alter Stage 11 hidden filtering or Stage 7/8 App Lock behavior. App Lock continues to use its existing detection, protection, overlay, authentication, and permission paths independently.

The fixed identity is not an Android account/profile and is not a vault. There is no profile persistence, configuration UI, network call, telemetry, package-name history, or new storage. Icon/label display, launcher-specific app-drawer behavior, default-Home resolution, and cross-launcher recovery should be checked on real devices; static checks alone cannot establish OEM behavior. No runtime Android verification is claimed unless performed on an Android device or emulator.
