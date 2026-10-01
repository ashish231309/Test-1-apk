# Stage 16 — Albums, Search, Sorting & Vault Organization

Stage 16 extends the existing Stage 13–15 vault screen and content architecture. It does not add a vault root, key, credential, session, item database, object reader, or viewer route. Album metadata is separate from item metadata: the authenticated Stage 14 index remains the only authority for each `VaultItem` name, MIME type, size, import time, and stable `VaultItemId`.

## Domain contracts and semantics

`VaultAlbumId` is a fixed-width 128-bit random identifier generated from the shared `SecureRandomSource`; names are never identities or paths. `VaultAlbum` stores only its ID, a validated name, and ordered `VaultItemId` references. Albums may be empty, names may repeat, and an item may belong to multiple albums. Membership is ordered by insertion; appending a member preserves existing order. Adding a member already present returns the typed `AlreadyMember` result without writing another generation. A stale reference remains part of the album until a user explicitly removes that reference. Deleting an album removes only its organization metadata; deleting/removing a membership never deletes or changes an encrypted vault item.

Album names are trimmed and NFC-normalized. Empty names, invalid UTF-8, controls, and names longer than 100 Unicode code points or 400 UTF-8 bytes are rejected. Album lists are deterministic: normalized case-insensitive name, then stable album ID.

## Search and sort

Search normalizes to NFC, lowercases with `Locale.ROOT`, and collapses Unicode whitespace. It examines only the authenticated index's original filename, MIME type, and the existing `VaultContentClassifier` category/preview kind. It never opens an object or decrypts content. Queries are bounded to 256 Unicode code points and reject malformed UTF-16/UTF-8 input. The query is transient UI state; there is no network, ranking, analytics, or persisted search history. Empty query, matches, no matches, invalid/oversized query, missing/unavailable/unreadable index, unsupported index version, and unavailable vault are distinct typed states. A failed index read is never presented as zero results. Album searches cover currently resolved indexed members; stale IDs remain visible as stale references and are not searched because their authenticated item metadata is unavailable.

Sorting supports name, original size, import time, and classified type, ascending or descending. `VaultItemId` is the stable ascending tie-breaker in either direction. The default `DEFAULT` option returns the authenticated index's existing order without resorting.

## Organization record format and encryption

The Android-free `VaultOrganizationCodec` uses a strict version-1 bounded record containing only vault ID, organization generation, album IDs/names, and item-ID references. Bounds are 1,000 albums, 20,000 references per album, 100,000 references total, and 8 MiB plaintext. Decoding rejects duplicate IDs/memberships, noncanonical album ordering/names, malformed UTF-8, out-of-range counts, unknown trailing data, and unsupported versions. A distinct outer `NVOE` envelope binds the generation; the storage filename and AES-GCM AAD must agree with it.

`DefaultVaultRepository` implements the existing `VaultContentCrypto` bridge for organization records. It reuses the existing vault content key and Stage 2 `AuthenticatedEncryption`, with the explicit `nivara.vault.organization-metadata.v1` purpose and vault-ID/generation binding. The item key hierarchy is unchanged; no second key or encryption implementation is introduced. The SAF adapter stores encrypted generation files under the existing selected vault root at `data/organization/`; `SafVaultStorage` and `SafVaultContentStorage` both validate this directory through their strict allow-lists.

## Commit, readback, and failure behavior

Each update serializes through a repository mutex, checks the existing `SessionManager` authorization callback, writes the next immutable organization generation through a same-directory pending document, verifies exact bytes before and after rename, and re-reads, decrypts, strictly decodes, and compares the committed snapshot before reporting success. The previously verified generation is not overwritten. After successful authenticated readback, the repository retains the latest two generations and then prunes older files. A new generation that fails authenticated readback is discarded without deleting previous committed generations; if cleanup itself fails, the newest state remains explicitly corrupt/unavailable rather than falling back to an older record or becoming empty. Provider failures, access denial, unavailable vault keys, corruption, and unsupported versions remain distinct from an empty organization snapshot.

SAF providers do not expose a portable `fsync` or power-loss guarantee. The protocol verifies the bytes observed through the provider and does not claim universal provider durability. A pending write or unexpected organization-directory entry fails closed; ordinary reads do not repair, delete, or prune metadata.

## Performance behavior

Search is a deterministic linear scan over at most the authenticated index limit of 20,000 bounded metadata rows; sorting precomputes normalized/type keys before comparing, so it does not repeatedly classify or normalize every comparator operand. Organization membership is bounded to 100,000 references. The Compose list renders at most 100 item/member rows per page (stale references are also paged). No search/sort/album operation reads object bytes, performs crypto, or uses network access. Search is not persisted or indexed separately.

## UI and Stage 15 integration

The existing vault screen exposes **All Items**, **Albums**, and **Search**. Albums can be created, renamed, deleted, opened, and managed through per-item add/remove membership controls. The deletion confirmation explicitly states that no item data is deleted. Missing, unindexed, and unfinished Stage 14 content diagnostics remain available and no organization action repairs them.

Every valid item selection—whether from All Items, Search, or an album—selects the same stable `VaultItemId` and reaches the single Stage 15 `VaultItemViewerContent` / `VaultContentPresentationGateway` path. Album IDs and organization metadata never enter viewer/content-reader APIs. Stale album members cannot be opened and are shown with their stable ID and an explicit remove-reference action. No thumbnail, plaintext cache, media staging, export/share, item deletion, trash, restore, recovery, backup, cloud, or extra permission is added.

## Tests and verification status

JVM sources cover album ID/name/membership rules, deterministic codecs and malformed/unsupported records, Unicode search, typed index failures, sorting, stale references, authenticated organization persistence, commit/rollback/readback failures, generation pruning, and ViewModel-facing session/query/sort state. Instrumented sources exercise the organization codec and metadata search on Android's runtime. Source presence is not proof of Android compilation or execution; consult the final build/test report for actual evidence. The architecture verifier has independent Stage 16 checks and mutation-restoring negative tests in `tools/test_verify_vault_organization.py`.
