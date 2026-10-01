# External encrypted vault — Stages 13–15

## Purpose and current scope

Stage 13 establishes the single explicitly selected external vault root, authenticated/versioned metadata, and typed inspection/initialization failures. Stage 14 adds one-document SAF import, per-item streaming AES-GCM objects, and an authenticated versioned index. Stage 15 adds authenticated-MIME classification, a user-facing item viewer, bounded in-memory image and plain-text previews, and explicit unsupported/error states. Stage 16 adds encrypted album metadata, Unicode-aware metadata-only search, deterministic sorting, and explicit stale-reference handling. Audio/video playback, PDF and other document rendering, thumbnails, trash, restore, and reinstall/recovery remain deferred. See [Stage 15 implementation notes](stage15.md) for the strict boundary between formats classified and formats actually rendered, and [Stage 16 organization](stage16.md) for album and metadata semantics.

## External storage mechanism and root selection

Nivara uses Android's Storage Access Framework through `ACTION_OPEN_DOCUMENT_TREE`. The user explicitly chooses a writable directory in a system document picker; Nivara does not select Downloads, DCIM, Pictures, Documents, a fixed `/storage` path, or private app storage. The choice is persisted using the returned persistable read/write URI grant and the exact document-tree URI in app-private preferences. URI parsing, permission grants, `DocumentsContract`, and document I/O remain in `data.vault`; neither the Android-free domain nor Compose state contains a `Uri`, `DocumentFile`, `File`, or raw path.

The selected directory itself is the vault root. It must be a writable document tree with create support and be empty at initialization. The system provider must support create, read-back, and rename for the initialization transaction; the adapter checks the pending document's advertised rename capability before committing. If the same tree is picked again, Nivara restores/reconnects its persisted grant. Android 11 (API 30) and later prevent selecting the internal-storage root, reliable SD-card roots, and `Download` as a tree; `Android/data` and `Android/obb` are also restricted. Thus the user's available choices depend on platform policy and provider. A different tree is rejected while one root is bound; it is never silently substituted. Selection cancellation, denied grants, provider failures, and a different-root choice leave the previous selection unchanged.

The tree URI is location metadata, not a credential or key. It is not logged or shown in UI. Losing/revoking the persisted grant yields an explicit **Access denied** or **Unavailable** state; users can re-pick the exact same tree. A new installation has no automatic access to the prior URI and must explicitly reselect it. There is no fallback to internal or another public directory. Stage 13 does not offer a root-replacement/migration or reinstall recovery flow.

## Directory structure

Stage 13 initialization creates only these entries inside the user-selected empty directory:

```text
<selected tree>/
├── vault.nvmeta       # encrypted/authenticated vault metadata record
└── data/              # content root, initially empty
```

After explicit Stage 14 index initialization, the same `data/` contains:

```text
data/
├── index/             # immutable authenticated index generations (*.vxi)
└── objects/           # finalized encrypted items (<random-item-id>.nvc)
```

`vault.nvmeta.pending` is a temporary sibling used only during Stage 13 initialization. Stage 14 allows only the `index/` and `objects/` subdirectories directly inside `data/`; unexpected entries or wrong-type directories remain an invalid structure. Index/object pending files are temporary, generated, and never treated as indexed content. No thumbnail, album, trash, or plaintext file is created. The root must remain dedicated to Nivara.

## UI and navigation

The existing navigation graph contains a thin vault status destination and a separate root-configuration destination. The configuration screen exposes only an explicit button that launches the system picker; the Activity Result boundary returns a domain-safe outcome and never passes the selected URI into Compose. Both inspection/initialization and root configuration recheck the existing `SessionManager` gate. The vault adds no Activity, secondary navigation graph, or authentication flow.

## Metadata format and authentication

The outer binary record uses magic `NIVLT13M`, a big-endian 32-bit format version (`1`), a random 128-bit non-secret vault identifier, length-prefixed Stage 2 `WrappedKeyEnvelope` bytes, and a length-prefixed Stage 2 `EncryptedEnvelope` for the header. Parsing is length-bounded and rejects malformed/truncated records, unsupported versions, and trailing bytes. A future format version is surfaced as **Unsupported**, not as uninitialized.

The encrypted header plaintext is deliberately minimal: magic `NVHDR13\0`, its 32-bit header version (`1`), and the vault identifier. It contains no path, creation time, credentials, verifier, biometric data, recovery secret, or plaintext key. No plaintext credentials or raw content keys are stored in the vault record. The header and wrapped key are authenticated by the existing AES-GCM/key-wrapping contracts. The outer magic/version and vault identifier are format/routing fields; they are not relied on as authentication by themselves.

## Key hierarchy and existing Stage 2 integration

1. User authentication and app authorization remain Nivara's existing primary credential/biometric flow and single `SessionManager`. The vault screen checks `SessionManager.currentState()` and `mayAccessSensitiveContent()` before inspect/initialize. The repository does not authenticate, derive from credentials, establish or lock sessions, or own a timeout.
2. Initialization obtains 32 random bytes for a vault content key and a separate 16-byte vault identifier through the existing `SecureRandomSource`. Temporary byte arrays are cleared after use.
3. The content key is wrapped with the existing `KeyWrappingService` and `KeyProtection.ANDROID_KEYSTORE`, using the existing `DeviceKeyStore` key alias `vault_content_wrap_v1`. The purpose and vault identifier are authenticated as context.
4. The small header is encrypted using the existing `AuthenticatedEncryption` service (the Stage 2 AES-256-GCM implementation), with a distinct header purpose and the same vault identifier as AAD binding.
5. The repository discards the raw key after initialization/inspection and does not expose it to Compose or `SessionManager`. No Stage 2 primitive, key derivation, AES/GCM, nonce generation, or Android Keystore implementation is duplicated. The existing credential `CredentialKeyDeriver` is intentionally not used for vault keys.

The device wrapping key is non-exportable under the existing Android Keystore contract, but it is not configured to require biometric authentication for each operation. UI authorization is provided by the existing session gate; this does not turn presentation code into a platform security boundary. Because the current Stage 13 record has only the Android Keystore wrapper, removal/invalidation of that installation's key makes the external vault **Unavailable**; no recovery wrapper or recovery key is created in this stage. Stage 18 must define a recovery/reinstallation hierarchy before claiming portability.

## Initialization and reopen behavior

Initialization is explicit and only offered after inspection finds an accessible, empty selected root. The repository and SAF adapter serialize their own operations with coroutine `Mutex` instances within this app process; this is not an OS-wide or cross-process lock. Initialization rechecks root emptiness and verifies the final structure, so provider/user races fail closed rather than authorize replacement. The repository creates a fresh identifier and content key, wraps/encrypts the header through Stage 2 services, encodes the versioned record, and asks the SAF adapter to:

1. recheck that the exact selected root is empty;
2. create the reserved `data/` directory;
3. create `vault.nvmeta.pending`, write the bounded record, close it, and read it back byte-for-byte;
4. rename the pending document to `vault.nvmeta`;
5. read back the committed metadata and verify the required root entries;
6. let the repository unwrap/decrypt/authenticate the header and confirm the vault identifier before returning success.

A second initialization never overwrites a valid vault. Existing, corrupt, unsupported, inaccessible, or structurally invalid state is never replaced. Inspection after process recreation resolves only the persisted exact URI/grant; there is no automatic directory fallback. A valid record is **Ready** only after both required entries and both cryptographic authentication checks pass.

## Explicit states and fail-closed behavior

Domain/UI state distinguishes:

- root not selected;
- selected root with no marker and no `data/` entry (**Not initialized**);
- authenticated initialized root (**Ready**);
- storage unavailable;
- Android access denied/revoked;
- unreadable or malformed/authentication-failing metadata (**Corrupt metadata**);
- unsupported metadata, wrapped-key, or encrypted-header version/algorithm;
- incomplete, wrong-type, or unexpected root structure (**Invalid structure**);
- initialization in progress and typed initialization failure.

A lone `data/` directory, a lone metadata record, temporary/pending artifacts, unexpected root entries, a bad GCM tag, wrong key material, and truncated bytes do not become an empty vault. A missing Android Keystore key is reported as unavailable, not corruption or an empty vault. No failure path silently chooses another root or reports successful initialization.

## Atomicity and cleanup limits

SAF providers do not expose a universal filesystem transaction or promise that `DocumentsContract.renameDocument` is atomic. The adapter uses a same-directory pending document, closes and verifies it before rename, verifies the renamed bytes, and the domain authenticates the complete record again before accepting **Ready**. Read-back confirms observed provider bytes but is not an `fsync` or a power-loss durability guarantee; SAF has no portable durability primitive. A provider that refuses create/read-back/rename cannot initialize the vault. Provider-level crash behavior cannot be proven by this protocol; interrupted leftovers are rejected as invalid, never accepted as ready.

When initialization fails, cleanup targets only the exact pending/final document and empty `data/` directory created by that attempt. It does not recursively delete the selected folder or pre-existing user data. Cleanup failure is surfaced separately; it may leave an invalid, non-ready structure requiring user/provider intervention. No replacement write API exists in Stage 13. Later metadata updates must preserve the same temporary-write, verify, rename, and post-write authentication discipline.

## Permissions and Android compatibility

The SAF document picker and persistable URI grants avoid broad filesystem access. No `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, notification permission, accessibility service, device admin, or vault foreground service is added. The app retains only its pre-existing Stage 1–12 permissions and narrow package visibility.

The implementation uses `ACTION_OPEN_DOCUMENT_TREE`, persisted URI grants, and `DocumentsContract`, available on the Android 9 / API 28 minimum and compatible with Android's scoped storage APIs. Android 10–12 and Android 13+ behavior still depends on each selected document provider and its support for create/read/rename. Compatibility was assessed from the API boundary and verifier, not exercised on those OS versions. No external-storage runtime test is claimed. See Android's [shared-storage SAF guide](https://developer.android.com/training/data-storage/shared/documents-files) and [`DocumentsContract.renameDocument` reference](https://developer.android.com/reference/android/provider/DocumentsContract#renameDocument(android.content.ContentResolver,android.net.Uri,java.lang.String)).

## Privacy/security boundaries and independence

Stage 13 alone did not import vault content; Stage 14 now provides streamed file-content encryption described in [stage14.md](stage14.md). The metadata/header foundation is encrypted/authenticated using Stage 2 services. The selected URI, file names, paths, vault identifiers, keys, and plaintext header are not logged; no network or analytics code is present. The vault does not access credentials, hidden-app or protected-app repositories, App Lock, or camouflage identity. It adds no Activity or secondary navigation graph; its status and root-configuration destinations are registered in the existing graph and are reachable from Nivara Home regardless of the fixed Stage 12 presentation label.

## Stage 14 implementation notes

The Stage 14 item/index/object format, scoped per-item key handling, streamed import transaction, session-expiry/orphan behavior, concurrency limits, verifier coverage, and deferred scope are documented in [stage14.md](stage14.md). In particular, finalized objects without a successful authenticated index commit remain **unindexed orphans**; the index is never reconstructed by scanning objects.

## Stage 15 presentation

Stage 15 keeps the same authenticated item/index/object formats and `SessionManager`. MIME classification uses only authenticated `originalMimeType`; missing/invalid MIME maps to Other and no file extension is consulted. The platform presentation gateway re-reads the current authenticated index row, opens the corresponding generated object through the existing read-only storage boundary, and invokes Stage 14 quarantine decryption. A preview is withheld until streaming GCM tag verification succeeds. Only bounded in-memory image (16 MiB compressed maximum, sampled to a 1,536-pixel render dimension) and strict UTF-8 `text/plain` (256 KiB maximum) previews are implemented. No decrypted file or thumbnail is written to disk/cache.

MIME classification also recognizes image, audio, video, and document categories, but category recognition is not viewer support. PDF, audio, video, office/OpenDocument, and generic/unknown content receive explicit unsupported presentation. The current sequential GCM format is not exposed through an unauthenticated prefix, and full-file plaintext staging is deliberately not used to make Android media playback seekable. See [stage15.md](stage15.md) for tested-vs-unverified format detail and lifecycle/session behavior. No broad storage/media permission, sharing, external open-with, index repair, or object deletion was added.

## Stage 16 albums, search, sorting, and organization

Stage 16 adds a bounded, versioned organization record under `data/organization/`, encrypted with the existing vault content key through a distinct Stage 2 organization purpose. Albums persist only names and ordered `VaultItemId` references; item metadata stays authoritative in the authenticated index. Empty albums and multiple memberships are supported; duplicate membership requests return `AlreadyMember` without a write. Stale references are explicit and ordinary reads never prune them. Album deletion and membership removal affect organization metadata only.

The existing screen now provides All Items, Albums, and Search, with deterministic name/size/import-time/type sort and stable ID tie-breaking. Search uses Unicode NFC normalization, locale-independent case folding and whitespace collapsing over authenticated filename, MIME and classifier metadata only; it never opens/decrypts objects or persists query history. Empty, no-match, unavailable, unreadable and unsupported index/organization states are distinct. All item selections still enter the single Stage 15 viewer by item identity. See [stage16.md](stage16.md) for bounds, record validation, generation commits, crash behavior, and test sources.

## Tests and runtime status

JVM tests cover Stage 13 metadata/header codecs, actual Stage 2 AES-GCM/key wrapping, initialization/idempotence and failure states, plus Stage 14 deterministic bounded index codecs, streamed GCM round trips/tampering, content-key integration, importer checkpoints, index corruption/write failures, and orphan outcomes. Stage 16 JVM sources cover album/domain codecs, Unicode search, all sort keys and stable tie-breaking, stale references, authorization, generation readback/rollback/pruning, corruption, and ViewModel-facing state; a real JCA integration test checks the distinct organization purpose. Instrumented sources exercise Android JCA streaming with multi-megabyte inputs and nonce sampling, image decoding, and organization/search contracts; execution requires a connected Android device/emulator. Device/provider execution, real persisted grants, rename guarantees, and Android UI behavior require device verification. No test is described as compiled or run unless it actually was.
