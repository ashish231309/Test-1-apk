"""Positive and deliberately failing Stage 12 camouflage/recovery verifier fixtures."""

from __future__ import annotations

import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_nivara  # noqa: E402


ANDROID_NS = "http://schemas.android.com/apk/res/android"


class CamouflageVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "app/src/main/res/values").mkdir(parents=True)
        (self.root / "app/src/main/res/mipmap-anydpi-v26").mkdir(parents=True)
        package = self.root / "app/src/main/java/com/ashishkumar/nivara"
        package.mkdir(parents=True)
        self.manifest = self._manifest()
        self._write("app/src/main/res/values/strings.xml", '<resources><string name="camouflage_identity_label">Home</string></resources>')
        self._write("app/src/main/res/mipmap-anydpi-v26/camouflage_home.xml", "<adaptive-icon />")
        self._write("app/build.gradle.kts", 'android { namespace = "com.ashishkumar.nivara"; defaultConfig { applicationId = "com.ashishkumar.nivara" } }')
        self._write(
            "app/src/main/java/com/ashishkumar/nivara/MainActivity.kt",
            "container.primaryCredentialService; container.biometricAuthenticator(this); "
            "sessionManager = container.sessionManager; NivaraApp(",
        )
        self._write(
            "docs/camouflage/README.md",
            "Fixed benign identity; no profile persistence; recovery works when another app is selected "
            "and after process recreation, Quick Lock, and session expiry. SessionManager and the "
            "primary-credential/biometric flow. Camouflage does not provide invisibility, encryption, "
            "anti-forensics; it is not a security boundary. Android Settings; no new permission.",
        )
        self._write("README.md", "ordinary recovery entry; not concealment from Android; docs/camouflage/README.md")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_valid_fixed_identity_and_recovery_contract_passes(self) -> None:
        verify_nivara.verify_camouflage_manifest(self.manifest, self.root)
        verify_nivara.verify_camouflage_architecture(self.root)

    def test_non_benign_application_label_is_rejected(self) -> None:
        self.manifest.find("application").set(f"{{{ANDROID_NS}}}label", "@string/app_name")
        self._assert_manifest_rejected()

    def test_identity_resource_must_be_fixed_home(self) -> None:
        self._write("app/src/main/res/values/strings.xml", '<resources><string name="camouflage_identity_label">Nivara</string></resources>')
        self._assert_manifest_rejected()

    def test_identity_icon_must_be_applied_consistently(self) -> None:
        self.manifest.find("application").set(f"{{{ANDROID_NS}}}icon", "@drawable/nivara_icon")
        self._assert_manifest_rejected()

    def test_recovery_must_be_exported_for_the_system_launcher(self) -> None:
        recovery = self.manifest.find("application/activity[@android:name='.MainActivity']", {"android": ANDROID_NS})
        recovery.set(f"{{{ANDROID_NS}}}exported", "false")
        self._assert_manifest_rejected()

    def test_recovery_must_not_claim_home_role(self) -> None:
        recovery_filter = self.manifest.find("application/activity[@android:name='.MainActivity']/intent-filter", {"android": ANDROID_NS})
        recovery_filter.find("category").set(f"{{{ANDROID_NS}}}name", "android.intent.category.HOME")
        self._assert_manifest_rejected()

    def test_broadened_package_visibility_is_rejected(self) -> None:
        queries = ET.SubElement(self.manifest, "queries")
        ET.SubElement(queries, "package", {f"{{{ANDROID_NS}}}name": "com.example.other"})
        self._assert_manifest_rejected()

    def test_unrelated_exported_component_is_rejected(self) -> None:
        application = self.manifest.find("application")
        ET.SubElement(application, "service", {
            f"{{{ANDROID_NS}}}name": ".UnexpectedService",
            f"{{{ANDROID_NS}}}exported": "true",
        })
        self._assert_manifest_rejected()

    def test_activity_alias_is_rejected(self) -> None:
        application = self.manifest.find("application")
        ET.SubElement(application, "activity-alias", {
            f"{{{ANDROID_NS}}}name": ".HomeAlias",
            f"{{{ANDROID_NS}}}exported": "false",
        })
        self._assert_manifest_rejected()

    def test_package_identity_change_is_rejected(self) -> None:
        self._write("app/build.gradle.kts", 'android { namespace = "com.example.changed"; defaultConfig { applicationId = "com.ashishkumar.nivara" } }')
        self._assert_manifest_rejected()

    def test_parallel_recovery_authentication_is_rejected(self) -> None:
        self._write("app/src/main/java/com/ashishkumar/nivara/MainActivity.kt", "RecoveryCredentialStore()")
        self._assert_architecture_rejected()

    def test_component_disabling_is_rejected(self) -> None:
        self._write("app/src/main/java/com/ashishkumar/nivara/SomeUi.kt", "setComponentEnabledSetting(component)")
        self._assert_architecture_rejected()

    def test_invisibility_claim_documentation_is_required(self) -> None:
        self._write("docs/camouflage/README.md", "The app is invisible and secure.")
        self._assert_architecture_rejected()

    def test_recovery_after_process_and_session_events_must_be_documented(self) -> None:
        self._write("docs/camouflage/README.md", "Fixed benign identity and security boundary.")
        self._assert_architecture_rejected()

    def _manifest(self) -> ET.Element:
        return ET.fromstring(
            f"""<manifest xmlns:android="{ANDROID_NS}">
              <queries><intent>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
              </intent></queries>
              <application android:label="@string/camouflage_identity_label"
                           android:icon="@mipmap/camouflage_home" android:roundIcon="@mipmap/camouflage_home">
                <activity android:name=".MainActivity" android:exported="true"
                          android:label="@string/camouflage_identity_label" android:icon="@mipmap/camouflage_home">
                  <intent-filter><action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" /></intent-filter>
                </activity>
                <activity android:name=".LauncherActivity" android:exported="true"
                          android:label="@string/camouflage_identity_label" android:icon="@mipmap/camouflage_home">
                  <intent-filter><action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.HOME" />
                    <category android:name="android.intent.category.DEFAULT" /></intent-filter>
                </activity>
              </application>
            </manifest>""",
        )

    def _assert_manifest_rejected(self) -> None:
        with self.assertRaises(SystemExit):
            verify_nivara.verify_camouflage_manifest(self.manifest, self.root)

    def _assert_architecture_rejected(self) -> None:
        with self.assertRaises(SystemExit):
            verify_nivara.verify_camouflage_architecture(self.root)

    def _write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
