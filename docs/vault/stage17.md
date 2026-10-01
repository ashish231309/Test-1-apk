# Stage 17 — Vault Trash, Restore & Metadata Preservation

## Contract and non-goals

Trash is a reversible lifecycle transition on the authenticated Stage 14 item-index row:

```text
ACTIVE ──moveToTrash──> TRASHED ──restoreFromTrash──> ACTIVE
```

Both states keep the same stable `VaultItemId` and point to the same existing encrypted content object. No content object is opened, scanned, re-encrypted, copied, migrated, overwritten, or deleted by Trash/Restore. No separate trash database or storage directory exists. There is no permanent deletion, no Empty Trash, no secure erase, and no automatic expiry. Background cleanup, sharing/export, backup, and reinstall recovery are also not part of Stage 17.

## Preserved item metadata

The authenticated item-index record preserves the stable ID, original filename, MIME type (and therefore the existing deterministic MIME classification), verified plaintext and encrypted-object sizes, import time, content format version, wrapped item key, and any recorded digest. The lifecycle timestamp is present only for `TRASHED`; restoring clears it. Transition helpers copy all remaining metadata and key/digest bytes defensively. If the encrypted object is missing, the index row and Trash entry remain represented. Restoring metadata never recreates missing bytes.

Stage 17 adds an optional 32-byte SHA-256 digest for newly imported plaintext. Import computes it incrementally through `DigestInputStream` while the existing streaming encryption reads the source, and the digest is then persisted inside the existing authenticated index. It is not placed in the object header, filename, external log, or new database. Stage 14/v1 items had no digest: decoding does not scan/decrypt old objects to invent one, and the null value remains preserved through transitions.

## Index format and migration

`VaultIndexCodec` writes strict version 2 rows and decodes version 1 rows as Active with a null trash timestamp and null digest. Existing encrypted index envelope, AES-GCM purpose, vault content key, generation AAD, storage root, and Stage 14 object format are unchanged. V2 validates lifecycle enum values, Active/Trashed timestamp invariants, digest marker and exact digest length, per-row size bounds, UTF-8 and metadata bounds, duplicate IDs, total index byte/item bounds, and trailing bytes. Unsupported versions and malformed records remain distinct failures; neither becomes an empty Trash state.

## Persistence, authorization, and generation behavior

The existing `DefaultVaultIndexRepository` is the sole item-state authority. Its mutex serializes import/index and Trash/Restore operations in the app process. Each transition reads the authoritative index, requires the expected lifecycle, prepares a new generation, rechecks the existing authorization checkpoint, commits through the existing SAF content storage, rechecks authorization, decrypts/authenticates the new generation, and compares the transitioned row against the expected identity and all preserved metadata. It returns success only after that verification. Already-Active/already-Trashed results are typed and do not write a generation. Authorization, vault, storage, corrupt, unsupported, missing-index, and item-not-found results remain explicit.

Index file pruning keeps the latest verified generation and its predecessor. Old generations are pruned only after authenticated readback and state comparison. If readback or authorization fails, the repository attempts to discard only the just-created, unverified newest index generation, retaining the previous committed generation. If provider cleanup fails, the failure is not represented as a successful mutation. A successful state mutation may remain successful when *optional pruning* fails; UI reports that older generations could not all be pruned.

SAF create/write/readback/rename and repository readback are observed provider behavior, not a universal filesystem transaction. `DocumentsContract` providers do not promise `fsync`, power-loss persistence, or cross-process locking. Stage 17 makes no such claim. The repository and storage mutexes serialize only this process/repository instance.

## Album memberships and active collections

Albums retain the same item-ID membership list while an item is trashed. The organization resolver reports a `Trashed` member explicitly; active album item surfaces filter it out while leaving its reference intact. Restore makes that same item resolve as an active member again. Album deletion continues to remove only album metadata/references and does not touch trashed or active objects.

All Items, active Search, active sorting, album browsing, and active viewer selection exclude trashed rows. Trash is independent, searches the same normalized metadata fields as Stage 16, and supports deterministic sorting. Default order is descending trash timestamp, then ascending stable item ID; other displayed metadata sort keys also use a stable ID tie-break. Trash has Restore-only rows: there is no content-open action from this conservative surface.

## UI and failure states

The Trash tab is shown when the authenticated index is readable. A readable index with zero trashed rows yields the distinct **Trash is empty** state. Locked session, missing index, unavailable vault/storage, access denial, unreadable/corrupt index, and unsupported format are distinct and never map to empty. A trashed row with known missing content states that restoring will not recreate it; when diagnostics are unavailable, UI says availability could not be checked instead of claiming the object exists.

Moving to Trash uses a confirmation that explains metadata and memberships remain and that this is not permanent deletion. Restore returns the same item ID; active surfaces/search/album membership become effective again. Mutations require the existing `SessionManager` both at the UI action and repository authorization checkpoints. No additional login flow or permission was added.

## Privacy and performance boundaries

Search/sort operate on the existing authenticated metadata only. A trash action does not list/scan the object directory or open content; index reads for Trash state skip optional object-directory diagnostics and preserve the last observed diagnostics in UI. New import digesting uses a bounded streaming wrapper; it does not buffer the source. V2 index rows are bounded by the existing index maximum, per-item record limit, and item-count limit. No plaintext content, key, URI, or search history is logged or sent over a network.

## Verification sources

JVM sources cover v1/v2/malformed codec records, digest import, typed transitions, identity/metadata preservation, stale and missing items, generation cleanup/readback, authorization expiry, failed writes, concurrent operations, album references, search/sort, and ViewModel empty/locked/error/populated states. Android instrumented source covers the versioned index contract on Android runtime; it requires a connected device/emulator and is not claimed as executed unless CI/device evidence says so. `tools/verify_nivara.py` checks Stage 17 architecture rules, and `tools/test_verify_vault_trash.py` mutates each protected rule, requires verifier rejection, then restores exact original bytes.
