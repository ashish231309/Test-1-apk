"""Negative fixtures for Stage 17 trash/restore architectural rules."""

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


class VaultTrashVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        source_root = Path(__file__).resolve().parents[1]
        for relative in (
            "app/src/main/java",
            "app/src/test/java",
            "app/src/androidTest/java",
        ):
            shutil.copytree(source_root / relative, self.root / relative)
        (self.root / "docs/vault").mkdir(parents=True)
        for name in ("README.md", "stage17.md"):
            shutil.copy2(source_root / "docs/vault" / name, self.root / "docs/vault" / name)
        shutil.copy2(source_root / "README.md", self.root / "README.md")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_stage17_contracts_are_valid(self) -> None:
        verify_nivara.verify_vault_trash_architecture(self.root)

    def test_each_security_boundary_rejects_mutation_and_restores_exact_bytes(self) -> None:
        item = "app/src/main/java/com/ashishkumar/nivara/domain/vault/content/VaultItem.kt"
        codec = "app/src/main/java/com/ashishkumar/nivara/domain/vault/content/VaultIndexCodec.kt"
        contracts = "app/src/main/java/com/ashishkumar/nivara/domain/vault/content/VaultIndexContracts.kt"
        repo = "app/src/main/java/com/ashishkumar/nivara/data/vault/DefaultVaultIndexRepository.kt"
        storage = "app/src/main/java/com/ashishkumar/nivara/data/vault/SafVaultContentStorage.kt"
        importer = "app/src/main/java/com/ashishkumar/nivara/data/vault/DefaultVaultImportRepository.kt"
        organization = "app/src/main/java/com/ashishkumar/nivara/domain/vault/content/VaultOrganization.kt"
        view_model = "app/src/main/java/com/ashishkumar/nivara/ui/vault/VaultViewModel.kt"
        screen = "app/src/main/java/com/ashishkumar/nivara/ui/vault/VaultScreen.kt"
        content_codec = "app/src/main/java/com/ashishkumar/nivara/domain/vault/content/VaultIndexCodec.kt"
        stage_docs = "docs/vault/stage17.md"
        mutations = (
            (item, "enum class VaultItemLifecycle", "enum class VaultItemLifecycleRemoved"),
            (item, "contentDigestSha256: ByteArray? = null", "discardedDigest: ByteArray? = null"),
            (codec, "version != LEGACY_VERSION", "version == LEGACY_VERSION"),
            (codec, "input.available() != 0", "input.available() == 0"),
            (repo, "sameItemMetadata(changedItem, verifiedItem)", "true"),
            (repo, "pruneAfterVerifiedWrite(generation, authorizationCheckpoint)", "pruneBeforeVerification(generation, authorizationCheckpoint)", 1),
            (storage, "override suspend fun pruneIndexFiles", "override suspend fun pruneAllIndexFiles"),
            (importer, "DigestInputStream(source, digest)", "InputStream(source, digest)"),
            (organization, "fun search(index: VaultIndexRead, query: String): VaultSearchState = search(index, query, includeTrashed = false)",
             "fun search(index: VaultIndexRead, query: String): VaultSearchState = search(index, query, includeTrashed = true)"),
            (view_model, "indexRepository.moveToTrash(ready.vaultId, itemId) { hasValidSession() }",
             "indexRepository.moveToTrash(ready.vaultId, itemId) { true }"),
            (screen, "private fun TrashItemRow(", "private fun TrashItemRow(onOpenItem: (VaultItem) -> Unit,"),
            (screen, "Text(\"Restore\")", "Text(\"Permanent delete\")"),
            (content_codec, "const val VERSION = 1\n    const val ALGORITHM_AES_256_GCM", "const val VERSION = 2\n    const val ALGORITHM_AES_256_GCM"),
            (stage_docs, "no permanent deletion", "permanent deletion is supported"),
        )
        for mutation in mutations:
            relative, old, new = mutation[:3]
            occurrence = mutation[3] if len(mutation) == 4 else 0
            with self.subTest(path=relative, mutation=old, occurrence=occurrence):
                self._mutate_and_reject(relative, old, new, occurrence)

    def _mutate_and_reject(self, relative: str, old: str, new: str, occurrence: int = 0) -> None:
        path = self.root / relative
        original = path.read_bytes()
        text = original.decode("utf-8")
        self.assertGreater(text.count(old), occurrence)
        start = 0
        for _ in range(occurrence):
            start = text.index(old, start) + len(old)
        offset = text.index(old, start)
        path.write_text(text[:offset] + new + text[offset + len(old):], encoding="utf-8")
        try:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                verify_nivara.verify_vault_trash_architecture(self.root)
        finally:
            path.write_bytes(original)
            self.assertEqual(original, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
