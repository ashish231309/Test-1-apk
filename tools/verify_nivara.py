#!/usr/bin/env python3
"""Static checks for Nivara's App Lock, hidden-app, launcher, and permission boundaries."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def source(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as error:
        fail(f"required source is missing or unreadable ({path.relative_to(ROOT)}): {error}")


def verify_hidden_architecture(root: Path) -> None:
    """Stage 10 checks, split out so negative fixtures can exercise each boundary."""
    package = "app/src/main/java/com/ashishkumar/nivara"
    hidden_domain = root / package / "domain/apphide"
    domain_files = sorted(hidden_domain.glob("*.kt"))
    require(bool(domain_files), "hidden-app Android-free domain package is missing")
    for path in domain_files:
        text = path.read_text(encoding="utf-8")
        require(not re.search(r"^\s*import\s+android\.", text, re.MULTILINE),
                f"Android import in hidden-app domain: {path.name}")
    model = "\n".join(path.read_text(encoding="utf-8") for path in domain_files)
    require(re.search(r"data class HiddenApplication\(val packageName: String\)", model) is not None,
            "HiddenApplication must use packageName as its sole identity")

    data_dir = root / package / "data/apphide"
    data_sources = sorted(data_dir.glob("*.kt"))
    implementations = []
    data_text = []
    for path in data_sources:
        text = path.read_text(encoding="utf-8")
        data_text.append(text)
        if re.search(r"\bclass\s+\w+[\s\S]*?\)\s*:\s*HiddenApplicationRepository\b", text):
            implementations.append(path)
    require(len(implementations) == 1,
            f"exactly one data implementation may own hidden state (found {len(implementations)})")
    all_data = "\n".join(data_text)
    require("AtomicFile" in all_data and "HiddenApplicationsCodec" in all_data,
            "hidden persistence must use the atomic adapter and versioned codec")
    require("HiddenApplicationsSnapshot.Unreadable" in all_data and "HiddenApplicationsSnapshot.Unavailable" in all_data,
            "hidden persistence must preserve explicit unreadable and unavailable outcomes")

    view_model_path = root / package / "ui/apphide/HiddenApplicationManagementViewModel.kt"
    screen_path = root / package / "ui/apphide/HiddenApplicationManagementScreen.kt"
    view_model = view_model_path.read_text(encoding="utf-8")
    screen = screen_path.read_text(encoding="utf-8")
    forbidden_ui_storage = re.compile(
        r"\b(?:AtomicFile|FileInputStream|FileOutputStream|File|HiddenApplicationsCodec|"
        r"HiddenApplicationFileStore|AndroidAtomicHiddenApplicationStore)\b"
    )
    forbidden_package_hiding = re.compile(
        r"\b(?:setApplicationEnabledSetting|setComponentEnabledSetting|"
        r"COMPONENT_ENABLED_STATE_DISABLED)\b"
    )
    forbidden_new_auth = re.compile(r"\b(?:PrimaryCredentialService|BiometricAuthenticator|BiometricPrompt)\b")
    for path, text in ((view_model_path, view_model), (screen_path, screen)):
        require(not forbidden_ui_storage.search(text), f"UI layer accesses hidden storage directly: {path.name}")
        require(not forbidden_package_hiding.search(text),
                f"package-manager disabling is forbidden hiding logic: {path.name}")
        require(not forbidden_new_auth.search(text),
                f"hidden management must reuse SessionManager rather than add authentication: {path.name}")
        require("Log." not in text and "println(" not in text,
                f"hidden-app identifiers must not be logged: {path.name}")
    require("SecureScreenEffect()" in screen, "hidden management must use the shared SecureScreenEffect")
    require("InstalledApplicationSearch.matches" in view_model and
            "InstalledApplicationOrdering.deterministic" in view_model and
            "InstalledApplicationOrdering.reverseAlphabetical" in view_model,
            "hidden management must reuse the established application search and ordering")
    require("sessionManager.currentState()" in view_model,
            "hidden mutations must re-check the existing SessionManager")
    require(not re.search(r"protectedApplicationRepository\.(?:setProtected|protect|unprotect)\s*\(", view_model),
            "hidden mutations must not change App Lock protected state")
    require(not re.search(r"\b(?:HiddenAppSession|hiddenUnlocked|hiddenAuthCounter|hiddenTimeout)\b", view_model),
            "a hidden-app-specific authentication/session mechanism is forbidden")

    nav = (root / package / "ui/NivaraApp.kt").read_text(encoding="utf-8")
    destinations = (root / package / "ui/navigation/AppDestination.kt").read_text(encoding="utf-8")
    container = (root / package / "di/NivaraContainer.kt").read_text(encoding="utf-8")
    require("AppDestination.HiddenApplicationManagement.route" in nav and
            "HiddenApplicationManagementScreen(" in nav,
            "hidden management destination must be registered in the existing navigation graph")
    require("HiddenApplicationManagement" in destinations,
            "hidden management navigation destination is missing")
    require(len(re.findall(r"\boverride val hiddenApplicationRepository\b", container)) == 1,
            "NivaraContainer must bind exactly one shared hidden repository")

    production = root / package
    for path in production.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        require(not forbidden_package_hiding.search(text),
                f"package-manager component disabling is forbidden: {path.relative_to(root)}")
        require("DevicePolicyManager" not in text and "DeviceAdminReceiver" not in text,
                f"device-admin/device-owner APIs are forbidden: {path.relative_to(root)}")
    applock_domain = production / "domain/applock"
    for path in applock_domain.glob("*.kt"):
        require("HiddenApplication" not in path.read_text(encoding="utf-8"),
                f"Stage 7/8 must not depend on hidden state: {path.name}")
    stage9_view_model = production / "ui/applock/AppLockManagementViewModel.kt"
    require("HiddenApplication" not in stage9_view_model.read_text(encoding="utf-8"),
            "Stage 9 App Lock management must remain independent of hidden state")


def verify_launcher_manifest(manifest: ET.Element) -> None:
    application = manifest.find("application")
    require(application is not None, "manifest application declaration is missing")
    activities = application.findall("activity")
    home_filters: list[tuple[ET.Element, ET.Element, set[str], set[str]]] = []
    for activity in activities:
        for intent_filter in activity.findall("intent-filter"):
            actions = {item.get(ANDROID_NS + "name", "") for item in intent_filter.findall("action")}
            categories = {item.get(ANDROID_NS + "name", "") for item in intent_filter.findall("category")}
            if "android.intent.category.HOME" in categories:
                home_filters.append((activity, intent_filter, actions, categories))

    require(len(home_filters) == 1, "exactly one activity must declare the Android Home intent")
    home_activity, home_filter, actions, categories = home_filters[0]
    require(home_activity.get(ANDROID_NS + "name", "").endswith("LauncherActivity"),
            "the sole Home intent must belong to LauncherActivity")
    require(home_activity.get(ANDROID_NS + "exported") == "true",
            "the Home activity must be explicitly exported for Android to invoke it")
    require(actions == {"android.intent.action.MAIN"},
            "the Home activity must expose only ACTION_MAIN")
    require(categories == {"android.intent.category.HOME", "android.intent.category.DEFAULT"},
            "the Home filter must contain exactly CATEGORY_HOME and CATEGORY_DEFAULT")
    require(len(home_activity.findall("intent-filter")) == 1,
            "the exported Home activity must not expose unrelated intent filters")

    components = [
        component
        for tag in ("activity", "activity-alias", "service", "receiver", "provider")
        for component in application.findall(tag)
    ]
    main_entries = [
        component for component in components
        if component.tag == "activity" and component.get(ANDROID_NS + "name", "").endswith("MainActivity")
    ]
    require(len(main_entries) == 1, "the existing MainActivity must provide one ordinary recovery app entry")
    main_activity = main_entries[0]
    require(main_activity.get(ANDROID_NS + "exported") == "true",
            "MainActivity must be externally launchable through the system app drawer")
    main_filters = main_activity.findall("intent-filter")
    require(len(main_filters) == 1, "MainActivity must expose only its ordinary app-drawer entry")
    main_actions = {item.get(ANDROID_NS + "name", "") for item in main_filters[0].findall("action")}
    main_categories = {item.get(ANDROID_NS + "name", "") for item in main_filters[0].findall("category")}
    require(main_actions == {"android.intent.action.MAIN"} and
            main_categories == {"android.intent.category.LAUNCHER"},
            "MainActivity recovery must be a MAIN/LAUNCHER entry, not another Home or external route")
    explicitly_exported = [component for component in components if component.get(ANDROID_NS + "exported") == "true"]
    require(set(explicitly_exported) == {home_activity, main_activity} and len(explicitly_exported) == 2,
            "only LauncherActivity (Home role) and MainActivity (app-drawer recovery) may be exported")
    require(not application.findall("activity-alias"),
            "activity aliases are not used for identity camouflage or recovery")
    for component in components:
        if component not in (home_activity, main_activity):
            require(component.get(ANDROID_NS + "exported") == "false",
                    f"unrelated {component.tag} components must remain explicitly non-exported")
            require(not component.findall("intent-filter"),
                    f"unrelated {component.tag} components must not add launcher or external intent filters")


def verify_launcher_architecture(root: Path) -> None:
    package = root / "app/src/main/java/com/ashishkumar/nivara"
    view_model_path = package / "ui/launcher/LauncherViewModel.kt"
    screen_path = package / "ui/launcher/LauncherScreen.kt"
    activity_path = package / "LauncherActivity.kt"
    view_model = view_model_path.read_text(encoding="utf-8")
    screen = screen_path.read_text(encoding="utf-8")
    activity = activity_path.read_text(encoding="utf-8")
    require("ApplicationRepository" in view_model and "HiddenApplicationRepository" in view_model,
            "launcher presentation must consume the existing discovery and hidden-state contracts")
    require("sessionManager.currentState()" in view_model and "sessionManager.lockNow()" in view_model,
            "temporary reveal and Quick Lock must use the existing SessionManager")
    require("sessionManager.sessionState.value === revealSession" in view_model,
            "temporary reveal must be bound to the exact live SessionManager session")
    require("hiddenApplicationRepository.getHiddenApplications()" in view_model,
            "launcher must refresh hidden state from HiddenApplicationRepository")
    require(not re.search(r"hiddenApplicationRepository\.(?:hide|unhide)\s*\(", view_model),
            "temporary reveal must never mutate persistent hidden preferences")
    require("InstalledApplicationOrdering.deterministic" in view_model,
            "launcher must reuse the existing application ordering")
    require("HiddenApplicationsSnapshot.Unreadable" in view_model and
            "HiddenApplicationsSnapshot.Unavailable" in view_model and
            "ApplicationDiscoveryResult.Unavailable" in view_model,
            "launcher must distinguish repository and discovery failure states")

    forbidden_storage = re.compile(
        r"\b(?:AtomicFile|AtomicFiles|FileInputStream|FileOutputStream|HiddenApplicationsCodec|"
        r"HiddenApplicationFileStore|AndroidAtomicHiddenApplicationStore|DataStore|SharedPreferences)\b"
    )
    forbidden_auth = re.compile(r"\b(?:PrimaryCredentialService|BiometricAuthenticator|BiometricPrompt)\b")
    forbidden_scanner = re.compile(
        r"\b(?:queryIntentActivities|queryIntentServices|queryBroadcastReceivers|getInstalledApplications|"
        r"getInstalledPackages|getPackagesHoldingPermissions|AccessibilityService)\b"
    )
    forbidden_reveal_cache = re.compile(r"\b(?:unhiddenApps|revealedAppsStore|persistentReveal|launcherRevealDataStore)\b")
    for path, text in ((view_model_path, view_model), (screen_path, screen), (activity_path, activity)):
        require(not forbidden_storage.search(text),
                f"launcher UI must not access persistence directly: {path.name}")
        require(not forbidden_auth.search(text),
                f"launcher must not implement a second authentication authority: {path.name}")
        require(not forbidden_scanner.search(text),
                f"launcher must not add a PackageManager scanner or accessibility discovery: {path.name}")
        require(not forbidden_reveal_cache.search(text),
                f"temporary reveal must not become a launcher-specific persistent list: {path.name}")
        require("Log." not in text and "println(" not in text,
                f"package identities must not be logged: {path.name}")
    require("SecureScreenEffect()" in screen,
            "launcher screen must use screenshot protection while temporary reveal is available")
    require("SessionState.Authenticated" in screen and "hiddenApplicationsRevealed" in screen,
            "launcher UI must immediately gate a revealed snapshot on the live SessionManager state")
    require("AndroidApplicationIconProvider" in screen,
            "launcher must reuse the shared application icon provider")
    require("getLaunchIntentForPackage(application.packageName)" in activity,
            "application launch must resolve the exact discovered package through PackageManager")
    require("Intent(this, MainActivity::class.java)" in activity,
            "launcher must provide a normal explicit route to Nivara settings/authentication")
    require("getStringExtra" not in activity and "putExtra(" not in activity,
            "launcher must not accept package identities or sensitive state from external Intent extras")
    require(not re.search(r"\b(?:rememberSaveable|onSaveInstanceState|SharedPreferences|DataStore)\b", screen + view_model + activity),
            "temporary hidden-app reveal must remain non-persistent")
    require(not re.search(r"ProtectedApplicationRepository|setProtected\s*\(|unprotect\s*\(", view_model + screen),
            "launcher presentation must not mutate App Lock protected state")

    domain_files = (package / "domain/apphide").glob("*.kt")
    for path in domain_files:
        text = path.read_text(encoding="utf-8")
        require(not re.search(r"^\s*import\s+android\.", text, re.MULTILINE),
                f"hidden-app domain must remain Android-free: {path.name}")


def verify_camouflage_manifest(manifest: ET.Element, root: Path) -> None:
    """Stage 12: fixed presentation identity and safe ordinary recovery entry."""
    application = manifest.find("application")
    require(application is not None, "manifest application declaration is missing")
    identity = "@string/camouflage_identity_label"
    icon = "@mipmap/camouflage_home"
    require(application.get(ANDROID_NS + "label") == identity,
            "application label must use the one fixed benign identity resource")
    require(application.get(ANDROID_NS + "icon") == icon and application.get(ANDROID_NS + "roundIcon") == icon,
            "application icons must use the fixed benign home icon")
    require(not application.findall("activity-alias"),
            "camouflage must not add activity aliases or component-state hiding")

    activities = {item.get(ANDROID_NS + "name", ""): item for item in application.findall("activity")}
    home = activities.get(".LauncherActivity")
    recovery = activities.get(".MainActivity")
    require(home is not None and recovery is not None,
            "existing LauncherActivity and MainActivity must provide Home and recovery")
    for component in (home, recovery):
        require(component.get(ANDROID_NS + "label") == identity and component.get(ANDROID_NS + "icon") == icon,
                "Home and recovery entries must share the selected fixed presentation identity")
    require(recovery.get(ANDROID_NS + "exported") == "true",
            "ordinary app-drawer recovery must be resolvable by Android")
    launcher_filters = recovery.findall("intent-filter")
    require(len(launcher_filters) == 1, "recovery must expose exactly one app-drawer filter")
    actions = {node.get(ANDROID_NS + "name", "") for node in launcher_filters[0].findall("action")}
    categories = {node.get(ANDROID_NS + "name", "") for node in launcher_filters[0].findall("category")}
    require(actions == {"android.intent.action.MAIN"} and
            categories == {"android.intent.category.LAUNCHER"},
            "recovery must be ordinary MAIN/LAUNCHER and must not claim CATEGORY_HOME")
    components = [component for tag in ("activity", "activity-alias", "service", "receiver", "provider")
                  for component in application.findall(tag)]
    exported = [component for component in components if component.get(ANDROID_NS + "exported") == "true"]
    require(set(exported) == {home, recovery} and len(exported) == 2,
            "only the existing Home activity and app-drawer recovery activity may be exported")
    for component in components:
        if component not in (home, recovery):
            require(component.get(ANDROID_NS + "exported") == "false" and
                    not component.findall("intent-filter"),
                    "unrelated components must stay non-exported without external filters")

    strings_path = root / "app/src/main/res/values/strings.xml"
    strings = ET.parse(strings_path).getroot()
    identity_string = next((item for item in strings.findall("string")
                            if item.get("name") == "camouflage_identity_label"), None)
    require(identity_string is not None and (identity_string.text or "").strip() == "Home",
            "the only supported identity must be the fixed Home label")
    adaptive_icon = root / "app/src/main/res/mipmap-anydpi-v26/camouflage_home.xml"
    require(adaptive_icon.is_file(), "fixed neutral adaptive launcher icon is missing")

    gradle = source(root / "app/build.gradle.kts")
    require('namespace = "com.ashishkumar.nivara"' in gradle and
            'applicationId = "com.ashishkumar.nivara"' in gradle,
            "camouflage must preserve the Android namespace and application ID")
    manifest_text = ET.tostring(manifest, encoding="unicode")
    require("QUERY_ALL_PACKAGES" not in manifest_text and "queries" in manifest_text,
            "camouflage must not broaden package visibility")
    query_children = manifest.findall("queries/*")
    queries = manifest.findall("queries/intent")
    require(len(query_children) == 1 and len(queries) == 1,
            "camouflage must preserve only the existing narrow launcher-visibility query")
    query_actions = {n.get(ANDROID_NS + "name", "") for n in queries[0].findall("action")}
    query_categories = {n.get(ANDROID_NS + "name", "") for n in queries[0].findall("category")}
    require(query_actions == {"android.intent.action.MAIN"} and
            query_categories == {"android.intent.category.LAUNCHER"},
            "package visibility must remain limited to MAIN/LAUNCHER")


def verify_camouflage_architecture(root: Path) -> None:
    package = root / "app/src/main/java/com/ashishkumar/nivara"
    main_activity = source(package / "MainActivity.kt")
    for required in ("container.primaryCredentialService", "container.biometricAuthenticator(this)",
                     "sessionManager = container.sessionManager", "NivaraApp("):
        require(required in main_activity,
                f"recovery must reuse existing MainActivity authentication/navigation wiring ({required})")
    require(not re.search(r"\b(?:CamouflageRepository|IdentityProfileRepository|RecoveryCredential|"
                          r"CamouflageSessionManager|recoveryAuthenticated|fakeAuthenticated)\b", main_activity),
            "recovery must not add a repository, credential, or parallel/persisted session")

    production_files = list(package.rglob("*.kt"))
    forbidden_new_architecture = re.compile(
        r"\b(?:CamouflageRepository|IdentityProfileRepository|IdentitySelectionViewModel|"
        r"RecoveryCredentialStore|CamouflagePreferences|IdentityProfileStore|HiddenVault)\b"
    )
    for path in production_files:
        text = source(path)
        require(not forbidden_new_architecture.search(text),
                f"camouflage must not add profile, auth, preference, or vault architecture: {path.relative_to(root)}")
        require("setApplicationEnabledSetting" not in text and "setComponentEnabledSetting" not in text,
                f"camouflage must not alter installed-app/component state: {path.relative_to(root)}")
    for forbidden_dir in ("data/camouflage", "domain/camouflage", "ui/camouflage"):
        require(not (package / forbidden_dir).exists(),
                f"no new camouflage subsystem is permitted: {forbidden_dir}")

    docs = source(root / "docs/camouflage/README.md").lower()
    for required in ("fixed benign identity", "no profile", "when another app is selected",
                     "after process recreation", "quick lock", "session expiry", "sessionmanager",
                     "primary-credential/biometric", "does not provide invisibility", "not a security boundary",
                     "android settings", "no new permission"):
        require(required in docs,
                f"camouflage recovery/security documentation must cover {required!r}")
    readme = source(root / "README.md").lower()
    for required in ("ordinary recovery entry", "not invisibility", "docs/camouflage/readme.md"):
        require(required in readme, f"README must document Stage 12 {required!r}")


def main() -> None:
    manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
    try:
        manifest = ET.parse(manifest_path).getroot()
    except (ET.ParseError, OSError) as error:
        fail(f"manifest cannot be parsed: {error}")

    declared_permissions = {
        element.get(ANDROID_NS + "name", "").removeprefix("android.permission.")
        for element in manifest.findall("uses-permission")
    }
    allowed_permissions = {
        "USE_BIOMETRIC",
        "PACKAGE_USAGE_STATS",
        "FOREGROUND_SERVICE",
        "FOREGROUND_SERVICE_SPECIAL_USE",
        "SYSTEM_ALERT_WINDOW",
    }
    require(
        declared_permissions == allowed_permissions,
        f"unexpected manifest permission set: {sorted(declared_permissions)}",
    )

    manifest_text = source(manifest_path)
    require("QUERY_ALL_PACKAGES" not in manifest_text, "broad package visibility must not be requested")
    require(manifest_text.count("android.permission.SYSTEM_ALERT_WINDOW") == 1,
            "Stage 8 must add only one SYSTEM_ALERT_WINDOW declaration")
    require("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" not in manifest_text, "battery workaround is out of scope")
    require("POST_NOTIFICATIONS" not in manifest_text, "notification runtime permission is not required for FGS start")
    require("AccessibilityService" not in manifest_text and "BIND_ACCESSIBILITY_SERVICE" not in manifest_text,
            "accessibility APIs/services are out of scope")
    require("BIND_DEVICE_ADMIN" not in manifest_text and "android.app.admin" not in manifest_text,
            "device-admin/device-owner APIs are out of scope")
    verify_launcher_manifest(manifest)
    verify_camouflage_manifest(manifest, ROOT)
    verify_camouflage_architecture(ROOT)

    query_intents = manifest.findall("queries/intent")
    require(
        any(
            intent.find("action").get(ANDROID_NS + "name") == "android.intent.action.MAIN"
            and intent.find("category").get(ANDROID_NS + "name") == "android.intent.category.LAUNCHER"
            for intent in query_intents
            if intent.find("action") is not None and intent.find("category") is not None
        ),
        "launcher discovery must retain its narrow MAIN/LAUNCHER visibility query",
    )

    service = next(
        (item for item in manifest.findall("application/service")
         if item.get(ANDROID_NS + "name", "").endswith("AppLockDetectionService")),
        None,
    )
    require(service is not None, "the app-lock monitoring service must be declared")
    require(service.get(ANDROID_NS + "exported") == "false", "the monitoring service must be non-exported")
    require(service.get(ANDROID_NS + "foregroundServiceType") == "specialUse", "the service must declare its reviewed specialUse type")
    subtype = next(
        (prop.get(ANDROID_NS + "value", "") for prop in service.findall("property")
         if prop.get(ANDROID_NS + "name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"),
        "",
    )
    require("protected applications" in subtype.lower(), "specialUse subtype needs the concrete App Lock use case")

    activities = manifest.findall("application/activity")
    biometric_activity = next(
        (item for item in activities
         if item.get(ANDROID_NS + "name", "").endswith("AppLockBiometricActivity")),
        None,
    )
    require(biometric_activity is not None, "the biometric prompt host must be declared")
    require(biometric_activity.get(ANDROID_NS + "exported") == "false",
            "the biometric prompt host must be non-exported")
    require(biometric_activity.get(ANDROID_NS + "excludeFromRecents") == "true",
            "the biometric prompt host must not appear as a recent task")

    domain = ROOT / "app/src/main/java/com/ashishkumar/nivara/domain/applock"
    require(domain.is_dir(), "Android-free App Lock domain package is missing")
    domain_files = sorted(domain.glob("*.kt"))
    require(bool(domain_files), "App Lock domain contracts are missing")
    for path in domain_files:
        text = source(path)
        require(not re.search(r"^\s*import\s+android\.", text, re.MULTILINE),
                f"Android import in domain: {path.relative_to(ROOT)}")

    production_root = ROOT / "app/src/main/java/com/ashishkumar/nivara"
    applock_sources = [path for path in production_root.rglob("*.kt") if "applock" in path.parts]
    require(bool(applock_sources), "App Lock production implementation is missing")
    forbidden_state_names = re.compile(
        r"\b(?:appUnlocked|currentlyAuthenticated|lastSuccessfulUnlock|perAppSession|isUnlockedForPackage|"
        r"unlockedPackages|packageAuthCounter|perPackageSession)\b", re.IGNORECASE,
    )
    forbidden_logging = re.compile(r"\b(?:Log\.[a-zA-Z]+|println\s*\()")
    forbidden_storage = re.compile(r"\b(?:DataStore|RoomDatabase|SQLiteDatabase)\b")
    stage8_implementation_names = {
        "AndroidAppLockOverlayHost.kt",
        "AndroidAppLockPresentationController.kt",
        "AndroidOverlayCapabilityRepository.kt",
        "AppLockBiometricActivity.kt",
    }
    for path in applock_sources:
        text = source(path)
        relative = path.relative_to(ROOT)
        require(not forbidden_state_names.search(text), f"duplicate unlock/session state in {relative}")
        require(not forbidden_logging.search(text), f"package/event logging is forbidden in {relative}")
        require(not forbidden_storage.search(text), f"unexpected persistent/database state in {relative}")
        if path.name not in stage8_implementation_names:
            require(
                not any(token in text for token in ("Settings.canDrawOverlays", "TYPE_APPLICATION_OVERLAY", "BiometricPrompt")),
                f"overlay/authentication platform UI leaked into the detection boundary: {relative}",
            )

    overlay_host = source(production_root / "data/applock/AndroidAppLockOverlayHost.kt")
    for required in ("TYPE_APPLICATION_OVERLAY", "FLAG_SECURE", "removeViewImmediate", "clearSensitiveInput"):
        require(required in overlay_host, f"secure overlay host is missing {required}")
    require("FLAG_NOT_TOUCHABLE" not in overlay_host, "the protected request must not pass touches through")

    presentation = source(production_root / "data/applock/AndroidAppLockPresentationController.kt")
    require("AppLockAuthenticationRouter" in presentation and "requestStillTargetsProtectedPackage" in presentation,
            "presentation must validate request identity and route authentication through the shared router")
    require("Intent(applicationContext, AppLockBiometricActivity::class.java)" in presentation,
            "biometric host launch must remain explicit and Nivara-owned")
    require(not re.search(r"putExtra\s*\(", presentation), "request identity or sensitive data must not be placed in Intent extras")

    router = source(domain / "AppLockAuthenticationRouter.kt")
    for required in ("authenticatePrimary", "authenticateBiometric", "sessionManager.authenticatePrimary",
                     "sessionManager.authenticateBiometric", "requestStillValid", "credential.fill"):
        require(required in router, f"authentication routing is missing {required}")
    container = source(production_root / "di/NivaraContainer.kt")
    for binding in ("protectedApplicationRepository", "appLockMonitor", "appLockMonitoringController",
                    "appLockPresentationController", "sessionManager", "applicationIconProvider"):
        require(binding in container, f"NivaraContainer is missing App Lock binding {binding}")

    management_view_model = source(production_root / "ui/applock/AppLockManagementViewModel.kt")
    for required in ("InstalledApplicationSearch.matches", "InstalledApplicationOrdering.deterministic",
                     "InstalledApplicationOrdering.reverseAlphabetical", "protectedApplicationRepository.protect",
                     "protectedApplicationRepository.unprotect", "sessionManager.currentState()",
                     "protectedApplicationRepository.getProtectedApplications()", "appLockMonitor.state"):
        require(required in management_view_model, f"Stage 9 management is missing {required}")
    require("AppLockDetectionState.Stopped" in management_view_model and "requestMonitoringStart()" in management_view_model,
            "Stage 9 must start stopped monitoring after saving a non-empty protected set")
    management_screen = source(production_root / "ui/applock/AppLockManagementScreen.kt")
    for required in ("AppLockApplicationSection.ALL", "AppLockApplicationSection.PROTECTED",
                     "Lifecycle.Event.ON_RESUME", "SessionState.Authenticated", "SecureScreenEffect()"):
        require(required in management_screen, f"Stage 9 screen is missing {required}")
    navigation = source(production_root / "ui/NivaraApp.kt")
    require("AppDestination.AppLockManagement.route" in navigation and "onReturnHomeForAuthentication" in navigation,
            "Stage 9 management must be reachable and route credential verification to Home")
    icon_provider = source(production_root / "data/app/AndroidApplicationIconProvider.kt")
    require("PackageManager" in icon_provider and "NameNotFoundException" in icon_provider,
            "application icons must stay in PackageManager-backed data code with missing-app fallback")

    protected_model = source(domain / "ProtectedApplication.kt")
    require("data class ProtectedApplication(val packageName: String)" in protected_model,
            "ProtectedApplication must use packageName as its only identity")
    persistence = source(production_root / "data/applock/SharedPreferencesProtectedApplicationRepository.kt")
    require("commit()" in persistence and "ProtectedApplicationsSnapshot.Unavailable" in persistence,
            "protected-app writes must be atomic and malformed state must remain unavailable")
    permission_contracts = source(domain / "OverlayCapabilityContracts.kt")
    for value in ("GRANTED", "NOT_GRANTED", "UNAVAILABLE", "OPENED", "FAILED"):
        require(value in permission_contracts, f"overlay capability contract is missing explicit {value} state")

    docs = source(ROOT / "docs/applock/README.md").lower()
    for required in ("foreground service", "specialuse", "usage access", "not_granted", "unavailable",
                     "quick lock", "overlay", "battery", "device/emulator", "background", "flag_secure"):
        require(required in docs, f"App Lock documentation must cover {required!r}")

    verify_hidden_architecture(ROOT)
    verify_launcher_architecture(ROOT)
    hidden_docs = source(ROOT / "docs/apphide/README.md").lower()
    for required in ("hiddenapplicationrepository", "unreadable", "atomicfile", "sessionmanager", "stage 11", "stock launcher", "cryptographically secret"):
        require(required in hidden_docs, f"hidden-app documentation must cover {required!r}")
    launcher_docs = source(ROOT / "docs/launcher/README.md").lower()
    for required in ("category_home", "hiddenapplicationrepository", "fail-closed", "temporary", "quick lock", "settings", "api 28"):
        require(required in launcher_docs, f"launcher documentation must cover {required!r}")
    readme = source(ROOT / "README.md").lower()
    for required in ("custom launcher", "app drawer", "normal home settings", "stage 12", "remain installed and functional"):
        require(required in readme, f"README must document {required!r}")
    print("PASS: App Lock, hidden-app, launcher and camouflage/recovery contracts, persistence, auth boundaries, and permissions.")


if __name__ == "__main__":
    main()
