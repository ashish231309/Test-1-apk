"""Negative fixtures for Stage 18 recovery, reconnect, and key-lifecycle architecture rules."""

from __future__ import annotations

import contextlib
import io
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_nivara  # noqa: E402


class VaultRecoveryVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        source_root = Path(__file__).resolve().parents[1]
        for relative in (
            "app/src/main/java/com/ashishkumar/nivara/domain/vault",
            "app/src/main/java/com/ashishkumar/nivara/domain/security",
            "app/src/main/java/com/ashishkumar/nivara/data/vault",
            "app/src/main/java/com/ashishkumar/nivara/data/security",
            "app/src/main/java/com/ashishkumar/nivara/ui/vault",
            "app/src/main/java/com/ashishkumar/nivara/di",
        ):
            shutil.copytree(source_root / relative, self.root / relative)
        for relative in (
            "README.md",
            "docs/vault/README.md",
            "docs/vault/stage18.md",
            "docs/vault/stage18-architecture-audit.md",
        ):
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source_root / relative, destination)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_valid_stage18_contracts_pass(self) -> None:
        verify_nivara.verify_vault_recovery_architecture(self.root)

    def test_android_dependency_in_recovery_domain_is_rejected(self) -> None:
        self._mutate_and_reject(
            "app/src/main/java/com/ashishkumar/nivara/domain/vault/VaultRecoveryContracts.kt",
            "package ",
            "import android.net.Uri\npackage ",
        )

    def test_recovery_secret_derivation_is_rejected(self) -> None:
        self._mutate_and_reject(
            "app/src/main/java/com/ashishkumar/nivara/data/vault/DefaultVaultRepository.kt",
            "KeyProtection.RECOVERY",
            "KeyProtection.CREDENTIAL_DERIVED",
        )

    def test_initialization_during_recovery_is_rejected(self) -> None:
        self._mutate_and_reject(
            "app/src/main/java/com/ashishkumar/nivara/data/vault/DefaultVaultRecoveryRepository.kt",
            "commitRecoveryRecordAtomically(pending.vaultId, pending.recordBytes)",
            "initializeAtomically(pending.recordBytes)",
        )

    def test_persisting_recovery_code_in_local_key_store_is_rejected(self) -> None:
        self._mutate_and_reject(
            "app/src/main/java/com/ashishkumar/nivara/data/vault/AndroidVaultKeyAccessStore.kt",
            "wrapped_content_key",
            "recovery_code",
        )

    def test_automatic_sharing_of_recovery_code_is_rejected(self) -> None:
        self._mutate_and_reject(
            "app/src/main/java/com/ashishkumar/nivara/ui/vault/VaultRecoveryContent.kt",
            "Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {",
            "Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { ClipboardManager()",
        )

    def test_recovery_session_bypass_is_rejected(self) -> None:
        self._mutate_and_reject(
            "app/src/main/java/com/ashishkumar/nivara/ui/vault/VaultViewModel.kt",
            "sessionManager.currentState() is SessionState.Authenticated && sessionManager.mayAccessSensitiveContent()",
            "sessionManager.currentState() is SessionState.Unauthenticated && sessionManager.mayAccessSensitiveContent()",
        )

    def test_recovery_record_rotation_is_rejected(self) -> None:
        self._mutate_and_reject(
            "app/src/main/java/com/ashishkumar/nivara/data/vault/SafVaultStorage.kt",
            "VaultRecoveryFile.Missing -> Unit",
            "VaultRecoveryFile.Missing, is VaultRecoveryFile.Present -> Unit",
        )

    def test_recovery_code_saved_to_restorable_ui_state_is_rejected(self) -> None:
        self._mutate_and_reject(
            "app/src/main/java/com/ashishkumar/nivara/ui/vault/VaultRecoveryContent.kt",
            'var code by remember { mutableStateOf("") }',
            'var code by rememberSaveable { mutableStateOf("") }',
        )

    def test_recovery_ui_receiving_a_raw_saf_uri_is_rejected(self) -> None:
        self._mutate_and_reject(
            "app/src/main/java/com/ashishkumar/nivara/ui/vault/VaultRecoveryContent.kt",
            "package ",
            "import android.net.Uri\npackage ",
        )

    def test_persisting_plaintext_vault_key_is_rejected(self) -> None:
        self._mutate_and_reject(
            "app/src/main/java/com/ashishkumar/nivara/data/vault/AndroidVaultKeyAccessStore.kt",
            "Base64.getEncoder().encodeToString(wrapper)",
            "Base64.getEncoder().encodeToString(contentKey)",
        )

    def _mutate_and_reject(self, relative: str, old: str, new: str) -> None:
        path = self.root / relative
        original = path.read_bytes()
        text = original.decode("utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, 1), encoding="utf-8")
        try:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                verify_nivara.verify_vault_recovery_architecture(self.root)
        finally:
            path.write_bytes(original)
        self.assertEqual(original, path.read_bytes())
