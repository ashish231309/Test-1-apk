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
    }
    require(
        declared_permissions == allowed_permissions,
        f"unexpected manifest permission set: {sorted(declared_permissions)}",
    )

    manifest_text = manifest_path.read_text(encoding="utf-8")
    require("QUERY_ALL_PACKAGES" not in manifest_text, "broad package visibility must not be requested")
    require("SYSTEM_ALERT_WINDOW" not in manifest_text, "overlay permission belongs to Stage 8")
    require("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" not in manifest_text, "battery workaround is out of scope")
    require("POST_NOTIFICATIONS" not in manifest_text, "notification runtime permission is not required for FGS start")

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

    domain = ROOT / "app/src/main/java/com/ashishkumar/nivara/domain/applock"
    require(domain.is_dir(), "Android-free App Lock domain package is missing")
    domain_files = sorted(domain.glob("*.kt"))
    require(bool(domain_files), "App Lock domain contracts are missing")
    for path in domain_files:
        source = path.read_text(encoding="utf-8")
        require(not re.search(r"^\s*import\s+android\.", source, re.MULTILINE), f"Android import in domain: {path.relative_to(ROOT)}")

    production_root = ROOT / "app/src/main/java/com/ashishkumar/nivara"
    applock_sources = [
        path for path in production_root.rglob("*.kt")
        if "applock" in path.parts
    ]
    require(bool(applock_sources), "App Lock production implementation is missing")
    forbidden_state_names = re.compile(
        r"\b(?:appUnlocked|currentlyAuthenticated|lastSuccessfulUnlock|perAppSession|isUnlockedForPackage)\b",
        re.IGNORECASE,
    )
    forbidden_logging = re.compile(r"\b(?:Log\.[a-zA-Z]+|println\s*\()")
    forbidden_storage = re.compile(r"\b(?:DataStore|RoomDatabase|SQLiteDatabase)\b")
    forbidden_stage8_ui = ("Settings.canDrawOverlays", "SYSTEM_ALERT_WINDOW", "TYPE_APPLICATION_OVERLAY", "BiometricPrompt")
    for path in applock_sources:
        source = path.read_text(encoding="utf-8")
        require(not forbidden_state_names.search(source), f"duplicate unlock/session state in {path.relative_to(ROOT)}")
        require(not forbidden_logging.search(source), f"package/event logging is forbidden in {path.relative_to(ROOT)}")
        require(not forbidden_storage.search(source), f"use the documented minimal preferences store, not a database: {path.relative_to(ROOT)}")
        require(not any(token in source for token in forbidden_stage8_ui), f"Stage 8 overlay/authentication UI leaked into {path.relative_to(ROOT)}")

    container = (production_root / "di/NivaraContainer.kt").read_text(encoding="utf-8")
    for binding in ("protectedApplicationRepository", "appLockMonitor", "appLockMonitoringController"):
        require(binding in container, f"NivaraContainer is missing App Lock binding {binding}")

    protected_model = (domain / "ProtectedApplication.kt").read_text(encoding="utf-8")
    require("data class ProtectedApplication(val packageName: String)" in protected_model,
            "ProtectedApplication must use packageName as its only identity")
    persistence = (ROOT / "app/src/main/java/com/ashishkumar/nivara/data/applock/SharedPreferencesProtectedApplicationRepository.kt").read_text(encoding="utf-8")
    require("commit()" in persistence and "ProtectedApplicationsSnapshot.Unavailable" in persistence,
            "protected-app writes must be atomic and malformed state must remain unavailable")

    docs = (ROOT / "docs/applock/README.md").read_text(encoding="utf-8").lower()
    for required in ("foreground service", "specialuse", "usage access", "not_granted", "unavailable", "quick lock", "overlay", "battery", "device/emulator"):
        require(required in docs, f"App Lock documentation must cover {required!r}")

    print("PASS: App Lock permissions, narrow package visibility, service declaration, domain boundary, persistence, and documentation.")


if __name__ == "__main__":
    main()
