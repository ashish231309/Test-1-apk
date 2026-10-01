"""Negative regression tests for Stage 11 launcher manifest and architecture checks."""

from __future__ import annotations

import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_nivara  # noqa: E402


ANDROID_NS = "http://schemas.android.com/apk/res/android"


class LauncherVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.package = self.root / "app/src/main/java/com/ashishkumar/nivara"
        self._write(
            "ui/launcher/LauncherViewModel.kt",
            """class LauncherViewModel(
                val applicationRepository: ApplicationRepository,
                val hiddenApplicationRepository: HiddenApplicationRepository,
                val sessionManager: SessionManager,
            ) {
                suspend fun validate() { sessionManager.currentState(); sessionManager.lockNow() }
                fun live(revealSession: Any) = sessionManager.sessionState.value === revealSession
                suspend fun load() { hiddenApplicationRepository.getHiddenApplications() }
                fun order() = InstalledApplicationOrdering.deterministic(emptyList())
                val safeStates = listOf(HiddenApplicationsSnapshot.Unreadable,
                    HiddenApplicationsSnapshot.Unavailable, ApplicationDiscoveryResult.Unavailable)
            }""",
        )
        self._write("ui/launcher/LauncherScreen.kt", "SecureScreenEffect(); AndroidApplicationIconProvider; SessionState.Authenticated; hiddenApplicationsRevealed")
        self._write(
            "LauncherActivity.kt",
            "Intent(this, MainActivity::class.java); getLaunchIntentForPackage(application.packageName)",
        )
        self._write("domain/apphide/HiddenApplication.kt", "data class HiddenApplication(val packageName: String)")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_minimal_valid_launcher_architecture_passes(self) -> None:
        verify_nivara.verify_launcher_architecture(self.root)
        verify_nivara.verify_launcher_manifest(self._manifest())

    def test_launcher_repository_boundary_is_required(self) -> None:
        self._write("ui/launcher/LauncherViewModel.kt", "SessionManager.currentState()")
        self._assert_architecture_rejected()

    def test_persistent_reveal_storage_is_rejected(self) -> None:
        self._append("ui/launcher/LauncherViewModel.kt", "\nDataStore")
        self._assert_architecture_rejected()

    def test_second_in_memory_hidden_app_list_is_rejected(self) -> None:
        self._append("ui/launcher/LauncherViewModel.kt", "\nunhiddenApps")
        self._assert_architecture_rejected()

    def test_direct_hidden_codec_access_is_rejected(self) -> None:
        self._write("ui/launcher/LauncherScreen.kt", "SecureScreenEffect(); HiddenApplicationsCodec")
        self._assert_architecture_rejected()

    def test_launcher_specific_authentication_is_rejected(self) -> None:
        self._write("LauncherActivity.kt", "BiometricAuthenticator")
        self._assert_architecture_rejected()

    def test_duplicate_package_scanner_is_rejected(self) -> None:
        self._write("LauncherActivity.kt", "PackageManager.queryIntentActivities")
        self._assert_architecture_rejected()

    def test_installed_package_enumeration_is_rejected(self) -> None:
        self._write("LauncherActivity.kt", "PackageManager.getInstalledPackages")
        self._assert_architecture_rejected()

    def test_unprotected_launcher_screen_is_rejected(self) -> None:
        self._write("ui/launcher/LauncherScreen.kt", "AndroidApplicationIconProvider")
        self._assert_architecture_rejected()

    def test_stale_reveal_snapshot_must_be_gated_by_live_session(self) -> None:
        self._write("ui/launcher/LauncherScreen.kt", "SecureScreenEffect(); AndroidApplicationIconProvider")
        self._assert_architecture_rejected()

    def test_home_activity_must_be_explicitly_exported(self) -> None:
        root = self._manifest()
        activity = root.find("application/activity")
        assert activity is not None
        activity.set(f"{{{ANDROID_NS}}}exported", "false")
        self._assert_manifest_rejected(root)

    def test_home_action_and_categories_are_required_exactly(self) -> None:
        root = self._manifest()
        home_filter = root.find("application/activity/intent-filter")
        assert home_filter is not None
        action = home_filter.find("action")
        assert action is not None
        action.set(f"{{{ANDROID_NS}}}name", "android.intent.action.VIEW")
        self._assert_manifest_rejected(root)

        root = self._manifest()
        default_category = root.find("application/activity/intent-filter/category[@android:name='android.intent.category.DEFAULT']", {
            "android": ANDROID_NS,
        })
        assert default_category is not None
        root.find("application/activity/intent-filter").remove(default_category)  # type: ignore[union-attr]
        self._assert_manifest_rejected(root)

    def test_second_home_or_other_exported_component_is_rejected(self) -> None:
        root = self._manifest()
        application = root.find("application")
        assert application is not None
        duplicate = ET.SubElement(application, "activity", {
            f"{{{ANDROID_NS}}}name": ".OtherHomeActivity",
            f"{{{ANDROID_NS}}}exported": "true",
        })
        intent_filter = ET.SubElement(duplicate, "intent-filter")
        ET.SubElement(intent_filter, "action", {f"{{{ANDROID_NS}}}name": "android.intent.action.MAIN"})
        ET.SubElement(intent_filter, "category", {f"{{{ANDROID_NS}}}name": "android.intent.category.HOME"})
        ET.SubElement(intent_filter, "category", {f"{{{ANDROID_NS}}}name": "android.intent.category.DEFAULT"})
        self._assert_manifest_rejected(root)

        root = self._manifest()
        application = root.find("application")
        assert application is not None
        ET.SubElement(application, "service", {
            f"{{{ANDROID_NS}}}name": ".UnsafeService",
            f"{{{ANDROID_NS}}}exported": "true",
        })
        self._assert_manifest_rejected(root)

    def test_unrelated_launcher_intent_filter_is_rejected(self) -> None:
        root = self._manifest()
        application = root.find("application")
        assert application is not None
        activity = ET.SubElement(application, "activity", {
            f"{{{ANDROID_NS}}}name": ".MainActivity",
            f"{{{ANDROID_NS}}}exported": "false",
        })
        intent_filter = ET.SubElement(activity, "intent-filter")
        ET.SubElement(intent_filter, "action", {f"{{{ANDROID_NS}}}name": "android.intent.action.MAIN"})
        ET.SubElement(intent_filter, "category", {f"{{{ANDROID_NS}}}name": "android.intent.category.LAUNCHER"})
        self._assert_manifest_rejected(root)

    def _manifest(self) -> ET.Element:
        return ET.fromstring(
            f"""<manifest xmlns:android="{ANDROID_NS}">
              <application android:label="@string/camouflage_identity_label"
                           android:icon="@mipmap/camouflage_home" android:roundIcon="@mipmap/camouflage_home">
                <activity android:name=".LauncherActivity" android:exported="true"
                          android:label="@string/camouflage_identity_label" android:icon="@mipmap/camouflage_home">
                  <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.HOME" />
                    <category android:name="android.intent.category.DEFAULT" />
                  </intent-filter>
                </activity>
                <activity android:name=".MainActivity" android:exported="true"
                          android:label="@string/camouflage_identity_label" android:icon="@mipmap/camouflage_home">
                  <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
                </activity>
                <activity android:name=".data.applock.AppLockBiometricActivity" android:exported="false" />
                <service android:name=".data.applock.AppLockDetectionService" android:exported="false" />
              </application>
            </manifest>""",
        )

    def _assert_architecture_rejected(self) -> None:
        with self.assertRaises(SystemExit):
            verify_nivara.verify_launcher_architecture(self.root)

    def _assert_manifest_rejected(self, manifest: ET.Element) -> None:
        with self.assertRaises(SystemExit):
            verify_nivara.verify_launcher_manifest(manifest)

    def _append(self, relative: str, text: str) -> None:
        path = self.package / relative
        path.write_text(path.read_text(encoding="utf-8") + text, encoding="utf-8")

    def _write(self, relative: str, text: str) -> None:
        path = self.package / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
