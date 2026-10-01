# Nivara launcher — Stage 11

## Home activity and selection

`LauncherActivity` is the one Android Home-role activity. Its Home filter remains `ACTION_MAIN` with `CATEGORY_HOME` and `CATEGORY_DEFAULT`; Android may invoke it as Home. The existing `MainActivity` also has a separate ordinary `ACTION_MAIN` + `CATEGORY_LAUNCHER` entry, with no Home category, so a user can open Nivara's existing settings/authentication flow from the app drawer for recovery whether Nivara is selected as Home or not. Both components use the fixed presentation label “Home”; neither component is aliased or disabled. Nivara does not change the user's default Home application.

To select Nivara, use the device's normal **Settings → Apps → Default apps → Home app** screen (exact wording varies by Android and OEM). Leaving Nivara with Home, Back, or Recents remains under the platform's control.

## Home, settings, discovery, and drawer

The home surface is presented as **Home** and offers **Open app drawer**, **Nivara settings**, and **Show hidden apps for this session** controls. The settings button opens the existing Nivara credential and management navigation through an explicit in-app intent. If Nivara is not selected as Home, the ordinary app-drawer entry labelled **Home** launches that existing `MainActivity` as a recovery route. See [camouflage and recovery](../camouflage/README.md) for the component and security boundaries. The home and drawer use the existing Material 3 theme and `SecureScreenEffect()`.

`LauncherViewModel` consumes the shared `ApplicationRepository`, `HiddenApplicationRepository`, and `SessionManager`. It does not scan packages or access persistence. The application catalogue preserves Stage 6 launchable-activity discovery and uses the existing deterministic `InstalledApplicationOrdering`. Search is deliberately omitted from this first drawer version; if added later it should reuse `InstalledApplicationSearch`.

Visible rows display the installed app label and the shared `AndroidApplicationIconProvider` icon (or a neutral fallback), and launch the exact discovered package through `getLaunchIntentForPackage`. Package names are neither row labels nor user-entered launch targets. Missing apps and launch failures produce a generic message and a refresh rather than a crash.

## Hidden filtering and fail-closed behavior

The normal drawer filters the latest catalogue using `HiddenApplicationRepository`. Hidden applications remain installed and functional outside Nivara. When hidden state is `Unreadable` or `Unavailable`, Nivara displays an explicit safe state and no application entries. It never treats unknown hidden state as an empty set or shows the unfiltered catalogue. Discovery failure and an actually empty catalogue are also distinct states.

The launcher refreshes from the repositories on activity resume, when the drawer opens, and on explicit retry. It does not poll. Its visible list is an ephemeral rendering snapshot only; the shared repository remains authoritative.

## Temporary reveal and session lifecycle

The visible Home control reveals hidden launchable apps only after the existing `SessionManager.currentState()` confirms an authenticated session. If none exists, Nivara opens its normal settings/credential flow; successful existing credential verification establishes the usual Nivara session. The launcher re-reads both repositories and validates the session again immediately before reveal.

Reveal is only an in-memory reference to the exact authenticated `SessionManager` state in the ViewModel. It is not persisted and does not alter `HiddenApplicationRepository`. It clears when the SessionManager becomes unauthenticated, on Quick Lock (`SessionManager.lockNow()`), and on process recreation. A refresh while the same session remains valid preserves an already explicit reveal but does not extend the session. Rotations do not themselves reveal anything; a fresh process starts unauthenticated with hidden apps omitted.

App Lock remains independent. A protected+hidden app is omitted normally, may appear during authenticated reveal, and is then launched into the unchanged Stage 7/8 App Lock flow. The launcher does not unprotect an app, create an App Lock unlock marker, or modify protected state.

## Android compatibility and permissions

Nivara remains compatible with Android 9 / API 28 and later; the launcher does not raise the minimum SDK.

The Home intent filter requires no new runtime permission. The manifest retains the existing narrow launcher-activity visibility query and existing Stage 8 overlay permission; no `QUERY_ALL_PACKAGES`, accessibility, device-admin, or package-manager disabling mechanism is used. This launcher hides apps only from Nivara's own normal drawer; Android Settings, other launchers, package tools and privileged software remain outside its control.

Search and release-level launcher compatibility work remain out of scope. Stage 12's fixed identity and recovery route are documented separately; identity is presentation only and does not modify Home filtering. The Home chooser, Home resolution on real devices, external launches, icon rendering, lifecycle, session expiry and reveal behavior have not been runtime-verified without an Android device/emulator.
