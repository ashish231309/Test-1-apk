#!/usr/bin/env python3
"""Static checks for Nivara's durable App Lock and permission boundaries."""

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

    print("PASS: App Lock permissions, service/activity declarations, secure presentation, auth routing, Stage 9 management, persistence, and docs.")


if __name__ == "__main__":
    main()
