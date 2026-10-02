"""Negative fixtures for each Stage 13 vault boundary enforced by verify_nivara.py."""

from __future__ import annotations

import contextlib
import io
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_nivara  # noqa: E402


ANDROID_NS = "http://schemas.android.com/apk/res/android"
PACKAGE = "app/src/main/java/com/ashishkumar/nivara"


class VaultVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        source_root = Path(__file__).resolve().parents[1]
        for relative in (
            f"{PACKAGE}/domain/vault",
            f"{PACKAGE}/domain/security",
            f"{PACKAGE}/data/vault",
            f"{PACKAGE}/ui/vault",
        ):
            shutil.copytree(source_root / relative, self.root / relative)
        for relative in (
            f"{PACKAGE}/di/NivaraContainer.kt",
            f"{PACKAGE}/data/security/JcaAesGcmEncryption.kt",
            f"{PACKAGE}/MainActivity.kt",
            f"{PACKAGE}/ui/NivaraApp.kt",
            f"{PACKAGE}/ui/credentials/CredentialHomeScreen.kt",
            f"{PACKAGE}/ui/navigation/AppDestination.kt",
            "app/src/main/AndroidManifest.xml",
            "README.md",
            "docs/vault/README.md",
            "docs/vault/stage14.md",
            "docs/vault/stage15.md",
            "docs/vault/stage18.md",
            "docs/vault/stage18-architecture-audit.md",
            "app/src/test/java/com/ashishkumar/nivara/domain/vault/content/VaultContentClassifierTest.kt",
            "app/src/test/java/com/ashishkumar/nivara/ui/vault/VaultItemViewerViewModelTest.kt",
            "app/src/test/java/com/ashishkumar/nivara/data/vault/DefaultVaultRepositoryTest.kt",
            "app/src/androidTest/java/com/ashishkumar/nivara/data/vault/AndroidVaultImageDecoderInstrumentedTest.kt",
            "app/src/androidTest/java/com/ashishkumar/nivara/data/vault/AndroidVaultContentPresentationInstrumentedTest.kt",
        ):
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source_root / relative, destination)
        self.manifest = ET.parse(self.root / "app/src/main/AndroidManifest.xml").getroot()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_valid_vault_boundaries_pass(self) -> None:
        verify_nivara.verify_vault_manifest(self.manifest)
        verify_nivara.verify_vault_architecture(self.root, self.manifest)
        verify_nivara.verify_vault_recovery_architecture(self.root)
        verify_nivara.verify_vault_content_architecture(self.root)
        verify_nivara.verify_vault_presentation_architecture(self.root)

    def test_stage15_presentation_guards_reject_each_individual_mutation(self) -> None:
        mutations = (
            (f"{PACKAGE}/domain/vault/content/VaultContentPresentation.kt",
             "classify(item: VaultItem): VaultContentClassification = classify(item.originalMimeType)",
             "classify(item: VaultItem): VaultContentClassification = classify(item.originalFilename)", False),
            (f"{PACKAGE}/domain/vault/content/VaultContentPresentation.kt",
             "VaultContentClassification(VaultContentCategory.OTHER, null, VaultContentClassification.PreviewKind.UNSUPPORTED)",
             "VaultContentClassification(VaultContentCategory.IMAGE, null, VaultContentClassification.PreviewKind.UNSUPPORTED)", False),
            (f"{PACKAGE}/domain/vault/content/VaultContentPresentation.kt",
             "val mime = rawMimeType?", "val mime = rawMimeType?.also { it.substringAfterLast('.') }?", False),
            (f"{PACKAGE}/domain/vault/content/VaultContentPresentation.kt",
             "package ", "// data class VaultItem\npackage ", False),
            (f"{PACKAGE}/domain/vault/content/VaultContentPresentation.kt",
             "package ", "import android.net.Uri\npackage ", False),
            (f"{PACKAGE}/domain/vault/content/VaultContentPresentationGateway.kt",
             "package ", "// Cipher must remain behind the platform boundary\npackage ", False),
            (f"{PACKAGE}/data/vault/AndroidVaultContentPresentationRepository.kt",
             "crypto.decryptObjectToQuarantine", "crypto.verifyObject", True),
            (f"{PACKAGE}/data/vault/AndroidVaultContentPresentationRepository.kt",
             "MAX_TEXT_BYTES", "UNBOUNDED_TEXT_BYTES", True),
            (f"{PACKAGE}/data/vault/AndroidVaultContentPresentationRepository.kt",
             "package ", "// FileOutputStream plaintext staging\npackage ", False),
            (f"{PACKAGE}/data/vault/AndroidVaultContentPresentationRepository.kt",
             "return@withContext VaultPresentationOpenResult.Unsupported",
             "return@withLock VaultPresentationOpenResult.Failed", False),
            (f"{PACKAGE}/data/vault/AndroidVaultContentPresentationRepository.kt",
             "sessions.mayAccessSensitiveContent()", "sessions.mayAccessSensitiveContentDisabled()", True),
            (f"{PACKAGE}/data/vault/AndroidVaultContentPresentationRepository.kt",
             "previousSession != currentSession", "previousSession == currentSession", False),
            (f"{PACKAGE}/data/vault/AndroidVaultContentPresentationRepository.kt",
             "package ", "// sessions.lockNow()\npackage ", False),
            (f"{PACKAGE}/ui/vault/VaultItemViewerViewModel.kt",
             "onActivityPaused() = close()", "onActivityPaused() = Unit", False),
            (f"{PACKAGE}/ui/vault/VaultItemViewerViewModel.kt",
             "previous != null && previous != current", "previous == current", False),
            (f"{PACKAGE}/ui/vault/VaultItemViewerViewModel.kt",
             "data class VaultItemViewerUiState(", "data class VaultItemViewerUiState(\n    val stream: java.io.InputStream? = null,", False),
            (f"{PACKAGE}/ui/vault/VaultScreen.kt",
             "model.close()", "model.refreshPlayback()", False),
            (f"{PACKAGE}/ui/vault/VaultScreen.kt",
             "SecureScreenEffect()", "SecureEffectRemoved()", False),
            (f"{PACKAGE}/ui/vault/VaultScreen.kt",
             "VaultContentClassifier.classify", "VaultContentClassifier.guessFromFilename", False),
            (f"{PACKAGE}/ui/NivaraApp.kt",
             "vaultContentPresentationGateway", "unwiredPresentationGateway", True),
            ("app/src/androidTest/java/com/ashishkumar/nivara/data/vault/AndroidVaultImageDecoderInstrumentedTest.kt",
             "wideImageIsSampledToTheConfiguredRenderDimension", "wideImageSamplingTestRemoved", False),
            ("docs/vault/stage15.md", "No plaintext cache is used", "A plaintext cache is used", False),
            ("README.md", "docs/vault/stage15.md", "docs/vault/stage14.md", True),
        )
        for relative, old, new, all_matches in mutations:
            with self.subTest(path=relative, old=old):
                self._mutate_presentation_and_reject(relative, old, new, all_matches)

    def test_broad_storage_permission_is_rejected(self) -> None:
        ET.SubElement(self.manifest, "uses-permission", {
            f"{{{ANDROID_NS}}}name": "android.permission.MANAGE_EXTERNAL_STORAGE",
        })
        self._assert_manifest_rejected()

    def test_legacy_broad_storage_permissions_are_rejected(self) -> None:
        for permission in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"):
            with self.subTest(permission=permission):
                manifest = ET.parse(self.root / "app/src/main/AndroidManifest.xml").getroot()
                ET.SubElement(manifest, "uses-permission", {
                    f"{{{ANDROID_NS}}}name": f"android.permission.{permission}",
                })
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    verify_nivara.verify_vault_manifest(manifest)

    def test_broadened_package_visibility_is_rejected(self) -> None:
        queries = self.manifest.find("queries")
        ET.SubElement(queries, "package", {f"{{{ANDROID_NS}}}name": "com.example.other"})
        self._assert_manifest_rejected()

    def test_duplicate_vault_component_is_rejected(self) -> None:
        application = self.manifest.find("application")
        ET.SubElement(application, "service", {
            f"{{{ANDROID_NS}}}name": ".VaultBackgroundService",
            f"{{{ANDROID_NS}}}exported": "false",
        })
        self._assert_manifest_rejected()

    def test_android_type_in_domain_is_rejected(self) -> None:
        self._mutate_and_reject(f"{PACKAGE}/domain/vault/VaultContracts.kt", "package ", "import android.net.Uri\npackage ")

    def test_platform_storage_type_in_domain_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/domain/vault/VaultContracts.kt",
            "interface VaultRepository {",
            "val forbidden: java.io.File?\ninterface VaultRepository {",
        )

    def test_missing_distinct_failure_state_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/domain/vault/VaultContracts.kt",
            "data object CorruptMetadata : VaultStatus",
            "data object CorruptMarker : VaultStatus",
        )

    def test_unsupported_version_remains_a_distinct_vault_status(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/domain/vault/VaultContracts.kt",
            "data class UnsupportedVersion(val component: VaultFormatComponent, val version: Int?) : VaultStatus",
            "data class UnknownVersion(val component: VaultFormatComponent, val version: Int?) : VaultStatus",
        )

    def test_each_vault_status_has_a_distinct_contract(self) -> None:
        mutations = (
            ("data object RootNotSelected : VaultStatus", "data object Missing : VaultStatus"),
            ("data object NotInitialized : VaultStatus", "data object Empty : VaultStatus"),
            ("data class Ready(val vaultId: VaultId) : VaultStatus", "data class ReadyState(val vaultId: VaultId) : VaultStatus"),
            ("data class Unavailable(val reason: VaultUnavailableReason) : VaultStatus", "data class Offline(val reason: VaultUnavailableReason) : VaultStatus"),
            ("data object AccessDenied : VaultStatus", "data object Denied : VaultStatus"),
            ("data object InvalidStructure : VaultStatus", "data object InvalidVault : VaultStatus"),
            ("data class InitializationFailed(val reason: VaultInitializationFailure) : VaultStatus", "data class InitError(val reason: VaultInitializationFailure) : VaultStatus"),
        )
        for old, new in mutations:
            with self.subTest(state=old):
                self._mutate_and_reject(f"{PACKAGE}/domain/vault/VaultContracts.kt", old, new)

    def test_each_initialization_result_has_a_distinct_contract(self) -> None:
        mutations = (
            ("data class Initialized(val vaultId: VaultId) : VaultInitializationResult", "data class Created(val vaultId: VaultId) : VaultInitializationResult"),
            ("data object AlreadyInitialized : VaultInitializationResult", "data object PreviouslyCreated : VaultInitializationResult"),
            ("data object RootNotSelected : VaultInitializationResult", "data object NoRoot : VaultInitializationResult"),
            ("data class Unavailable(val reason: VaultUnavailableReason) : VaultInitializationResult", "data class Offline(val reason: VaultUnavailableReason) : VaultInitializationResult"),
            ("data object AccessDenied : VaultInitializationResult", "data object Denied : VaultInitializationResult"),
            ("data object InvalidStructure : VaultInitializationResult", "data object InvalidVault : VaultInitializationResult"),
            ("data class Failed(val reason: VaultInitializationFailure) : VaultInitializationResult", "data class InitError(val reason: VaultInitializationFailure) : VaultInitializationResult"),
        )
        for old, new in mutations:
            with self.subTest(result=old):
                self._mutate_and_reject(f"{PACKAGE}/domain/vault/VaultContracts.kt", old, new)

    def test_corrupt_metadata_remains_a_distinct_initialization_result(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/domain/vault/VaultContracts.kt",
            "data object CorruptMetadata : VaultInitializationResult",
            "data object InvalidMetadata : VaultInitializationResult",
        )

    def test_unsupported_version_remains_a_distinct_initialization_result(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/domain/vault/VaultContracts.kt",
            "data class UnsupportedVersion(val component: VaultFormatComponent, val version: Int?) : VaultInitializationResult",
            "data class UnknownVersion(val component: VaultFormatComponent, val version: Int?) : VaultInitializationResult",
        )

    def test_direct_uri_or_file_access_in_ui_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/ui/vault/VaultScreen.kt",
            "package ",
            "import android.net.Uri\npackage ",
        )

    def test_raw_filesystem_path_type_in_ui_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/ui/vault/VaultScreen.kt",
            "package ",
            "import java.io.File\npackage ",
        )

    def test_direct_document_io_in_ui_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/ui/vault/VaultScreen.kt",
            "package ",
            "import android.provider.DocumentsContract\npackage ",
        )

    def test_duplicate_crypto_implementation_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/data/vault/DefaultVaultRepository.kt",
            "package ",
            "import javax.crypto.Cipher\npackage ",
        )

    def test_missing_stage_two_crypto_binding_is_rejected(self) -> None:
        self._mutate_all_and_reject(
            f"{PACKAGE}/data/vault/DefaultVaultRepository.kt",
            "KeyWrappingService",
            "OtherWrapper",
        )

    def test_credential_derivation_in_storage_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/data/vault/SafVaultStorage.kt",
            "package ",
            "// CredentialKeyDeriver\npackage ",
        )

    def test_parallel_session_manager_in_storage_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/data/vault/SafVaultStorage.kt",
            "package ",
            "// SessionManager\npackage ",
        )

    def test_hidden_applock_or_camouflage_dependency_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/data/vault/DefaultVaultRepository.kt",
            "package ",
            "// ProtectedApplicationRepository\npackage ",
        )

    def test_logging_or_network_use_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/data/vault/SafVaultStorage.kt",
            "package ",
            "// Log.d(\"vault\", uri)\npackage ",
        )

    def test_automatic_public_or_internal_fallback_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/data/vault/SafVaultStorage.kt",
            "package ",
            "private const val FORBIDDEN_ROOT = \"/storage/emulated/0\"\npackage ",
        )

    def test_failure_must_not_collapse_into_not_initialized(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/data/vault/DefaultVaultRepository.kt",
            "private suspend fun inspectLocked()",
            "catch (failure: Exception) { return VaultStatus.NotInitialized }\n\nprivate suspend fun inspectLocked()",
        )

    def test_saf_must_take_persisted_permission_and_reject_new_root(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/data/vault/VaultRootSelectionHandler.kt",
            "takePersistableUriPermission",
            "persistPermissionBySomeOtherMeans",
        )

    def test_metadata_commit_must_rename_and_read_back(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/data/vault/SafVaultStorage.kt",
            "DocumentsContract.renameDocument",
            "DocumentsContract.copyDocument",
        )

    def test_session_gate_is_required(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/ui/vault/VaultViewModel.kt",
            "sessionManager.mayAccessSensitiveContent()",
            "true",
        )

    def test_root_configuration_must_recheck_the_existing_session_gate(self) -> None:
        self._mutate_all_and_reject(
            f"{PACKAGE}/ui/vault/VaultRootConfigurationScreen.kt",
            "sessionManager.mayAccessSensitiveContent()",
            "true",
        )

    def test_second_ui_authentication_flow_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/ui/vault/VaultScreen.kt",
            "package ",
            "// authenticatePrimary()\npackage ",
        )

    def test_existing_navigation_destination_is_required(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/ui/NivaraApp.kt",
            "VaultScreen(",
            "RemovedScreen(",
        )

    def test_initialization_result_must_preserve_corrupt_metadata(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/data/vault/DefaultVaultRepository.kt",
            "VaultInitializationResult.CorruptMetadata",
            "VaultInitializationResult.InvalidStructure",
        )

    def test_view_model_must_preserve_unsupported_version_result(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/ui/vault/VaultViewModel.kt",
            "is VaultInitializationResult.UnsupportedVersion -> updateStatus(",
            "is VaultInitializationResult.InvalidStructure -> updateStatus(",
        )

    def test_reserved_data_directory_must_be_validated_as_empty(self) -> None:
        self._mutate_all_and_reject(
            f"{PACKAGE}/data/vault/DefaultVaultRepository.kt",
            "unexpectedDataEntries",
            "ignoredDataEntries",
        )

    def test_provider_rename_capability_must_be_checked(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/data/vault/SafVaultStorage.kt",
            "FLAG_SUPPORTS_RENAME",
            "FLAG_SUPPORTS_OTHER_OPERATION",
        )

    def test_explicit_root_configuration_destination_is_required(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/ui/NivaraApp.kt",
            "VaultRootConfigurationScreen(",
            "RemovedRootScreen(",
        )
        self._mutate_all_and_reject(
            f"{PACKAGE}/ui/NivaraApp.kt",
            "AppDestination.VaultRootConfiguration.route",
            "AppDestination.Vault.route",
        )

    def test_selection_result_must_not_leak_uri_to_ui(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/MainActivity.kt",
            "rootSelectionResult = vaultRootSelectionResult",
            "rootSelectionResult = null",
        )

    def test_di_must_use_shared_crypto_services(self) -> None:
        self._mutate_all_and_reject(
            f"{PACKAGE}/di/NivaraContainer.kt",
            "keyWrapping = keyWrapping",
            "keyWrapping = OtherWrapper()",
        )

    def test_duplicate_repository_binding_is_rejected(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/di/NivaraContainer.kt",
            "override val vaultRepository: VaultRepository by lazy {",
            "DefaultVaultRepository(\n    override val vaultRepository: VaultRepository by lazy {",
        )

    def test_versioned_metadata_marker_is_required(self) -> None:
        self._mutate_and_reject(
            f"{PACKAGE}/domain/vault/VaultMetadataCodec.kt",
            "const val CURRENT_VERSION = 1",
            "const val FORMAT = 1",
        )

    def test_documentation_scope_and_limitations_are_required(self) -> None:
        self._mutate_all_and_reject("docs/vault/README.md", "Stage 14", "later stage")
        self._mutate_all_and_reject(
            "docs/vault/README.md",
            "root-configuration destination",
            "settings view",
        )

    def test_main_readme_vault_status_is_required(self) -> None:
        self._mutate_and_reject("README.md", "External encrypted vault", "Vault details removed")

    def test_each_vault_status_is_rendered_by_the_screen(self) -> None:
        states = (
            "VaultStatus.RootNotSelected",
            "VaultStatus.NotInitialized",
            "VaultStatus.Ready",
            "VaultStatus.Unavailable",
            "VaultStatus.AccessDenied",
            "VaultStatus.CorruptMetadata",
            "VaultStatus.UnsupportedVersion",
            "VaultStatus.InvalidStructure",
            "VaultStatus.InitializationFailed",
        )
        for state in states:
            with self.subTest(state=state):
                self._mutate_all_and_reject(
                    f"{PACKAGE}/ui/vault/VaultScreen.kt",
                    state,
                    "VaultStatus.MissingState",
                )

    def test_ui_must_render_real_states(self) -> None:
        path = self.root / f"{PACKAGE}/ui/vault/VaultScreen.kt"
        original = path.read_bytes()
        try:
            path.write_bytes(b"")
            self._assert_architecture_rejected()
        finally:
            path.write_bytes(original)
            self.assertEqual(original, path.read_bytes())

    def test_each_stage14_content_rule_rejects_an_independent_negative_fixture(self) -> None:
        item = f"{PACKAGE}/domain/vault/content/VaultItem.kt"
        codec = f"{PACKAGE}/domain/vault/content/VaultIndexCodec.kt"
        contracts = f"{PACKAGE}/domain/vault/content/VaultIndexContracts.kt"
        crypto_contract = f"{PACKAGE}/domain/security/CryptoContracts.kt"
        crypto_impl = f"{PACKAGE}/data/security/JcaAesGcmEncryption.kt"
        repository = f"{PACKAGE}/data/vault/DefaultVaultRepository.kt"
        index_repository = f"{PACKAGE}/data/vault/DefaultVaultIndexRepository.kt"
        importer = f"{PACKAGE}/data/vault/DefaultVaultImportRepository.kt"
        storage = f"{PACKAGE}/data/vault/SafVaultContentStorage.kt"
        picker = f"{PACKAGE}/data/vault/VaultSourceDocumentHandler.kt"
        view_model = f"{PACKAGE}/ui/vault/VaultViewModel.kt"
        screen = f"{PACKAGE}/ui/vault/VaultScreen.kt"
        activity = f"{PACKAGE}/MainActivity.kt"
        container = f"{PACKAGE}/di/NivaraContainer.kt"
        mutations = (
            (item, "package ", "import android.net.Uri\npackage ", False),
            (item, "val originalFilename: String", "val displayFilename: String", False),
            (item, "[0-9a-f]{32}", "[0-9a-f]{31}", False),
            (codec, "item.encryptedItemKey", "item.rawKey", False),
            (codec, "MAX_INDEX_BYTES = 8 * 1024 * 1024", "MAX_INDEX_BYTES = 1024", False),
            (codec, "input.available() != 0", "input.available() == 0", False),
            (repository, "content-index.v1", "metadata.v1", False),
            (contracts, "interface VaultContentCrypto {", "interface OtherCryptoBridge {", False),
            (contracts, "package ", "import com.ashishkumar.nivara.domain.security.Aes256Key\npackage ", False),
            (crypto_contract, "interface StreamingAuthenticatedEncryption", "interface OneShotOnly", False),
            (crypto_impl, "cipher.doFinal()", "cipher.finishStream()", False),
            (codec, "0x4f) // NVCO", "0x50) // NVCO", False),
            (repository, "random.generateGcmNonce()", "random.nextNonce()", False),
            (picker, "Intent.ACTION_OPEN_DOCUMENT", "Intent.ACTION_GET_CONTENT", False),
            (picker, "ConcurrentHashMap<VaultSourceSelectionId, Uri>", "HashMap<VaultSourceSelectionId, Uri>", False),
            (picker, "package ", "// FLAG_GRANT_PERSISTABLE_URI_PERMISSION\npackage ", False),
            (storage, "objectName(id: VaultItemId)", "objectName(name: String)", False),
            (storage, "index-%020d.vxi", "index-%010d.vxi", False),
            (storage, "VaultObjectCommitResult.AuthorizationExpired", "VaultObjectCommitResult.WriteFailed", False),
            (index_repository, "VaultIndexRead.ObjectsWithoutIndex", "VaultIndexRead.Missing", True),
            (index_repository, "files.generations.maxOrNull()", "files.generations.minOrNull()", False),
            (importer, "crypto.verifyObject", "crypto.skipVerification", False),
            (importer, "validMetadata(metadata)", "true", False),
            (importer, "random.generateBytes(VaultItemId.BYTE_COUNT)", "random.generateBytes(8)", False),
            (importer, "completed.clear()", "completed.invalidate()", True),
            (importer, "package ", "// readAllBytes()\npackage ", False),
            (view_model, "VaultImportResult.AuthorizationExpired", "VaultImportResult.Failed", False),
            (activity, "VaultSourcePickerContract", "RemovedSourcePicker", True),
            (screen, "onChooseSource", "onOpenMedia", True),
            (screen, "VaultIndexRead.Corrupt", "VaultIndexRead.Missing", False),
            (importer, "private val mutex = Mutex()", "private val lock = Mutex()", False),
            (container, "DefaultVaultIndexRepository(", "OtherIndexRepository(", False),
            ("docs/vault/stage14.md", "64 KiB", "unbounded", False),
            ("README.md", "file import", "content import", False),
            (screen, "package ", "class VaultTrashManager\npackage ", False),
            (importer, "package ", "// resolver.delete(sourceUri)\npackage ", False),
            (importer, "package ", "// Log.d(\"vault\", filename)\npackage ", False),
        )
        for relative, old, new, all_matches in mutations:
            with self.subTest(path=relative, old=old):
                self._mutate_content_and_reject(relative, old, new, all_matches)

    def _assert_presentation_architecture_rejected(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            verify_nivara.verify_vault_presentation_architecture(self.root)

    def _mutate_presentation_and_reject(self, relative: str, old: str, new: str, all_matches: bool = False) -> None:
        path = self.root / relative
        original = path.read_bytes()
        text = original.decode("utf-8")
        self.assertIn(old, text)
        changed = text.replace(old, new) if all_matches else text.replace(old, new, 1)
        path.write_text(changed, encoding="utf-8")
        try:
            self._assert_presentation_architecture_rejected()
        finally:
            path.write_bytes(original)
            self.assertEqual(original, path.read_bytes())

    def _assert_content_architecture_rejected(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            verify_nivara.verify_vault_content_architecture(self.root)

    def _mutate_content_and_reject(self, relative: str, old: str, new: str, all_matches: bool = False) -> None:
        path = self.root / relative
        original = path.read_bytes()
        text = original.decode("utf-8")
        self.assertIn(old, text)
        changed = text.replace(old, new) if all_matches else text.replace(old, new, 1)
        path.write_text(changed, encoding="utf-8")
        try:
            self._assert_content_architecture_rejected()
        finally:
            path.write_bytes(original)
            self.assertEqual(original, path.read_bytes())

    def _assert_manifest_rejected(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            verify_nivara.verify_vault_manifest(self.manifest)

    def _assert_architecture_rejected(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            verify_nivara.verify_vault_architecture(self.root, self.manifest)
            verify_nivara.verify_vault_recovery_architecture(self.root)
            verify_nivara.verify_vault_content_architecture(self.root)

    def _mutate_all_and_reject(self, relative: str, old: str, new: str) -> None:
        path = self.root / relative
        original = path.read_bytes()
        text = original.decode("utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new), encoding="utf-8")
        try:
            self._assert_architecture_rejected()
        finally:
            path.write_bytes(original)
            self.assertEqual(original, path.read_bytes())

    def _mutate_and_reject(self, relative: str, old: str, new: str) -> None:
        path = self.root / relative
        original = path.read_bytes()
        text = original.decode("utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, 1), encoding="utf-8")
        try:
            self._assert_architecture_rejected()
        finally:
            path.write_bytes(original)
            self.assertEqual(original, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
