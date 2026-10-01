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


def verify_vault_manifest(manifest: ET.Element) -> None:
    manifest_text = ET.tostring(manifest, encoding="unicode")
    forbidden = (
        "MANAGE_EXTERNAL_STORAGE",
        "READ_EXTERNAL_STORAGE",
        "WRITE_EXTERNAL_STORAGE",
        "QUERY_ALL_PACKAGES",
        "BIND_ACCESSIBILITY_SERVICE",
        "BIND_DEVICE_ADMIN",
        "POST_NOTIFICATIONS",
    )
    for token in forbidden:
        require(token not in manifest_text, f"vault must not add broad/unnecessary Android permission or visibility: {token}")
    application = manifest.find("application")
    require(application is not None, "manifest application declaration is missing")
    require(not any("Vault" in component.get(ANDROID_NS + "name", "")
                    for tag in ("activity", "activity-alias", "service", "receiver", "provider")
                    for component in application.findall(tag)),
            "vault must use the existing navigation and system document picker, not add components")
    query_children = manifest.findall("queries/*")
    query_intents = manifest.findall("queries/intent")
    require(len(query_children) == 1 and len(query_intents) == 1,
            "vault must retain only the existing narrow package-visibility query")
    actions = {node.get(ANDROID_NS + "name", "") for node in query_intents[0].findall("action")}
    categories = {node.get(ANDROID_NS + "name", "") for node in query_intents[0].findall("category")}
    require(actions == {"android.intent.action.MAIN"} and
            categories == {"android.intent.category.LAUNCHER"},
            "vault must not broaden package visibility beyond launcher discovery")


def verify_vault_architecture(root: Path, manifest: ET.Element) -> None:
    package = root / "app/src/main/java/com/ashishkumar/nivara"
    domain_dir = package / "domain/vault"
    data_dir = package / "data/vault"
    ui_dir = package / "ui/vault"
    domain_files = sorted(domain_dir.glob("*.kt")) + sorted((domain_dir / "content").glob("*.kt"))
    data_files = sorted(data_dir.glob("*.kt"))
    ui_files = sorted(ui_dir.glob("*.kt"))
    require(bool(domain_files), "Android-free vault domain contracts are missing")
    require(bool(data_files), "external SAF vault data implementation is missing")
    require(bool(ui_files), "vault UI foundation is missing")

    domain_text = "\n".join(source(path) for path in domain_files)
    for required in ("data object RootNotSelected : VaultStatus", "data object NotInitialized : VaultStatus",
                     "data class Ready(val vaultId: VaultId) : VaultStatus",
                     "data class Unavailable(val reason: VaultUnavailableReason) : VaultStatus",
                     "data object AccessDenied : VaultStatus", "data object CorruptMetadata : VaultStatus",
                     "data class UnsupportedVersion(val component: VaultFormatComponent, val version: Int?) : VaultStatus",
                     "data object InvalidStructure : VaultStatus",
                     "data class InitializationFailed(val reason: VaultInitializationFailure) : VaultStatus"):
        require(required in domain_text, f"vault domain must expose distinct states ({required})")
    for required in ("data class Initialized(val vaultId: VaultId) : VaultInitializationResult",
                     "data object AlreadyInitialized : VaultInitializationResult",
                     "data object RootNotSelected : VaultInitializationResult",
                     "data class Unavailable(val reason: VaultUnavailableReason) : VaultInitializationResult",
                     "data object AccessDenied : VaultInitializationResult",
                     "data object CorruptMetadata : VaultInitializationResult",
                     "data class UnsupportedVersion(val component: VaultFormatComponent, val version: Int?) : VaultInitializationResult",
                     "data object InvalidStructure : VaultInitializationResult",
                     "data class Failed(val reason: VaultInitializationFailure) : VaultInitializationResult"):
        require(required in domain_text, f"vault initialization result must remain precise ({required})")
    data_text = "\n".join(source(path) for path in data_files)
    ui_text = "\n".join(source(path) for path in ui_files)
    for path in domain_files:
        text = source(path)
        require(not re.search(r"^\s*import\s+android\.", text, re.MULTILINE),
                f"Android import is forbidden in vault domain contract: {path.name}")
        require(not re.search(r"\b(?:Uri|DocumentFile|ContentResolver|Context|File)\b", text),
                f"platform storage handle is forbidden in vault domain contract: {path.name}")

    require(all(token in domain_text for token in (
        "RootNotSelected", "NotInitialized", "Ready", "Unavailable", "AccessDenied",
        "CorruptMetadata", "UnsupportedVersion", "InvalidStructure", "InitializationFailed",
    )), "vault domain must distinguish absent, valid, unavailable, corrupt, unsupported and invalid states")
    require("interface VaultStorage" in domain_text and "interface VaultRepository" in domain_text,
            "vault domain must define storage and repository boundaries")
    require("android.net.Uri" not in ui_text and "DocumentFile" not in ui_text and
            "ContentResolver" not in ui_text and "java.io.File" not in ui_text,
            "Compose vault UI must not receive Android storage handles or filesystem paths")
    require(not re.search(r"\b(?:openInputStream|openOutputStream|DocumentsContract|FileInputStream|FileOutputStream)\b", ui_text),
            "Compose vault UI must not perform direct storage I/O")

    forbidden_crypto_implementations = re.compile(
        r"\b(?:javax\.crypto\.|Cipher\.getInstance|SecretKeyFactory\.getInstance|PBKDF2WithHmac|"
        r"AES/GCM/NoPadding|class\s+\w*(?:Aes|AES|Gcm|GCM|KeyDeriver|KeyWrapper)\w*)"
    )
    for path in data_files:
        text = source(path)
        require(not forbidden_crypto_implementations.search(text),
                f"vault data code must reuse Stage 2 cryptography, not implement another primitive: {path.name}")
        credential_pattern = r"\b(?:PrimaryCredentialService|PrimaryCredentialStore|CredentialKeyDeriver|BiometricAuthenticator|password|pin|pattern)\b"
        if path.name != "AndroidVaultContentPresentationRepository.kt":
            credential_pattern = r"\b(?:PrimaryCredentialService|PrimaryCredentialStore|CredentialKeyDeriver|BiometricAuthenticator|CharArray|password|pin|pattern)\b"
            require(not re.search(r"\b(?:SessionManager|sessionManager\.(?:authenticate|lockNow|establish))\b", text),
                    f"vault storage must not create or control an authentication session: {path.name}")
        require(not re.search(credential_pattern, text, re.IGNORECASE),
                f"low-level vault storage must not handle credentials or derive keys from them: {path.name}")
        require(not re.search(r"\b(?:HiddenApplicationRepository|ProtectedApplicationRepository|CamouflageRepository|"
                              r"LauncherActivity|IdentityProfile)\b", text),
                f"vault storage must remain independent of hidden apps, App Lock and camouflage: {path.name}")
        require(not re.search(r"\b(?:Log\.[A-Za-z]+|println\s*\(|Timber\.|Firebase|Analytics|OkHttp|HttpURLConnection|Socket)\b", text),
                f"vault storage must not log, use analytics, or access a network: {path.name}")
        require(not re.search(r"\b(?:getExternalStorageDirectory|getFilesDir|filesDir|noBackupFilesDir|"
                              r"Environment\.getExternalStorage|Downloads|DCIM|Pictures|Documents)\b|"
                              r"['\"]/(?:storage|sdcard)/", text),
                f"vault must not select an automatic or hard-coded filesystem fallback: {path.name}")

    repo = source(data_dir / "DefaultVaultRepository.kt")
    for required in ("AuthenticatedEncryption", "KeyWrappingService", "DeviceKeyStore", "SecureRandomSource",
                     "keyWrapping.wrap(", "keyWrapping.unwrap(", "encryption.encrypt(", "encryption.decrypt(",
                     "KeyProtection.ANDROID_KEYSTORE", "VaultStatus.CorruptMetadata", "VaultStatus.UnsupportedVersion",
                     "VaultStatus.AccessDenied", "VaultStatus.InvalidStructure", "VaultStatus.NotInitialized",
                     "VaultInitializationResult.CorruptMetadata", "VaultInitializationResult.UnsupportedVersion",
                     "unexpectedDataEntries"):
        require(required in repo, f"vault must use existing cryptographic services and fail-closed states ({required})")
    require("CredentialKeyDeriver" not in repo and "PrimaryCredential" not in repo,
            "vault content keys must be random and must not be derived directly from a primary credential")
    require("catch" in repo and "CancellationException" in repo and
            not re.search(r"catch\s*\([^)]*\)\s*\{\s*return(?:@\w+)?\s+(?:emptyList|emptySet|VaultStatus\.NotInitialized)", repo),
            "vault failures must remain explicit and never collapse into an empty/uninitialized result")

    picker = source(data_dir / "VaultRootSelectionHandler.kt")
    for required in ("ACTION_OPEN_DOCUMENT_TREE", "takePersistableUriPermission", "DIFFERENT_ROOT_ALREADY_SELECTED",
                     "FLAG_GRANT_READ_URI_PERMISSION", "FLAG_GRANT_WRITE_URI_PERMISSION"):
        require(required in picker, f"external root must be explicitly selected and reconnected through SAF ({required})")
    storage = source(data_dir / "SafVaultStorage.kt")
    for required in ("DocumentsContract", "vault.nvmeta", "vault.nvmeta.pending", "data",
                     "renameDocument", "contentEquals", "VaultStorageSnapshot.AccessDenied",
                     "VaultMetadataFile.Unreadable", "VaultMetadataFile.Unavailable", "FLAG_SUPPORTS_RENAME"):
        require(required in storage, f"SAF storage is missing explicit root/atomicity/failure handling ({required})")
    require("renameDocument" in storage and "initializeAtomically" in storage and
            "readRaw(finalMetadata)" in storage and "inspectLocked()" in storage,
            "critical metadata must be committed through a temporary document, renamed, read back and checked")

    location_ui = "\n".join((source(package / "MainActivity.kt"), ui_text))
    vault_view_model = source(package / "ui/vault/VaultViewModel.kt")
    require("sessionManager.currentState()" in vault_view_model and
            "sessionManager.mayAccessSensitiveContent()" in vault_view_model,
            "vault inspection and initialization must use the existing SessionManager gate")
    for required in ("VaultInitializationResult.CorruptMetadata -> updateStatus(VaultStatus.CorruptMetadata)",
                     "is VaultInitializationResult.UnsupportedVersion -> updateStatus("):
        require(required in vault_view_model,
                f"vault initialization must preserve precise failure states ({required})")
    root_configuration_screen = source(ui_dir / "VaultRootConfigurationScreen.kt")
    require("sessionManager.currentState()" in root_configuration_screen and
            "sessionManager.mayAccessSensitiveContent()" in root_configuration_screen,
            "root selection/configuration must use the existing SessionManager gate")
    require(not re.search(r"\b(?:authenticatePrimary|authenticateBiometric|lockNow|PrimaryCredentialService|"
                          r"BiometricAuthenticator)\b", ui_text),
            "vault UI must not add a second authentication flow")
    navigation = source(package / "ui/NivaraApp.kt")
    require("AppDestination.Vault.route" in navigation and
            re.search(r"\bVaultScreen\s*\(", navigation) is not None and
            "onManageVault" in source(package / "ui/credentials/CredentialHomeScreen.kt"),
            "vault must be reachable through the existing Nivara navigation graph")
    require("AppDestination.VaultRootConfiguration.route" in navigation and
            re.search(r"\bVaultRootConfigurationScreen\s*\(", navigation) is not None,
            "vault must provide a distinct explicit root-configuration destination in the existing graph")
    require("VaultRootPickerContract" in location_ui and
            "rootSelectionResult = vaultRootSelectionResult" in source(package / "MainActivity.kt"),
            "the system picker must return only a domain-safe result to the existing activity/UI")

    container = source(package / "di/NivaraContainer.kt")
    require("override val vaultRepository:" in container,
            "NivaraContainer must bind the vault repository through the existing application container")
    vault_binding = container.split("override val vaultRepository:", 1)[-1]
    for required in ("DefaultVaultRepository(", "encryption = encryption",
                     "keyWrapping = keyWrapping", "deviceKeyStore = deviceKeyStore", "random = secureRandom"):
        require(required in vault_binding, f"vault DI must reuse the single Stage 2 service graph ({required})")
    require("SafVaultStorage(" in container,
            "the shared Stage 13 SAF storage adapter must remain the vault root authority")
    require(container.count("DefaultVaultRepository(") == 1 and container.count("SafVaultStorage(") == 1,
            "NivaraContainer must bind exactly one vault repository and external storage adapter")

    manifest_text = ET.tostring(manifest, encoding="unicode")
    for forbidden in ("MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
                      "QUERY_ALL_PACKAGES", "BIND_ACCESSIBILITY_SERVICE", "BIND_DEVICE_ADMIN",
                      "POST_NOTIFICATIONS"):
        require(forbidden not in manifest_text, f"vault must not add broad/unnecessary permission: {forbidden}")

    metadata_codec = source(domain_dir / "VaultMetadataCodec.kt")
    outer_metadata_codec, header_codec = metadata_codec.split("sealed interface VaultHeaderDecode", 1)
    for required in ("const val CURRENT_VERSION = 1", "UnsupportedVersion", "NIVLT13M", "VaultId"):
        require(required in outer_metadata_codec,
                f"vault metadata must be versioned and authenticated ({required})")
    for required in ("const val CURRENT_VERSION = 1", "UnsupportedVersion", "NVHDR13", "VaultId"):
        require(required in header_codec,
                f"encrypted vault header must be versioned and authenticated ({required})")
    vault_screen = source(ui_dir / "VaultScreen.kt")
    for required in ("VaultScreen(", "VaultStatus.RootNotSelected", "VaultStatus.NotInitialized",
                     "VaultStatus.Ready", "VaultStatus.Unavailable", "VaultStatus.AccessDenied",
                     "VaultStatus.CorruptMetadata", "VaultStatus.UnsupportedVersion", "VaultStatus.InvalidStructure",
                     "VaultStatus.InitializationFailed"):
        require(required in vault_screen, f"vault screen must render the explicit {required} state")
    docs = source(root / "docs/vault/README.md").lower()
    for required in ("storage access framework", "api 28", "scoped storage", "vault.nvmeta", "data/",
                     "android keystore", "keywrappingservice", "authenticatedencryption", "not initialized",
                     "access denied", "unavailable", "corrupt", "unsupported", "atomic", "no fallback",
                     "stage 14", "stage 18", "reinstall", "permission", "no plaintext credentials",
                     "root-configuration destination"):
        require(required in docs, f"vault documentation must cover {required!r}")
    readme = source(root / "README.md").lower()
    for required in ("external encrypted vault", "docs/vault/readme.md", "stage 14", "file import"):
        require(required in readme, f"README must describe current vault scope ({required!r})")
    require("\n".join(path.read_text(encoding="utf-8") for path in ui_files).strip(),
            "vault UI foundation must contain an explicit state consumer")


def verify_vault_content_architecture(root: Path) -> None:
    """Stage 14 contracts: Android-free records, streamed AEAD, authenticated snapshots and SAF-only import."""
    package = root / "app/src/main/java/com/ashishkumar/nivara"
    domain = package / "domain/vault/content"
    data = package / "data/vault"
    item = source(domain / "VaultItem.kt")
    contracts = source(domain / "VaultIndexContracts.kt")
    codec = source(domain / "VaultIndexCodec.kt")
    repository = source(data / "DefaultVaultRepository.kt")
    index_repo = source(data / "DefaultVaultIndexRepository.kt")
    importer = source(data / "DefaultVaultImportRepository.kt")
    storage = source(data / "SafVaultContentStorage.kt")
    picker = source(data / "VaultSourceDocumentHandler.kt")
    streaming_contract = source(package / "domain/security/CryptoContracts.kt")
    streaming_impl = source(package / "data/security/JcaAesGcmEncryption.kt")
    view_model = source(package / "ui/vault/VaultViewModel.kt")
    screen = source(package / "ui/vault/VaultScreen.kt")
    activity = source(package / "MainActivity.kt")
    container = source(package / "di/NivaraContainer.kt")
    docs = source(root / "docs/vault/README.md").lower()
    stage14_docs = source(root / "docs/vault/stage14.md").lower()
    readme = source(root / "README.md").lower()

    content_domain_files = sorted(domain.glob("*.kt"))
    require(bool(content_domain_files), "Stage 14 Android-free content domain package is missing")
    for path in content_domain_files:
        text = source(path)
        require(not re.search(r"^\s*import\s+android\.", text, re.MULTILINE),
                f"Android dependency in content domain: {path.name}")

    require("val originalFilename: String" in item and "val id: VaultItemId" in item,
            "content records must carry authenticated display metadata and a separate stable item identity")
    require("item.originalFilename" in codec and "item.encryptedItemKey" in codec and "item.id.toBytes()" in codec,
            "canonical index rows must authenticate filename metadata, item identity, and only an encrypted item key")
    require("value.matches(Regex(\"[0-9a-f]{32}\"))" in item and "objectName(id: VaultItemId)" in storage,
            "internal item IDs must be fixed-width names, never derived from original filenames")
    require("random.generateBytes(VaultItemId.BYTE_COUNT)" in importer,
            "stable internal item IDs must be independently generated with the shared secure random source")
    require("MAX_INDEX_BYTES = 8 * 1024 * 1024" in codec and "MAX_ITEMS = 20_000" in codec,
            "the authenticated index codec must enforce explicit byte and record-count bounds")
    require("Unsupported(version)" in codec and "DecodeResult.Invalid" in codec and "input.available() != 0" in codec,
            "index decoding must distinguish unsupported versions and reject malformed/trailing data")
    require("content-index.v1" in repository and "indexContext(vaultId, generation)" in repository,
            "index ciphertext must use its dedicated Stage 2 AAD context, not the metadata context")
    require(re.search(r"\binterface\s+VaultContentCrypto\s*\{", contracts) is not None and
            "Aes256Key" not in contracts and "rawKey" not in contracts,
            "the content-key bridge must not export key handles or raw key material")
    require("StreamingAuthenticatedEncryption" in streaming_contract and "64 * 1024" in streaming_impl,
            "Stage 2 must provide bounded-memory streaming AES-GCM through its existing crypto service")
    require("cipher.updateAAD(aad)" in streaming_impl and "cipher.update(buffer, 0, count)" in streaming_impl and "cipher.doFinal()" in streaming_impl,
            "streaming AES-GCM must authenticate context and require final tag verification")
    require("MAGIC = byteArrayOf(0x4e, 0x56, 0x43, 0x4f)" in codec and "NONCE_BYTES = 12" in codec and "VERSION = 1" in codec,
            "content objects must use an explicit versioned AES-GCM header and 96-bit nonce")
    require("random.generateAes256KeyBytes()" in repository and "random.generateGcmNonce()" in repository and
            "ITEM_KEY_PURPOSE" in repository,
            "each streamed content object must use a fresh scoped data key and a dedicated wrapping purpose")
    require("ACTION_OPEN_DOCUMENT" in picker and "VaultSourceSelectionId" in picker and
            "FLAG_GRANT_PERSISTABLE_URI_PERMISSION" not in picker,
            "source documents must be selected with SAF transiently and without persisting the source URI")
    require("ConcurrentHashMap<VaultSourceSelectionId, Uri>" in picker and "SharedPreferences" not in picker,
            "source URIs must remain one-shot in-memory data-layer handles")
    require(".nvc" in storage and "OBJECT_PENDING_PREFIX" in storage and "renameDocument" in storage and
            "FLAG_SUPPORTS_RENAME" in storage,
            "object outputs must use generated names and same-directory temporary-to-final rename")
    require("index-%020d.vxi" in storage and "before.generations.maxOrNull() != generation - 1" in storage and
            "contentEquals(bytes)" in storage,
            "index generations must be immutable, serialized, read-back-verified SAF snapshots")
    require("VaultObjectCommitResult.AuthorizationExpired" in storage and
            "VaultIndexFileCommitResult.AuthorizationExpired" in storage and "authorizationCheckpoint()" in storage,
            "session expiry must be checked at both SAF object-finalization and index-commit boundaries")
    require("VaultIndexRead.Missing" in index_repo and "VaultIndexRead.Corrupt" in index_repo and
            "VaultIndexRead.UnsupportedVersion" in index_repo and "VaultIndexRead.ObjectsWithoutIndex" in index_repo,
            "missing, empty, populated, corrupt, unsupported and objects-without-index outcomes must stay distinct")
    require("files.generations.maxOrNull()" in index_repo and "return VaultIndexRead.Corrupt" in index_repo,
            "a corrupt newest snapshot must fail closed rather than fall back to an older snapshot or empty state")
    require("authorizationCheckpoint" in importer and "crypto.verifyObject" in importer and "index.addItem" in importer,
            "imports must checkpoint existing authorization, validate finalized ciphertext, then add one index record")
    require("validMetadata(metadata)" in importer and "SourceSizeMismatch" in importer,
            "provider filename/MIME/size metadata must be validated against the streamed source size")
    require("completed.clear()" in importer and "sources.discard(sourceId)" in importer and "encryptedItemKey" in repository,
            "scoped item-key buffers and transient source handles must be cleared/discarded")
    require("readBytes()" not in importer + storage + repository and "readAllBytes()" not in importer + storage + repository,
            "arbitrary user file content must never be materialized as one byte array")
    require("VaultImportResult.AuthorizationExpired" in view_model and "hasValidSession()" in view_model and
            "SessionManager" in source(package / "domain/security/session/SessionContracts.kt"),
            "import authorization and expiry must reuse the existing absolute-timeout session")
    require("VaultSourcePickerContract" in activity and "sourceSelectionResult = vaultSourceSelectionResult" in activity,
            "the Android picker result must pass only a domain-safe one-shot selection token into the existing UI")
    require("index.items.size" in screen and "originalFilename" in screen and "onChooseSource" in screen,
            "Stage 14 UI must show a basic authenticated item list/count and explicit picker action")
    require(all(state in screen for state in (
        "VaultIndexRead.Missing", "VaultIndexRead.Ready", "VaultIndexRead.Corrupt",
        "VaultIndexRead.UnsupportedVersion", "VaultIndexRead.Unavailable", "VaultIndexRead.AccessDenied",
        "VaultIndexRead.ObjectsWithoutIndex", "VaultIndexRead.VaultUnavailable",
    )), "the vault UI must render each distinct authenticated index outcome")
    require("private val mutex = Mutex()" in importer and "private val mutex = Mutex()" in index_repo,
            "conflicting imports and index writes must be serialized in-process")
    require("override val vaultRepository: VaultRepository by lazy" in container and
            "DefaultVaultIndexRepository(" in container and "DefaultVaultImportRepository(" in container and
            "vaultContentCrypto" in container,
            "Stage 14 DI must share the single Stage 13 repository and Stage 2 crypto graph")
    require("no network" in docs and "no plaintext" in docs and "stage 18" in docs,
            "vault foundation documentation must define privacy and deferred-scope limits")
    require("unindexed orphan" in stage14_docs and "64 kib" in stage14_docs and "stage 15" in stage14_docs and
            "stage 18" in stage14_docs,
            "Stage 14 documentation must define bounded streaming, orphan behavior, and scope boundaries")
    require("stage14.md" in docs and "stage 14" in readme and "file import" in readme and "docs/vault/readme.md" in readme,
            "README must describe the delivered Stage 14 import scope and vault documentation")
    require(not re.search(r"\bclass\s+\w*(?:Album|Trash|Restore|Thumbnail|Recovery)\w*", item + contracts + importer + screen),
            "Stage 14 must not implement Stage 15–18 presentation, trash, restore or recovery features")

    require("deleteDocument" not in importer and "resolver.delete" not in importer and
            "openOutputStream" not in importer,
            "the import orchestrator must never mutate source documents")
    require("Log." not in importer and "println(" not in importer and "Timber." not in importer,
            "import orchestration must not log source identifiers, filenames, keys, or file content")

def verify_vault_presentation_architecture(root: Path) -> None:
    """Stage 15 presentation safety: authenticated metadata, bounded volatile previews, and cleanup."""
    package = root / "app/src/main/java/com/ashishkumar/nivara"
    domain = package / "domain/vault/content"
    data = package / "data/vault"
    classifier = source(domain / "VaultContentPresentation.kt")
    gateway = source(domain / "VaultContentPresentationGateway.kt")
    adapter = source(data / "AndroidVaultContentPresentationRepository.kt")
    decoder = source(data / "AndroidVaultImageDecoder.kt")
    view_model = source(package / "ui/vault/VaultItemViewerViewModel.kt")
    screen = source(package / "ui/vault/VaultScreen.kt")
    app = source(package / "ui/NivaraApp.kt")
    activity = source(package / "MainActivity.kt")
    classifier_test = source(root / "app/src/test/java/com/ashishkumar/nivara/domain/vault/content/VaultContentClassifierTest.kt")
    viewer_test = source(root / "app/src/test/java/com/ashishkumar/nivara/ui/vault/VaultItemViewerViewModelTest.kt")
    crypto_test = source(root / "app/src/test/java/com/ashishkumar/nivara/data/vault/DefaultVaultRepositoryTest.kt")
    decoder_test = source(root / "app/src/androidTest/java/com/ashishkumar/nivara/data/vault/AndroidVaultImageDecoderInstrumentedTest.kt")
    presentation_test = source(root / "app/src/androidTest/java/com/ashishkumar/nivara/data/vault/AndroidVaultContentPresentationInstrumentedTest.kt")
    docs = source(root / "docs/vault/stage15.md").lower()
    readme = source(root / "README.md").lower()

    for path in sorted(domain.glob("*.kt")):
        text = source(path)
        require(not re.search(r"^\s*import\s+android\.", text, re.MULTILINE),
                f"Android dependency in Stage 15 content domain: {path.name}")
    require("classify(item: VaultItem): VaultContentClassification = classify(item.originalMimeType)" in classifier,
            "content type must come only from the authenticated original MIME metadata")
    require("VaultContentCategory.OTHER, null" in classifier and "VaultItemValidation::validateMimeType" in classifier,
            "missing and invalid MIME values must conservatively become Other/Unknown")
    require("originalFilename" not in classifier and "substringAfterLast" not in classifier,
            "classification must not infer content from filenames or extensions")
    require("data class VaultItem" not in classifier and "originalMimeType =" not in classifier,
            "presentation classification must not modify or duplicate persisted item metadata")
    require("import android." not in gateway and "VaultPresentationHandle" in gateway and
            "java.io.InputStream" not in gateway and "Cipher" not in gateway,
            "presentation domain contracts must be Android-free and expose only opaque handles and typed values")
    require("decryptObjectToQuarantine" in adapter and "AuthenticationFailed" in adapter and
            "copyBytes()" in adapter and "quarantine.wipe()" in adapter,
            "plaintext previews must remain quarantined until Stage 14 streaming GCM finalization succeeds")
    require("MAX_IMAGE_COMPRESSED_BYTES" in adapter and "MAX_TEXT_BYTES" in adapter and
            "WipingBoundedOutputStream" in adapter and "inSampleSize = sample" in decoder and
            "MAX_IMAGE_RENDER_DIMENSION" in decoder,
            "image and text rendering must use bounded volatile buffers and sampled image decoding")
    require(not any(token in adapter for token in (
        "FileOutputStream", "MediaPlayer", "PdfRenderer", "cacheDir", "File.createTempFile",
        "openOutputStream", "readBytes()", "readAllBytes()",
    )), "viewer must not stage plaintext files, media, documents, or unbounded content to disk/memory")
    require("PreviewKind.IMAGE" in adapter and "PreviewKind.TEXT" in adapter and
            "PreviewKind.PDF" in classifier and "PreviewKind.AUDIO" in classifier and
            "PreviewKind.VIDEO" in classifier and "return@withContext VaultPresentationOpenResult.Unsupported" in adapter,
            "only bounded image/text previews may be implemented; other recognized MIME classes stay explicitly unsupported")
    require("classificationUsesMimeNotFilename" in classifier_test and
            "quickLockClosesActiveResource" in viewer_test and "closeDuringOpenClosesAHandle" in viewer_test and
            "unsupportedEncryptedObjectVersion" in viewer_test and
            "decryptObjectToQuarantine" in crypto_test and "wideImageIsSampledToTheConfiguredRenderDimension" in decoder_test and
            "quickLockClosesPreviewResourceAfterSuccessfulGatewayOpen" in presentation_test,
            "Stage 15 must include JVM classification/session/crypto tests and instrumented decoder/resource-cleanup tests")
    require("sessions.currentState() is SessionState.Authenticated" in adapter and
            "sessions.mayAccessSensitiveContent()" in adapter and "checkpoint()" in adapter and
            "sessions.sessionState.collect" in adapter and "closeAll()" in adapter,
            "opening must reuse an existing authorized session and close active resources on invalidation")
    require("currentSession == null" in adapter and "previousSession != currentSession" in adapter,
            "a quickly replaced authenticated session must also invalidate resources despite StateFlow conflation")
    require("authenticatePrimary" not in adapter and "authenticateBiometric" not in adapter and "lockNow" not in adapter,
            "viewer adapter must not establish, refresh, or lock sessions")
    require("lockAndClose()" in view_model and "gateway.close" in view_model and
            "onActivityPaused() = close()" in view_model,
            "viewer ViewModel must release opaque handles on Quick Lock, expiry, lifecycle pause, and close")
    require("previous != null && previous != current" in view_model and
            "replacedAuthenticatedSessionClosesViewer" in viewer_test,
            "a replaced session must close viewer handles even if StateFlow conflates the unauthenticated transition")
    state_decl = view_model.split("data class VaultItemViewerUiState(", 1)[1].split("\n)", 1)[0]
    require(not re.search(r"\b(?:InputStream|OutputStream|Cipher|SecretKey|Aes256Key|ByteArray|CharArray|Bitmap|Uri|File)\b", state_decl),
            "viewer UI state must not carry streams, ciphers, keys, plaintext buffers, or platform handles")
    require("onDispose" in screen and "model.close()" in screen and "ON_PAUSE" in screen and
            "gateway.close" not in screen,
            "Compose must deterministically close the ViewModel-owned handle on disposal and app pause")
    require("SecureScreenEffect()" in screen,
            "the sensitive vault destination must reuse the existing screenshot/recents protection effect")
    require("VaultContentClassifier.classify" in screen and "originalMimeType" in screen and
            "UnsupportedContent()" in screen and "unindexedObjects" in screen and "unfinished" in screen.lower(),
            "vault UI must show classified item metadata and preserve explicit unsupported and inventory diagnostics")
    require("vaultContentPresentationGateway" in app and "vaultContentPresentationGateway" in activity,
            "Stage 15 presentation must be wired through the existing app graph without another repository architecture")
    require("no plaintext cache" in docs and "not supported" in docs and "no permissions" in docs and
            "quick lock" in docs and "stage 16" in docs,
            "Stage 15 documentation must state unsupported formats, security/resource limits, and deferred scope")
    require("stage15.md" in readme and "bounded image" in readme and "audio/video" in readme,
            "README must make bounded image/text support and unsupported media behavior accurate")


def verify_vault_organization_architecture(root: Path) -> None:
    """Stage 16 organization contracts, persistence, UI, and existing security-boundary checks."""
    package = root / "app/src/main/java/com/ashishkumar/nivara"
    domain = package / "domain/vault/content"
    data = package / "data/vault"
    ui = package / "ui/vault"
    model_path = domain / "VaultOrganization.kt"
    codec_path = domain / "VaultOrganizationCodec.kt"
    contracts_path = domain / "VaultIndexContracts.kt"
    model = source(model_path)
    codec = source(codec_path)
    contracts = source(contracts_path)
    crypto = source(data / "DefaultVaultRepository.kt")
    organization_repository = source(data / "DefaultVaultOrganizationRepository.kt")
    storage = source(data / "SafVaultContentStorage.kt")
    root_storage = source(data / "SafVaultStorage.kt")
    view_model = source(ui / "VaultViewModel.kt")
    screen = source(ui / "VaultScreen.kt")
    container = source(package / "di/NivaraContainer.kt")
    nav = source(package / "ui/NivaraApp.kt")
    readme = source(root / "README.md").lower()
    vault_docs = source(root / "docs/vault/README.md").lower()
    stage_docs = source(root / "docs/vault/stage16.md").lower()

    for path in (model_path, codec_path, contracts_path):
        text = source(path)
        require(not re.search(r"^\s*import\s+android\.", text, re.MULTILINE),
                f"Android dependency in Stage 16 domain contract: {path.name}")
    for required in ("value class VaultAlbumId", "class VaultAlbum(", "VaultItemId",
                     "memberItemIds", "MAX_CODE_POINTS = 100", "MAX_ALBUMS = 1_000",
                     "MAX_ITEMS_PER_ALBUM = 20_000", "MAX_TOTAL_MEMBERSHIPS = 100_000",
                     "AlreadyMember", "VaultAlbumMember.Stale", "IndexUnreadable", "NoMatches",
                     "Field.NAME", "Field.SIZE", "Field.IMPORT_TIME", "Field.TYPE",
                     "thenBy { it.item.id.value }", "originalFilename", "originalMimeType",
                     "VaultContentClassifier.classify", "item.originalFilename", "item.originalMimeType",
                     "IndexMissing", "UnsupportedIndex"):
        require(required in model, f"Stage 16 organization contract is missing {required!r}")
    require("require(members.toSet().size == members.size)" in model and
            "itemId in album.memberItemIds" in organization_repository and
            "VaultAlbumMutationResult.AlreadyMember" in organization_repository,
            "duplicate memberships must be explicitly rejected as AlreadyMember without changing state")
    require("data class Stale(override val itemId: VaultItemId)" in model and
            "memberItemIds.map" in model and "VaultAlbumMembershipResolver" in model,
            "stale album references must be returned explicitly and must not be pruned by ordinary reads")
    require("MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024" in model and
            "encoded.size !in HEADER_BYTES..VaultOrganizationLimits.MAX_PLAINTEXT_BYTES" in codec and
            "input.available() != 0" in codec and "DecodeResult.Unsupported" in codec and
            "previousAlbumId" in codec,
            "organization decoding must be versioned, strictly validated, deterministic, and bounded")
    require("VaultOrganizationEnvelopeCodec" in codec and "generation" in codec,
            "organization ciphertext records must bind and verify their generation")
    require("encryptOrganization" in contracts and "decryptOrganization" in contracts and
            "VaultOrganizationLimits.MAX_PLAINTEXT_BYTES" in crypto and
            "nivara.vault.organization-metadata.v1" in crypto and
            "organizationContext(vaultId, generation)" in crypto and "withContentKey(vaultId)" in crypto,
            "organization records must reuse the existing vault key with a distinct generation-bound crypto purpose")
    require("data/organization" in storage or 'ORGANIZATION_DIRECTORY = "organization"' in storage,
            "organization records must be stored under the existing vault data root")
    require("DocumentsContract.renameDocument" in storage and "firstReadback.contentEquals(bytes)" in storage and
            "finalReadback.contentEquals(bytes)" in storage and "pruneOrganizationFiles" in storage and
            "discardOrganizationFile" in storage and
            "authorizationCheckpoint()" in storage and "MAX_ORGANIZATION_GENERATIONS" in storage,
            "organization writes require bounded atomic generation commits, readback, authorization, and post-verification pruning")
    require('setOf("index", "objects", "organization")' in root_storage and
            "setOf(INDEX_DIRECTORY, OBJECTS_DIRECTORY, ORGANIZATION_DIRECTORY)" in storage,
            "strict SAF structural allow-lists must recognize only the new organization directory")
    verified_position = organization_repository.find("verified.snapshot != next")
    prune_position = organization_repository.find("storage.pruneOrganizationFiles(keep, authorizationCheckpoint)")
    require(prune_position > verified_position >= 0 and "takeLast(2).toSet()" in organization_repository and
            "storage.discardOrganizationFile(next.generation)" in organization_repository,
            "generation pruning may occur only after authenticated readback; failed verification preserves prior generations")
    require("SessionManager" in view_model and "hasValidSession()" in view_model and
            all(token in view_model for token in ("createAlbum", "renameAlbum", "deleteAlbum", "addMembership", "removeMembership")) and
            all(call in view_model for call in (
                "repository.createAlbum(vaultId, name, checkpoint)",
                "repository.renameAlbum(vaultId, albumId, name, checkpoint)",
                "repository.deleteAlbum(vaultId, albumId, checkpoint)",
                "repository.addMembership(vaultId, albumId, itemId, checkpoint)",
                "repository.removeMembership(vaultId, albumId, itemId, checkpoint)",
            )) and
            "authorizationCheckpoint" in organization_repository,
            "every organization mutation must use the existing SessionManager authorization checkpoint")
    require(all(token in screen for token in ("All Items", "Albums", "Search", "Create album", "Rename", "Delete album",
                     "Add / remove album membership", "Remove reference", "VaultItemSearch.search")),
            "the existing vault screen must expose All Items, Albums, Search, and album/membership controls")
    require(len(re.findall(r"(?m)^\s*VaultItemViewerContent\(", screen)) == 1 and
            "selectedItemId = it.id" in screen and
            "gateway = contentPresentationGateway" in screen,
            "all organization collection selections must continue through the single Stage 15 item-ID viewer path")
    require("openObject" not in model and "decrypt" not in model and "openObject" not in organization_repository and
            "decryptObject" not in organization_repository,
            "search, sorting, and albums must not open or decrypt item content")
    require("vaultOrganizationRepository" in container and "vaultOrganizationRepository" in nav,
            "the existing dependency graph and navigation must bind the single organization repository")
    require("stage16" in readme and "albums" in vault_docs and "sessionmanager" in stage_docs and
            "authorization callback" in stage_docs and "authenticated index" in stage_docs and "stale" in stage_docs,
            "README and vault documentation must describe Stage 16 semantics and truthful security boundaries")
    require((root / "app/src/test/java/com/ashishkumar/nivara/domain/vault/content/VaultOrganizationTest.kt").is_file() and
            (root / "app/src/test/java/com/ashishkumar/nivara/data/vault/DefaultVaultOrganizationRepositoryTest.kt").is_file() and
            (root / "app/src/test/java/com/ashishkumar/nivara/ui/vault/VaultViewModelOrganizationTest.kt").is_file() and
            (root / "app/src/androidTest/java/com/ashishkumar/nivara/data/vault/VaultOrganizationContractInstrumentedTest.kt").is_file(),
            "Stage 16 domain, persistence, ViewModel, and instrumented tests must be present")


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
    verify_vault_manifest(manifest)
    verify_vault_architecture(ROOT, manifest)
    verify_vault_content_architecture(ROOT)
    verify_vault_presentation_architecture(ROOT)
    verify_vault_organization_architecture(ROOT)

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
    print("PASS: Stages 13–16 vault organization, search, sorting, viewer integration, authenticated import/index, App Lock, hidden-app, launcher, persistence, auth, and permission contracts.")


if __name__ == "__main__":
    main()
