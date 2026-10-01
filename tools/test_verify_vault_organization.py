"""Negative fixtures for Stage 16 organization architecture checks."""

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


class VaultOrganizationVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        source_root = Path(__file__).resolve().parents[1]
        shutil.copytree(source_root / "app/src/main/java", self.root / "app/src/main/java")
        shutil.copytree(source_root / "app/src/test/java", self.root / "app/src/test/java")
        shutil.copytree(source_root / "app/src/androidTest/java", self.root / "app/src/androidTest/java")
        (self.root / "docs/vault").mkdir(parents=True)
        for name in ("README.md", "stage16.md"):
            shutil.copy2(source_root / "docs/vault" / name, self.root / "docs/vault" / name)
        shutil.copy2(source_root / "README.md", self.root / "README.md")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_stage16_contracts_are_valid(self) -> None:
        verify_nivara.verify_vault_organization_architecture(self.root)

    def test_each_organization_boundary_rejects_a_mutation_and_restores_exact_bytes(self) -> None:
        model = "app/src/main/java/com/ashishkumar/nivara/domain/vault/content/VaultOrganization.kt"
        codec = "app/src/main/java/com/ashishkumar/nivara/domain/vault/content/VaultOrganizationCodec.kt"
        repository = "app/src/main/java/com/ashishkumar/nivara/data/vault/DefaultVaultOrganizationRepository.kt"
        crypto = "app/src/main/java/com/ashishkumar/nivara/data/vault/DefaultVaultRepository.kt"
        storage = "app/src/main/java/com/ashishkumar/nivara/data/vault/SafVaultContentStorage.kt"
        root_storage = "app/src/main/java/com/ashishkumar/nivara/data/vault/SafVaultStorage.kt"
        view_model = "app/src/main/java/com/ashishkumar/nivara/ui/vault/VaultViewModel.kt"
        screen = "app/src/main/java/com/ashishkumar/nivara/ui/vault/VaultScreen.kt"
        stage_docs = "docs/vault/stage16.md"
        mutations = (
            (model, "value class VaultAlbumId", "data class VaultAlbumId"),
            (repository, "VaultAlbumMutationResult.AlreadyMember", "VaultAlbumMutationResult.NotMember"),
            (model, "VaultAlbumMember.Stale", "VaultAlbumMember.Resolved"),
            (codec, "encoded.size !in HEADER_BYTES..VaultOrganizationLimits.MAX_PLAINTEXT_BYTES", "encoded.size in 0..Int.MAX_VALUE"),
            (crypto, "nivara.vault.organization-metadata.v1", "nivara.vault.content-index.v1"),
            (storage, "finalReadback.contentEquals(bytes)", "false"),
            (root_storage, 'setOf("index", "objects", "organization")', 'setOf("index", "objects")'),
            (repository, "takeLast(2).toSet()", "toSet()"),
            (view_model, "repository.createAlbum(vaultId, name, checkpoint)", "repository.createAlbum(vaultId, name, {})"),
            (model, "item.originalFilename,", "item.decryptedContent,"),
            (model, "thenBy { it.item.id.value }", "thenBy { it.item.originalFilename }"),
            (screen, "selectedItemId = it.id", "selectedItemId = VaultItemId(\"00000000000000000000000000000000\")"),
            (stage_docs, "authorization callback", "another authentication flow"),
        )
        for relative, old, new in mutations:
            with self.subTest(path=relative, mutation=old):
                self._mutate_and_reject(relative, old, new)

    def _mutate_and_reject(self, relative: str, old: str, new: str) -> None:
        path = self.root / relative
        original = path.read_bytes()
        text = original.decode("utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, 1), encoding="utf-8")
        try:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                verify_nivara.verify_vault_organization_architecture(self.root)
        finally:
            path.write_bytes(original)
            self.assertEqual(original, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
