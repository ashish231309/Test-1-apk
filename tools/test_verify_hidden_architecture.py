"""Negative regression tests for the Stage 10 architectural verifier rules."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_nivara  # noqa: E402


class HiddenArchitectureVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.package = self.root / "app/src/main/java/com/ashishkumar/nivara"
        self._write("domain/apphide/HiddenApplication.kt", "data class HiddenApplication(val packageName: String)\n")
        self._write(
            "data/apphide/AndroidHiddenApplicationRepository.kt",
            """class AndroidHiddenApplicationRepository internal constructor() : HiddenApplicationRepository {
                val atomic = AtomicFile()
                val codec = HiddenApplicationsCodec
                val unreadable = HiddenApplicationsSnapshot.Unreadable
                val unavailable = HiddenApplicationsSnapshot.Unavailable
            }""",
        )
        self._write(
            "ui/apphide/HiddenApplicationManagementViewModel.kt",
            """InstalledApplicationSearch.matches
                InstalledApplicationOrdering.deterministic
                InstalledApplicationOrdering.reverseAlphabetical
                sessionManager.currentState()
                protectedApplicationRepository.getProtectedApplications()""",
        )
        self._write("ui/apphide/HiddenApplicationManagementScreen.kt", "SecureScreenEffect()")
        self._write(
            "ui/NivaraApp.kt",
            "AppDestination.HiddenApplicationManagement.route\nHiddenApplicationManagementScreen()",
        )
        self._write("ui/applock/AppLockManagementViewModel.kt", "class AppLockManagementViewModel")
        self._write("ui/navigation/AppDestination.kt", "HiddenApplicationManagement")
        self._write("di/NivaraContainer.kt", "override val hiddenApplicationRepository")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_minimal_valid_hidden_architecture_passes(self) -> None:
        verify_nivara.verify_hidden_architecture(self.root)

    def test_ui_direct_storage_access_is_rejected(self) -> None:
        self._write("ui/apphide/HiddenApplicationManagementScreen.kt", "AtomicFile()")
        self._assert_rejected()

    def test_second_repository_owner_is_rejected(self) -> None:
        self._write("data/apphide/OtherHiddenRepository.kt", "class Other internal constructor() : HiddenApplicationRepository")
        self._assert_rejected()

    def test_package_manager_disabling_is_rejected(self) -> None:
        self._write(
            "ui/apphide/HiddenApplicationManagementViewModel.kt",
            "InstalledApplicationSearch.matches\nInstalledApplicationOrdering.deterministic\n"
            "InstalledApplicationOrdering.reverseAlphabetical\nsessionManager.currentState()\n"
            "setApplicationEnabledSetting(packageName, COMPONENT_ENABLED_STATE_DISABLED)",
        )
        self._assert_rejected()

    def test_secure_screen_effect_is_required(self) -> None:
        self._write("ui/apphide/HiddenApplicationManagementScreen.kt", "@Composable fun Screen() = Unit")
        self._assert_rejected()

    def test_feature_specific_authentication_is_rejected(self) -> None:
        self._write(
            "ui/apphide/HiddenApplicationManagementViewModel.kt",
            "InstalledApplicationSearch.matches\nInstalledApplicationOrdering.deterministic\n"
            "InstalledApplicationOrdering.reverseAlphabetical\nsessionManager.currentState()\n"
            "PrimaryCredentialService",
        )
        self._assert_rejected()

    def test_stage_nine_app_lock_cannot_depend_on_hidden_state(self) -> None:
        self._write("ui/applock/AppLockManagementViewModel.kt", "HiddenApplicationRepository")
        self._assert_rejected()

    def test_android_import_in_hidden_domain_is_rejected(self) -> None:
        self._write(
            "domain/apphide/HiddenApplication.kt",
            "import android.content.Context\ndata class HiddenApplication(val packageName: String)",
        )
        self._assert_rejected()

    def _assert_rejected(self) -> None:
        with self.assertRaises(SystemExit):
            verify_nivara.verify_hidden_architecture(self.root)

    def _write(self, relative: str, text: str) -> None:
        path = self.package / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
