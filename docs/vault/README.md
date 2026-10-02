# External encrypted vault

## User-visible behavior

The vault stores individually imported documents as encrypted objects in one folder that the user explicitly selects with Android's Storage Access Framework (SAF). The app supports metadata browsing, albums, metadata-only search and sorting, reversible Trash/Restore, and optional key recovery. The viewer supports bounded in-memory previews for decodable images and strict UTF-8 plain text. Audio, video, PDF, office documents, and other unsupported types are not opened. MIME classification is descriptive and does not promise that a particular device can decode an image format.

Trash and Restore change authenticated index metadata only. They do not delete or rewrite encrypted content. There is no permanent deletion, secure erase, automatic expiry, or content migration. Recovery reconnects the same vault key; it does not recover account credentials, biometrics, a session, or Android's storage grant.

## Storage selection and access

Nivara uses `ACTION_OPEN_DOCUMENT_TREE` and requires an explicit user choice of a writable directory. This uses Android's scoped storage model without broad storage permission. The existing vault status destination links to a separate root-configuration destination; both use the existing session gate. It does not choose Downloads, DCIM, Pictures, Documents, app-private storage, or a raw filesystem path. It retains the exact returned document-tree URI and persistable read/write grant in app-private preferences. URI parsing, grants, `DocumentsContract`, and document I/O remain in the data layer; Android-free domain and UI state do not carry `Uri`, `DocumentFile`, `File`, or raw paths.

The selected directory itself is the vault root. Initialization requires an accessible, empty location and provider support for create, read-back, and rename. The same tree must be selected again after a grant is lost or after reinstall. A different location is not silently substituted, and there is no fallback, path scan, automatic adoption, or repair. Android 11 (API 30) and later restrict selection of some locations, including internal-storage roots and `Download`; available choices depend on Android and the document provider. The app's minimum supported API is 28.

Losing or revoking access produces an explicit Access denied or Unavailable state. Cancellation, a denied grant, provider failure, or a different-root selection leaves the prior selection unchanged. A new installation has no automatic access to the prior URI and must explicitly reselect the same SAF tree.

## Data layout and formats

Initialization uses an atomic create-only transaction. The root is dedicated to Nivara and contains a metadata record, a `data/` directory, and, when explicitly configured, an optional recovery sidecar:

```text
<selected tree>/
├── vault.nvmeta             # versioned encrypted/authenticated vault header
├── vault.nvrec               # optional encrypted wrapper for the existing vault key
└── data/
    ├── index/                # immutable authenticated index generations
    ├── objects/              # generated encrypted content objects
    └── organization/         # encrypted album names and item-ID references
```

Pending siblings and object/index temporary files are recognized only during their corresponding write transaction. Unexpected entries, invalid pending records, wrong-type directories, unsupported versions, truncation, and trailing bytes fail closed. The app never treats a pending file as committed content. The index is authoritative: it is not reconstructed by scanning object names. An encrypted object left without a successful index commit remains an unindexed orphan and is reported as such.

The outer metadata record uses the `NIVLT13M` magic, an explicit format version, a random non-secret vault identifier, a wrapped-key envelope, and an authenticated encrypted header. The header binds the vault identifier and contains no path, credential, recovery secret, biometric data, or plaintext key. Parsing is bounded and distinguishes corrupt data from unsupported versions and a genuinely uninitialized root. No network, analytics, or telemetry is used, and no plaintext credentials or raw content keys are stored in vault files.

Content imports use unique generated item IDs, per-item random keys, a separate wrapped-key purpose, authenticated index metadata, and streaming AES-256-GCM encryption with bounded memory. Reads verify the final GCM tag before any preview is exposed. The source document is opened through a transient SAF selection handle; source URIs are not retained as vault item metadata. Original filenames are not used as object paths.

## Key, session, and recovery boundaries

The vault uses the existing `KeyWrappingService`, `AuthenticatedEncryption`, Android Keystore, and `SessionManager` contracts. It does not add a credential-derived content key, a software-key fallback, a second session, or an authentication cache. The device wrapping key is non-exportable under the Android Keystore contract, but is not configured to require biometric authentication on every operation. Repository operations recheck the existing session and authorization checkpoints.

The optional recovery code is a random 256-bit artifact with typo-detection encoding. It wraps the same vault content key under a separate authenticated envelope; it does not re-encrypt content objects, index generations, or album records. The code is shown for the user to save and is not persisted, shared, or exported by the app. Recovery requires a newly enrolled local primary credential, explicit selection of the original SAF tree, and explicit code entry. The repository validates the vault identity and any existing authenticated records before persisting a new installation-local Keystore wrapper. It does not initialize, migrate, repair, or prune the selected vault. Recovery-code rotation is not supported.

Failed recovery-envelope authentication is serialized and receives an in-memory exponential retry delay, capped at 30 seconds. Success resets the delay; process recreation clears it. This bounded delay is defense in depth, not a replacement for the recovery code's entropy or authenticated encryption.

## Organization, Trash, and viewing

Albums store names and ordered stable item IDs; authenticated item metadata remains in the index. Search normalizes and sorts authenticated metadata only and does not open or decrypt content objects. Stale references remain explicit and are not silently pruned. Trash is an authenticated lifecycle marker in index rows. Restoring an item preserves its ID and album references. Trash does not create a content copy or directory.

Index updates use immutable generations and preserve the previous authoritative generation until the new one has been written, flushed, read back, decrypted, authenticated, and verified. Only then may older generations be pruned. If verification fails, the previous authoritative generation remains available. SAF providers do not offer a universal `fsync`, cross-process lock, or power-loss guarantee; provider-specific durability beyond the verified operations cannot be promised.

The viewer holds only typed UI state and an opaque handle, not raw keys, paths, URIs, streams, or whole-file plaintext. Image data is size-capped and sampled for bounded in-memory rendering; `text/plain` uses strict UTF-8 and a size limit. No decrypted file or thumbnail is written to disk/cache. PDF, audio, video, and other unsupported categories receive an explicit unsupported state; full-file plaintext staging, sharing/export, and external open-with are not used to enable playback or rendering. Viewer resources close on explicit exit, screen disposal, Activity pause, session invalidation, and Quick Lock.

## Explicit states and limitations

The UI distinguishes root not selected, Not initialized, Ready, Access denied, Unavailable, Corrupt, Unsupported, and initialization failure. Missing, corrupt, inaccessible, unavailable, and unsupported data are never presented as an empty vault or as permission to overwrite. Recovery also distinguishes invalid code material, failed envelope authentication, damaged records, unsupported versions, and location/provider failures.

SAF provider behavior varies. Rename/read-back support, large imports, image codec support, Android UI lifecycle, biometric behavior, and device-maker background policies require verification on target devices. Android test-source compilation is not the same as running instrumented tests on an emulator or device. For local device execution, connect an API 28+ device/emulator and run:

```sh
./gradlew connectedDebugAndroidTest
```

The repository architecture verifier and Python negative harness can be run with:

```sh
python3 tools/verify_nivara.py
python3 -m unittest discover -s tools -p 'test_*.py'
```
