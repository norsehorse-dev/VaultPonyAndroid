# VaultPony 2.0.0 rc3: iOS File Provider extension, Phase 0 spike

Date: 2026-10-21. Scope: plan section 4.2, decisions D2 and Q1. Desk research plus a read of the
current code (iOS repo at 2.0.0-rc2, VaultPonyCore, VaultPonyAndroid plan Rev 5). No app code
written. Every claim is tagged with a source, or marked **[guess]** / **[verify on device]** where
evidence is thin.

Code read: `THREAT_MODEL-IOS.md`, `App/SessionRegistry.swift`, `CoreBridge/Vault.swift`,
`Security/FdOpener.swift`, `Storage/VaultStore.swift`, `project.yml`, core
`vault-ffi/src/lib.rs`, `vault-core/src/session.rs`, `vc-types/src/registry.rs`
(`argon2_cost`), `vc-io/src/lib.rs`, `vc-fs/src/fat.rs`, `norse-exfat/src/alloc.rs`. For
precedent I also read the open-source Cryptomator iOS app (github.com/cryptomator/ios,
`develop`), which ships a File Provider for encrypted vaults on the App Store.

---

## TL;DR

- **Model:** use the older, non-replicated `NSFileProviderExtension`, not the replicated one the
  plan names. It is the only model where VaultPony decides where plaintext lands (its own app
  group), with which protection class (`.complete`), and can delete it at lock itself, even when
  the extension is dead. Replicated storage on iOS is system-managed and undocumented.
  Cryptomator uses the non-replicated model for exactly this job.
- **Memory:** an Apple engineer states File Provider extensions on iOS have a **20 MB** limit.
  Argon2id at the default PIM needs **416 MiB** (20x over); even PIM 1 needs 64 MiB. The
  extension cannot run the KDF. PBKDF2 would fit, but the unlock search, keyfiles, hidden and
  protected modes make an in-extension unlock a poor fit anyway.
- **Keys:** option (a). The app pushes an exported key blob plus the open container fd (as a
  `FileHandle`) to the extension over an `NSFileProviderService` XPC connection. Keys live only in
  process memory. Cryptomator does the same (it sends a derived KEK over XPC). If the system kills
  the extension, the domain reports `notAuthenticated` until VaultPony is opened again, which
  re-pushes silently if the app's session is still within its auto-lock window. No Keychain
  copy of key material.
- **Two blockers the plan does not mention:**
  1. **Two writers on one FAT/exFAT volume.** The app keeps its in-app session, the extension has
     its own. Each caches filesystem state (exFAT bitmap, FAT FSInfo, free-space counts).
     Interleaved writes corrupt the volume. Needs a cross-process lock plus a core `remount()`.
  2. **`FileDevice` uses `seek` + `read_exact`**, not `pread`/`pwrite`. Two processes sharing one
     open file description (fd passing, or even the same fd via dup) share the file offset, so
     concurrent I/O reads and writes the wrong offsets. Must move to `FileExt::read_exact_at` /
     `write_all_at` before rc3.
- **Plaintext on disk is unavoidable on iOS.** There is no proxy-fd equivalent of Android's
  `openProxyFileDescriptor`; other apps open real files. Mitigations: `.complete` protection,
  backup exclusion, delete on `stopProvidingItem`, wipe the domain directory at lock, empty working
  set (no Recents, no Spotlight), off in no-trace mode.

---

## 1. Which File Provider model, and where plaintext lands

### Findings

| | `NSFileProviderExtension` (non-replicated) | `NSFileProviderReplicatedExtension` |
|---|---|---|
| Availability | iOS 8+ (domains iOS 11+), not deprecated on iOS; Apple only says not to use it on macOS [A1] | iOS 16+ [A2] |
| Who stores materialized files | The extension. It writes them under `NSFileProviderManager.documentStorageURL`, which lives in the app group named by `NSExtensionFileProviderDocumentGroup` [A1][C1] | The system ("the system takes responsibility for managing and storing the local copies") [A2] |
| Protection class | Whatever VaultPony sets. Default for third-party data is "Protected Until First User Authentication" [A10], so it must be set explicitly to `.complete` | Not documented for iOS; not settable by the provider **[guess]** |
| Knowing when a file is no longer used | `stopProvidingItem(at:)`: "a given document is no longer being accessed" [A1] | No push; provider must call `evictItem` |
| Removal at lock | App calls `NSFileProviderManager.remove(domain)` and deletes `documentStorageURL/<domain path>` itself. Cryptomator does exactly this [C2] | `remove(_:mode: .removeAll)` (iOS 16+) [A3][A4]; system-side, asynchronous, not verifiable from the app |
| Eviction | Provider deletes its own files | `evictItem` fails with `EBUSY` "if the item has open file descriptors on it", and with `unsyncedEdits` for local changes [A5] |
| Spotlight / Recents | Driven by the working set the provider enumerates | Same; "The device's Spotlight database also indexes the working set's contents" [A6] |
| Partial / range fetch | None | `NSFileProviderPartialContentFetching` is macOS 12.3+ only [A7], so no help on iOS |

Neither model streams. Opening a vault file from another app always means a full plaintext copy on
disk. Cryptomator's developer confirms the same constraint: the cache is "technically required
because of the File Provider Extension API, which needs cleartext data" [C4]. FSKit (a real user
space filesystem) is macOS 15.4+ only [A8].

Where plaintext lands in the non-replicated model:
- `startProvidingItem(at:)`: the extension decrypts the whole file (streamed in chunks) into
  `documentStorageURL/<random domain path>/<item id>/<name>`.
- Edits by another app are written by that app into that same file; the extension learns via
  `itemChanged(at:)`.
- `importDocument(at:toParentItemIdentifier:)` hands the extension a URL to someone else's file to
  copy in.

### Can removal at lock be guaranteed?

- **Non-replicated: yes for VaultPony's own copies.** The directory is in VaultPony's app group,
  so the app can delete it and then enumerate the group container to prove it is empty, whether
  or not the extension is running. Cryptomator removes the domain and then deletes the domain
  directory from the app [C2]. Cryptomator's own 2021 bug (2 GB of decrypted cache, unencrypted,
  included in iCloud backups) shows what happens without this; they fixed it by clearing the cache
  and excluding it from backup [C4][C3].
- **Replicated: no guarantee.** `remove(.removeAll)` asks the system; the location is not
  documented for iOS; `evictItem` refuses open files [A5]. Verifying would need a sysdiagnose or
  device-image inspection.

### Files another app has open when the domain goes away

Not documented by Apple. **[guess, verify on device]** iOS apps open provider files directly by
path, so POSIX unlink semantics apply: an app that already has the file open keeps reading the
orphaned inode until it closes it, and its later writes go to the orphan and are lost. With
`.complete` protection, the class key is discarded about 10 seconds after the device locks [A10],
so the orphan becomes unreadable once the phone locks. Apps that copied the file into their own
container ("Open in" as copy, "Save to Photos") keep their copies; that is outside VaultPony's
control, same as the Android threat-model note.

---

## 2. Extension memory limit vs the KDFs

### Findings

- Apple engineer, accepted answer, Oct 2023: "FileProvider app extension processes have a 20 MB
  memory limit on iOS." The reported crash was
  `EXC_RESOURCE (RESOURCE_TYPE_MEMORY: high watermark memory limit exceeded) (limit=20 MB)` [F1].
- Apple DTS, Nov 2023, same 20 MB: even 5 MiB decryption chunks were "large relative to a
  20 MiB limit"; he advised smaller chunks [F2].
- A 2025 forum post still reports 20 MB (listing ~2,000 items crashed a WebDAV provider); DTS asked
  for a Feedback report, with no change announced [F3].
- Cryptomator's docs: the File Provider extension "has limited capabilities and a lower memory
  limit", and "iOS may terminate Cryptomator at any time, which is basically the same as locking
  the vault" [C5].
- Nothing official says whether replicated and non-replicated differ, or whether iOS 18 changed the
  limit. Evidence is thin but consistent: plan for 20 MB including the Swift runtime, the
  FileProvider framework and the Rust core. **[verify on device]** Log
  `os_proc_available_memory()` at extension start and in steady state on iOS 17 and 18.

### KDF numbers, from the core

`vc-types::registry::argon2_cost(pim)` (tests in `registry.rs`):

| PIM | Argon2id memory | passes |
|---|---|---|
| 0 / 12 (default) | **416 MiB** (425,984 KiB) | 6 |
| 1 (minimum) | 64 MiB | 3 |
| 4 | 160 MiB | 4 |
| 31+ | 1024 MiB (cap) | 13+ |

This matches the website's "about 416 MB". PBKDF2 needs only a few KiB of state (HMAC), but the
default VeraCrypt auto search runs up to about 500,000 iterations per PRF over several PRFs, which
takes seconds.

### Conclusion

The extension cannot run Argon2id at any PIM (20 MB limit vs 64 MiB minimum). It could run
PBKDF2, but then:
- Argon2id volumes (VeraCrypt 1.26.29+, supported since VaultPony 1.1.0) could never unlock from
  Files;
- the unlock UI would have to rebuild keyfiles, hidden volumes, protected-outer mode, the KDF filter
  and the hash choice planned for rc4.

So the KDF stays in the app, which already checks memory (`Vault.argon2MemoryCheck`). Q1 in the plan
resolves to "app hands over derived keys". The open part is how (section 3).

Other memory budget items for the extension:
- exFAT bitmap cache: `MAX_CACHED_BITMAP_BYTES = 64 MiB` (`norse-exfat/src/alloc.rs`). A large,
  small-cluster exFAT volume would blow the budget on the first allocation. The core needs a
  lower cap for the extension (for example 1 to 2 MiB, falling back to the existing on-disk path).
- `read_at` caps at 8 MiB per call; the extension should use about 256 KiB chunks. UniFFI copies
  each buffer at least once on each side, so peak use is several times the chunk size.
- `DecryptedDevice` allocates one block buffer per call, sized to the request. Fine at 256 KiB.
- Enumerating huge folders: build `NSFileProviderItem`s in pages, never the whole listing as
  Swift objects at once. Note that `VaultSession.list` returns the full `Vec<DirEntry>`.

---

## 3. Key handoff without the extension doing the KDF

### (a) App pushes keys over File Provider XPC (recommended)

What exists:
- The extension vends services via `NSFileProviderServicing` / `supportedServiceSources(for:)`;
  each source returns an `NSXPCListenerEndpoint` from `makeListenerEndpoint()` [A9][A11].
- The app connects in one of two ways:
  - iOS 11+: `FileManager.default.getFileProviderServicesForItem(at:)` with an item URL. For the
    non-replicated model, Cryptomator uses `documentStorageURL/<domain path>` as that URL [C6].
  - iOS 16+: `NSFileProviderManager(for: domain)?.getService(named:for: .rootContainer)` [A12].

  Either way it then calls `NSFileProviderService.getFileProviderConnection` to get an
  `NSXPCConnection` [A11].
- Direction: app to extension only. iOS apps cannot vend XPC services, and the app is usually
  suspended anyway. The extension can only reach the app through shared files or Darwin
  notifications [B1].
- `isRestricted` (iOS 16+) defaults to true. Apple DTS: restricted services are reachable only
  from the owning app. Setting it to false opens the service to other processes, so keep the
  default [A13][F5].
- Does the request launch the extension? It is not documented explicitly. DTS (2025) describes
  `getFileProviderServicesForItem` leading the system to call `supportedServiceSources(for:)`,
  which "only starts the process" of setting up the XPC channel [F5]. Cryptomator calls it from its
  app and its FileProviderUI right after the user types a password, when the extension may well not
  be running, and sends the derived KEK through it: `unlockVault(kek: [UInt8], reply:)` [C6][C7].
  That is good production evidence that it launches the extension. **[verify on device]**
- Gotcha: the extension's `listener(_:shouldAcceptNewConnection:)` must call
  `newConnection.resume()`, or the client fails with a misleading "Sandbox restriction" error [F4].

Passing the container as well. The app already holds an fd. Apple DTS: "You can't pass
security-scoped bookmarks between processes" (macOS context). The recommended approach is to "pass
a file descriptor", via `FileHandle` over XPC ("Not just sufficient, but required") [F6].
`NSFileHandle` conforms to `NSSecureCoding` and carries the fd across NSXPC [F7]. Benefits:
- the extension never needs a bookmark or a container path;
- it works for every location the app could open, including copy-mode copies in the app's private
  Application Support;
- nothing new about the vault is persisted in the app group.

**[verify on device]** that a `FileHandle` travels over a FileProvider service connection, which is
an ordinary `NSXPCConnection`.

Lifecycle under (a):
- The extension holds keys and fd in memory only.
- Killed extension (jetsam, idle, reboot): the next enumeration throws `notAuthenticated` from
  `enumerator(for: .rootContainer)`. That is the path that shows Files' authenticate view; returning
  it from `startProvidingItem` only shows a generic -1000 alert [F8].
- The app re-pushes on every `sceneDidBecomeActive` while its session lives (idempotent), so
  "open VaultPony" restores Files access without a password if the auto-lock window has not passed.
- **[verify on device]** How fast fileproviderd kills an idle extension is undocumented. If it is
  seconds rather than minutes, (a) gets annoying. Cryptomator's default "Let iOS Decide" has the
  same exposure, and they ship it [C5].

### (b) Short-lived app-group Keychain item

Cryptomator does this as an opt-in "Unlock Duration": the masterkey is copied into the Keychain
for the chosen period and removed when it expires [C5]. They use
`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` [C8].

Against it for VaultPony:
- Key material is persisted on disk (Keychain DB). It survives process death **and reboot**, which
  breaks the current iOS posture ("sessions live only in the app's memory") and the Android D15
  text ("Process death still locks ... a reboot locks").
- Deletion at the deadline needs a VaultPony process to run after the deadline. A suspended app
  does not run timers, so the item can outlive the deadline by hours.
- `WhenUnlockedThisDeviceOnly` only blocks reads while the device is locked; the item is still on
  disk.

Only worth it as an explicit, warned opt-in later. Not for rc3.

### (c) Extension unlocks via FileProviderUI authentication

`FPUIActionExtensionViewController.prepare(forError:)` is invoked when the user taps Authenticate
after `notAuthenticated` [A14][F8]. The FPUI extension is a separate process with an undocumented
memory limit. Cryptomator runs its scrypt KEK derivation there (`UnlockVaultViewModel.getKEK`) and
sends the result to the FP extension over XPC [C7]. Scrypt at Cryptomator's defaults needs about
32 MiB **[guess from cryptolib defaults]**, so the FPUI limit is above that, but nothing suggests
416 MiB fits.

Verdict: PBKDF2-only volumes could re-unlock from Files; Argon2id volumes could not. Keyfiles need a
file picker inside an action extension. Hidden and protected modes need their UI. Too much for
rc3. Use the FPUI extension only to show "Open VaultPony to unlock" on `notAuthenticated`.

Do **not** try to open the containing app from the FPUI extension. There is no public API for that
in an action extension, and the responder-chain workaround is an App Review risk **[guess]**.

### Comparison

| | (a) XPC push | (b) Keychain | (c) FPUI unlock |
|---|---|---|---|
| Keys on disk | never | yes, until deleted | never |
| Survives extension kill | no (re-push on app open) | yes | user re-types password |
| Argon2id volumes | yes | yes | no |
| Keyfiles / hidden / protected | yes (done in app) | yes | big UI work |
| Deadline when app suspended | extension enforces in memory | item lingers on disk | extension enforces |
| Core work | export/import FFI | same, plus at-rest blob format | none, but PBKDF2-only filter |
| Precedent | Cryptomator `unlockVault(kek:)` | Cryptomator Unlock Duration | Cryptomator FPUI (scrypt) |

### What the core needs for (a)

Today `Session::unlock_device_with` hands the master keys to `SchemeXts::new` and then zeroizes the
header: "from this point the only key material lives inside the XTS engine". `Session` keeps only
the VFS, the scheme and PRF names, and flags. A handoff needs these FFI additions:

1. `VaultSession.export_handoff() -> Vec<u8>`: a versioned opaque blob holding
   - the scheme registry id;
   - the master key bytes (`scheme.key_bytes()`, at most 192 bytes for triple cascades);
   - `VolumeGeometry` (`volume_size`, `encrypted_area_start`, `encrypted_area_size`,
     `sector_size`);
   - the `hidden` flag;
   - the outer-protected range (`protected_start`, `protected_len`) or none;
   - the PRF name (display only);
   - **a container fingerprint**, for example a hash of the header salt at the header offset that
     unlocked, so keys are never applied to a different file.

   Never the password, PIM or keyfiles. The keys come either from a `SchemeXts::export_keys()`
   accessor or from a `Zeroizing` copy kept in `Session`. The accessor is better because it keeps
   one source of truth.
2. `VaultSession.import_fd(fd, blob)` constructor: check the version and scheme, check the geometry
   against `dev.len()`, re-read the fingerprint, rebuild `DecryptedDevice`, optional
   `ProtectedDevice`, `open_volume`. Wipe the input buffer. Errors stay coarse.
3. This is "secrets crossing the FFI boundary" beyond callback scope. The core THREAT_MODEL says
   such a design change "must be argued in this file first". Add a section before writing code.
4. Swift `Data` copies of the blob cannot be reliably zeroized. That is the same caveat already
   documented for passphrases in THREAT_MODEL-IOS section 2. Keep the blob's Swift lifetime to the
   XPC call.

---

## 4. Container access from the extension

### Findings

- Plan B, bookmarks: Cryptomator creates folder bookmarks in the app (`.minimalBookmark`), stores
  them in a shared Keychain group, and resolves them in the FP extension with
  `startAccessingSecurityScopedResource()`. This works in production for their Files-based storage
  [C9]. Apple DTS says security-scoped bookmarks can't cross processes [F6], and a 2016 macOS
  thread shows the same failure [F9]. iOS behavior for a *file* bookmark (VaultPony bookmarks the
  container file, not a folder) is **[verify on device]** for each of On My iPhone, iCloud Drive
  and a third-party provider.
- **Recommended: pass the fd** (section 3). The app keeps doing what it does now:
  `ensureDownloaded`, coordinated open in `FdOpener`, copy-in fallback. Then it sends
  `FileHandle(fileDescriptor: dup(fd))` to the extension. The extension needs no
  `NSFileCoordinator` per operation, matching the app, which coordinates only at open time.
- **The core must switch to positional I/O.** `vc_io::FileDevice::read_at`/`write_at` do
  `seek(SeekFrom::Start)` then `read_exact`/`write_all`. A dup'd or XPC-passed fd shares the file
  offset with the app's fd, so concurrent use from two processes reads and writes at the wrong
  offsets. Use `std::os::unix::fs::FileExt::{read_exact_at, write_all_at}` (`pread`/`pwrite`).
  This is small and also hardens the single-process case.
- iCloud Drive: the app already refuses to hand the core a dataless file. An open fd keeps the
  file from being evicted under it; Apple documents `EBUSY` eviction refusal for open fds in the
  File Provider eviction API [A5] **[guess that iCloud behaves the same]**.
- Container inside another File Provider (Dropbox, OneDrive and so on): no new problem, the app
  opens it as it does now. The extension never touches the other provider.
- **Container inside VaultPony's own domain** (a vault file stored in an unlocked vault): refuse it
  at unlock. Coordinating on your own domain's items from inside your extension is a classic
  deadlock **[guess]**, and it makes no sense anyway.
- **Copy-mode sessions** (`FdOpener.openViaCopyIn`): with fd passing they technically work. But
  the session ends in a write-back prompt in the app, which the extension cannot show. Simplest for
  rc3: no Files access for copy-mode sessions, with a line in the UI explaining why.

---

## 5. Auto-lock and lifecycle

### Findings

- The extension has no scene phase. It is launched on demand and torn down when idle or when its
  domain is removed [B1].
- The app's lock paths (Lock button, quick action, foreground deadline check, timer while briefly
  alive in background) all run in the app process, and the quick action launches the app. So an
  app-driven teardown is reliable whenever the app runs.
- When the app is suspended its timers don't run (`SessionRegistry` already re-checks on return).
  So the extension must enforce the deadline itself. Cryptomator does this lazily: on every access
  the extension checks `lastUsedDate + duration` and locks [C10].

### Teardown sequence (app side, inside `SessionRegistry.lock()`)

1. XPC `lock()` to the extension: flush, zeroize keys (`VaultSession.lock()`), close the fd, delete
   materialized files, reply. Best effort with a short timeout.
2. `NSFileProviderManager.remove(domain)`.
3. Delete `documentStorageURL/<domain path>` from the app, then enumerate the group container and
   check nothing is left. Report a failure in the unlocked-vault UI, never silently.
4. A Darwin notification `dev.norsehorse.vaultpony.lock` as belt and braces: no payload, reaches
   the extension if it is running [B2].
5. Sweep on app launch and on every `sceneDidBecomeActive`: if no session exists, remove any
   leftover VaultPony domains and directories. This covers crashes and jetsam of the app itself.

### Extension-side enforcement

- The handoff carries the policy:
  - the auto-lock delay;
  - the app's background timestamp as a `mach_continuous_time()` value, which is system-wide and
    keeps counting during sleep, unlike wall-clock time;
  - the absolute cap.
- Checks run at every entry point (enumerator, `item(for:)`, `startProvidingItem`, `itemChanged`,
  imports, service calls) plus a `DispatchSourceTimer` while alive.
- On expiry:
  - zeroize and close;
  - delete its own materialized files (the app group directory is writable from the extension);
  - `signalEnumerator(for: .rootContainer)` so Files re-enumerates and gets `notAuthenticated`;
  - if allowed from the extension, remove its own domain. **[verify on device]** that
    `NSFileProviderManager.remove` works when called from the extension on iOS; Apple says the
    manager is usable "from either your app or your File Provider extension" [A15], but that is
    not specific to domain removal.
- Extension at init: if it has no keys, delete everything under its domain directory before
  answering anything. This catches plaintext left behind by a killed extension.
- Residual risk to document: if both the app and the extension are dead, materialized plaintext
  that was not yet deleted stays on disk under `.complete` until either process runs again. Deleting
  on `stopProvidingItem` keeps that set small.
- The extension can't observe device lock reliably (`UIApplication` protected-data notifications
  aren't available in extensions **[guess]**). `.complete` protection covers the data side.

### Product decision needed (batched below)

With "Immediately" (the default) the vault locks the moment the user switches to Files, so the
domain is useless. Options:
1. Files access is offered only with a delay of at least 1 minute.
2. Files activity extends the deadline (idle timeout measured from the later of app background and
   last Files activity), with a hard cap of 1 hour after the app was last in front (D5 spirit).

Cryptomator measures from last use [C10]. Pick one before building.

---

## 6. Write path

### How edits arrive (non-replicated)

| Event | Callback | VaultPony action |
|---|---|---|
| Open from Files / another app | `startProvidingItem(at:)` | Stream decrypt with `read_at` in 256 KiB chunks into the materialized file (protection `.complete`, backup-excluded) |
| App edits in place | `itemChanged(at:)` | Coalesce (debounce about 1 s), coordinated read of the file, stream into the vault |
| Save to Files / drag in / Photos "Save to Files" | `importDocument(at:toParentItemIdentifier:)` | Security-scoped read of the source URL, stream into the vault |
| New folder, rename, move, delete | `createDirectory`, `renameItem`, `reparentItem`, `deleteItem` | Direct core calls. No trash: leave out the trashing capability so Files deletes immediately |
| No longer used | `stopProvidingItem(at:)` | Delete the materialized file |

The replicated equivalents would be `fetchContents` and `createItem`/`modifyItem` with a `contents`
URL. The same streaming applies; it is not recommended (section 1).

### Streaming into the vault, consistent if killed

- Write to `".vaultpony-<rand>.part"` in the target folder (the same pattern as rc1 imports, see
  THREAT_MODEL-IOS section 2). Loop: read 256 KiB from the plaintext URL, then `write_at`. Then
  `flush()`, then replace the target.
- FAT has no atomic rename-over. Options:
  - **core `replace(src, dst)`**: swap the two directory entries' first-cluster and size, then
    unlink `src`. That is one directory-sector write in the common case. Preferred.
  - shell sequence `remove(dst)` then `rename(src, dst)`, plus a recovery sweep at session adopt:
    a `.part` whose final name is missing gets renamed in, a `.part` whose final name exists gets
    deleted. For this the `.part` name has to encode the target (it lives inside the encrypted
    volume, so this leaks nothing).
- iOS `fsync` does not flush the drive cache; `F_FULLFSYNC` does. This only matters for power loss,
  not process kill (the kernel already has the data). Low priority.
- Outer-protected sessions: a write into the hidden area latches read-only. Return a write error
  and drop the write capabilities from items; never lose data silently (plan 4.1 rule).
- Read-only sessions (NTFS, TrueCrypt in rc5): items get read-only capabilities.

### Conflicts and versions

The non-replicated model has no version vectors. When a file is materialized, record (size, mtime,
generation) in extension memory. In `itemChanged`, if the vault copy changed meanwhile (an in-app
edit), keep both: write the Files edit as `name (1).ext` (D6 spirit). After in-app changes the app
calls `signalEnumerator(for: parent)` so Files refreshes **[guess at exact semantics; verify]**.

### Blocker: two writers

The app's in-app session and the extension's session are two independent mounts of one FAT/exFAT
volume, and each caches allocation state:
- exFAT `BitmapCache`;
- fatfs FSInfo free count and next-free hint;
- the per-session `space()` cache.

Cryptomator never hits this because its app has no in-app browser; only the extension touches vault
contents. Options:

1. **Gate plus remount (recommended).** A lock file in the app group, `flock` around each logical
   operation, holding a generation counter. Before an operation, if the generation changed since
   this process's last operation, call core `VaultSession.remount()`, which drops the VFS and
   reopens it over the same `DecryptedDevice`. After a mutation plus flush, bump the generation.
   `Vault.swift` is already the single bridge for every core call, so the gate goes there and both
   targets share it. Rules:
   - Never hold the lock across suspension: a suspended app holding a file lock in a shared
     container is killed with `0xdead10cc` [F10].
   - Take the lock per operation, and cover background work with `beginBackgroundTask`, as the
     rc1 import path already does.
2. **Single owner.** While the domain exists, the extension owns the only session and the app's
   browser becomes an XPC client of it. That gives one writer and keys in one process. But it means
   a big refactor of the in-app browser, viewer and media paths. A killed extension would also lock
   the vault in the app's face, and the 20 MB limit would apply to in-app streaming. Not for rc3.

My reading of the code is that fatfs 0.3 has no block cache of its own (VaultPony's `io.rs` adds
none), so a remount is enough for coherence. **[verify with a two-process interleaved-write test,
then open the result in VeraCrypt]**

---

## 7. Deniability

### What persists, and what VaultPony controls

| Leak | Control |
|---|---|
| Domain display name in Files > Locations | Constant generic name ("VaultPony"), never the vault file name. Cryptomator uses the vault name; don't copy that |
| Domain identifier and `pathRelativeToDocumentStorage` | Random per unlock (UUID), so nothing links two unlocks or names a container |
| Item identifiers (cached by Files, may show up in Files' own DBs) | Derive per unlock: `id = base64url(nonce ‖ AES-GCM(k, path))` with `nonce = HMAC(k, path)[..12]` and `k` random per unlock, pushed with the keys. Stable while unlocked, so a relaunched extension still resolves ids; meaningless after lock. Mirrors Android D14 |
| Recents, Favorites, Tags (provider-side) | Return an **empty working set**, and make `setLastUsedDate`, `setTagData` and `setFavoriteRank` no-ops. Apple: the working set feeds Recents and tagged items, and "the device's Spotlight database also indexes the working set's contents" [A6]. Cost: no Files search inside the vault |
| Spotlight | Covered by the empty working set [A6] |
| Thumbnails | Leave `fetchThumbnails` unimplemented in rc3 (generic icons). **[verify on device]** whether Files or QuickLook still generates and caches thumbnails from *materialized* files on its own; forensic studies of Files did not cover thumbnails [D2] |
| Hidden vs outer volume | Same display name and id scheme, no marker, so nothing distinguishes them |
| Materialized plaintext | Section 1/5: `.complete`, `isExcludedFromBackup`, delete on `stopProvidingItem`, wipe at lock, sweep at start |

### Outside VaultPony's control (document in THREAT_MODEL-IOS)

- The Files app keeps its own database (`smartfolders.db` in the DocumentManager app group, table
  `fp_folder_item` with used dates) and "On My iPhone" trash folders [D1][D2]. Whether it records
  folder names from third-party domains is **[verify]**.
- A user dragging a vault folder to Files Favorites creates a system-side entry.
- Apps that copied a file keep their copy.
- That VaultPony offers a Files location is visible from the app itself, as it is today.

Forensic check for the spike:
- create a file with a unique canary name, open it from Files in another app, then lock;
- make an encrypted local backup and a sysdiagnose, and grep both for the canary;
- inspect the app group container. Daemon databases outside backups can't be checked without a
  jailbroken device, so say so in the threat model.

---

## 8. App Review risk

- **Precedent:**
  - Cryptomator ships a File Provider plus a FileProviderUI extension for encrypted vaults on the
    App Store, open source [C1][C5].
  - Disk Decipher, a VeraCrypt client on the App Store, advertises "the File Provider integration
    across apps" [E1].
  - Boxcryptor did this before it was discontinued (historical, not re-verified here).
- **No documented File Provider-specific rejection pattern turned up.** Evidence is thin. Risks I
  would plan for:
  - **2.1 completeness:** with auto-lock "Immediately" the location vanishes when the reviewer
    switches to Files, and they may report it as broken. Review notes need a demo container,
    password and exact steps (unlock, enable "Show in Files", set auto-lock, Files > Browse >
    VaultPony).
  - **Crashes over 20 MB:** a reviewer opening a large video from Files must not jetsam the
    extension. Test with multi-GiB files.
  - **Opening the host app from the FPUI extension** with private tricks: don't.
  - **Privacy manifests:** Apple requires one per bundle that contains an executable using
    required-reason APIs [A16]. The extension is its own bundle (file timestamps through `stat`,
    disk space). The app currently has no `PrivacyInfo.xcprivacy`; check App Store Connect warnings
    and add one for app and extension.
  - **New bundle IDs, App Groups capability and provisioning** for
    `dev.norsehorse.vaultpony.FileProvider` and `.FileProviderUI`. Export compliance is unchanged
    (already `true`).
  - **4.3(a) history:** the August rejection was about design spam, not extensions. Keep the "no
    secret vault marketing" framing from THREAT_MODEL-IOS section 7.

---

## Recommended architecture for rc3

**Model.** `NSFileProviderExtension` (non-replicated) with domains, plus a small FileProviderUI
extension. App group `group.dev.norsehorse.vaultpony` used only for
- `documentStorageURL` (materialized files),
- the gate lock file.

Nothing else about vaults goes in the group.

**Unlock flow (app).**
1. Unlock as today.
2. If "Show in Files" is on, the session is not no-trace, not copy-mode, and the container is not
   inside VaultPony's own domain:
   - add a domain with a random identifier and path and the generic display name;
   - `export_handoff()`;
   - connect to the service;
   - `adopt(blob, FileHandle(dup(fd)), idKey, policy)`.
3. Re-push on every foreground while the session lives.

**Extension.**
- Keys, fd and the id key in memory only.
- Every callback goes through: deadline check, then gate, then core.
- Empty working set, no thumbnails, no trash.
- Streaming I/O in 256 KiB chunks.
- `.complete` plus backup-excluded materialization, deleted on `stopProvidingItem`.
- Wipe on init if it has no keys.
- `notAuthenticated` from the root enumerator when it has no keys.

**FPUI extension.** `prepare(forError:)` shows "Open VaultPony to unlock this vault" and a Done
button. No password entry in rc3.

**Lock.** XPC lock, then `remove(domain)`, then the app deletes the domain directory and verifies,
then the Darwin notification, then a sweep at launch and foreground. The extension enforces the
deadline itself when the app is suspended.

**Write path.** `.part`, then `flush`, then core `replace`; recovery sweep at adopt; keep-both on
conflict; protected and read-only sessions expose no write capabilities.

**No-trace mode.** Files access is off entirely (plan 4.2), explained in the setting.

### Core FFI changes (VaultPonyCore first, minor bump, all three app repos move the submodule)

1. `vc_io::FileDevice`: positional I/O (`read_exact_at`/`write_all_at`). **Required.**
2. `VaultSession.export_handoff() -> Vec<u8>` and `VaultSession.import_fd(fd, blob)`, with the
   container fingerprint, versioning and zeroizing (section 3). Fixture tests for every scheme and
   cascade, hidden, protected-outer, and the wrong-container refusal.
3. `VaultSession.remount()`: drop and reopen the VFS over the same decrypted device.
4. A memory profile (`set_low_memory(true)` or an unlock option): exFAT bitmap cache cap of about
   1 to 2 MiB, and a smaller `read_at` cap.
5. `VaultSession.replace(src, dst)` plus a `.part` recovery helper. Shell-side fallback possible.
6. A THREAT_MODEL.md section arguing key export across processes, before any of 2 lands.

---

## Open risks

1. The 20 MB limit is from forum answers (2023, 2025), not documentation. The real headroom after
   the Swift, FileProvider and Rust baseline is unknown. Measure first; if the baseline alone is
   near 20 MB, rc3 is in trouble.
2. Extension idle-kill timing decides how often users see "Open VaultPony to unlock".
3. Service connections launching the extension and `FileHandle` over the FP service connection are
   strongly suggested by Cryptomator and Apple DTS, but not documented for this exact use.
4. Two-writer coherence: `remount()` must truly drop all cached FS state; needs a stress test
   validated in VeraCrypt.
5. System-side residue (Files databases, thumbnail caches, open-file orphans) can't be fully
   inspected without a jailbreak; the threat model must say so.
6. Large files: the full decrypt on open is slow and doubles plaintext on disk for multi-GiB videos.
   Consider warning above a size.
7. Path-based item ids change on rename/move (FAT has no stable inode). An app with the file open
   loses track after a rename. Accept for rc3.
8. Calling `remove(domain)` from inside the extension on iOS is unverified.

## Questions for NorseHorse (batched)

1. Files access with auto-lock "Immediately": require a minimum delay, or let Files activity extend
   the deadline with a hard cap of 1 hour (section 5)?
2. Should "Show in Files" be a per-unlock checkbox on the unlock screen, or a Settings toggle?
3. OK to skip thumbnails, Recents and Files search for vault items in rc3 (deniability over
   convenience)?
4. OK to exclude copy-mode sessions (containers whose provider won't give a writable fd) from Files
   access in rc3?
5. Confirm: no Keychain key cache (option b) in 2.0.0.
6. The plan's 4.2 names the replicated model; this spike recommends non-replicated. Confirm the
   switch so Rev 6 of the plan can record it as a decision.

## Rough task breakdown (rc3 only; one RC at a time)

Sizes: S under a day, M one to three days, L more.

**Phase 0 on-device spike (do first, gate the rest):**
- S1 (M): minimal non-replicated extension plus app group. Log `os_proc_available_memory()` at idle
  and after `import_fd` with a stub core on iOS 17 and 18.
- S2 (S): app-to-extension service call when the extension is not running; `FileHandle` passing;
  `isRestricted` default.
- S3 (S): idle-kill timing; `notAuthenticated` to FPUI flow.
- S4 (S): bookmark-in-extension fallback per location (On My iPhone, iCloud Drive, a third-party
  provider).
- S5 (S): domain removal with a file open in another app; canary forensic check.

**Core (VaultPonyCore):**
- C1 (S): positional I/O in `FileDevice`.
- C2 (L): handoff export/import plus fingerprint, FFI, fixture tests, THREAT_MODEL section.
- C3 (M): `remount()` plus a two-session interleaved-write test validated by VeraCrypt.
- C4 (S): low-memory profile.
- C5 (M): `replace()` plus `.part` recovery.

**iOS:**
- I1 (M): `project.yml` targets (FileProvider, FileProviderUI), app group, shared bridge sources,
  privacy manifests, entitlements, bundle IDs.
- I2 (L): extension items and enumeration (encrypted ids, paging), `startProvidingItem` and
  `stopProvidingItem`, capabilities from `VolumeFacts`, empty working set.
- I3 (L): write path (`itemChanged` debounce, `importDocument`, create, rename, move, delete,
  keep-both, protected-volume errors).
- I4 (M): service protocol (adopt, lock, status) and app-side connector; re-push on foreground.
- I5 (M): gate (`flock` plus generation) in `Vault.swift`; `signalEnumerator` after in-app changes.
- I6 (M): lock path and sweeps in `SessionRegistry`; extension deadline enforcement.
- I7 (S): FPUI "Open VaultPony" screen.
- I8 (S): "Show in Files" UI, no-trace and copy-mode exclusions, Settings footer text on plaintext.
- I9 (M): THREAT_MODEL-IOS.md new section (plaintext materialization, key handoff, residue).
- I10 (M): localization of all new strings in en, de, es, fr, ru, pt-BR, as a multi-agent pass
  with a final sweep.
- I11 (L): device test matrix: three storage locations; Pages/Numbers edit in place; Photos and
  Save to Files; multi-GiB video; kill the extension mid-write, then check in VeraCrypt; lock while
  a file is open elsewhere; iPad drag and drop and split view (app and Files active together);
  container inspection after lock.
- I12 (S): App Review notes and screenshots for the new extension.

---

## Sources

Apple documentation
- [A1] NSFileProviderExtension: https://developer.apple.com/documentation/fileprovider/nsfileproviderextension
- [A2] Replicated File Provider extension: https://developer.apple.com/documentation/fileprovider/replicated-file-provider-extension
- [A3] NSFileProviderManager.remove(_:mode:completionHandler:) (iOS 16+): https://developer.apple.com/documentation/fileprovider/nsfileprovidermanager/remove(_:mode:completionhandler:)
- [A4] NSFileProviderManager.DomainRemovalMode: https://developer.apple.com/documentation/fileprovider/nsfileprovidermanager/domainremovalmode
- [A5] evictItem(identifier:completionHandler:) (EBUSY on open fds): https://developer.apple.com/documentation/fileprovider/nsfileprovidermanager/evictitem(identifier:completionhandler:)
- [A6] Synchronizing the File Provider Extension (working set, Spotlight): https://developer.apple.com/documentation/fileprovider/synchronizing-the-file-provider-extension
- [A7] NSFileProviderPartialContentFetching (macOS 12.3+ only): https://developer.apple.com/documentation/fileprovider/nsfileproviderpartialcontentfetching
- [A8] FSKit (macOS 15.4+ only): https://developer.apple.com/documentation/fskit
- [A9] NSFileProviderServiceSource: https://developer.apple.com/documentation/fileprovider/nsfileproviderservicesource
- [A10] Apple Platform Security, Data Protection classes: https://support.apple.com/guide/security/secb010e978a/web
- [A11] NSFileProviderService: https://developer.apple.com/documentation/foundation/nsfileproviderservice
- [A12] NSFileProviderManager.getService(named:for:) (iOS 16+): https://developer.apple.com/documentation/fileprovider/nsfileprovidermanager/getservice(named:for:completionhandler:)
- [A13] NSFileProviderServiceSource.isRestricted (iOS 16+): https://developer.apple.com/documentation/fileprovider/nsfileproviderservicesource/isrestricted
- [A14] FPUIActionExtensionViewController: https://developer.apple.com/documentation/fileproviderui/fpuiactionextensionviewcontroller
- [A15] NSFileProviderManager: https://developer.apple.com/documentation/fileprovider/nsfileprovidermanager
- [A16] Describing use of required reason API: https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api

Apple Developer Forums
- [F1] Upload file in File Provider Extension in iOS (20 MB, Apple engineer, Oct 2023): https://developer.apple.com/forums/thread/739839
- [F2] Download a file in a File Provider Extension in iOS (Apple DTS, Nov 2023): https://developer.apple.com/forums/thread/741225
- [F3] File Provider Extension Memory Limit of 20MB is really limiting (2025): https://developer.apple.com/forums/thread/804378
- [F4] iOS file provider extension services (DTS, Jul 2024): https://developer.apple.com/forums/thread/760865
- [F5] File Provider supportedServices (DTS, Jun 2025, `restricted`): https://developer.apple.com/forums/thread/788641
- [F6] Cross process URL bookmark (Apple DTS: pass an fd, not a bookmark): https://developer.apple.com/forums/thread/785735
- [F7] Send a file descriptor via Obj-C XPC API (Apple DTS, 2016): https://developer.apple.com/forums/thread/51592
- [F8] How to trigger authentication in a File Provider Extension (2017): https://developer.apple.com/forums/thread/91693
- [F9] Share security scoped bookmark in app group? (macOS, 2016): https://developer.apple.com/forums/thread/66259
- [F10] 0xdead10cc prevention (file locks during suspension): https://developer.apple.com/forums/thread/126438
- FileProvider IPC (app to extension via root container URL, 2022): https://developer.apple.com/forums/thread/711923
- Using NSFileProviderReplicatedExtension on iOS (2022): https://developer.apple.com/forums/thread/710116

Engineering posts and precedent
- [B1] Third-party engineering write-up on building a FileProvider sync client (process per domain, invalidate on idle, XPC services). Link omitted (personal site).
- [B2] Darwin notify center (cross-process, no payload): https://developer.apple.com/documentation/corefoundation/cfnotificationcentergetdarwinnotifycenter()
- [C1] Cryptomator iOS source (`FileProviderExtension: NSFileProviderExtension`, `NSExtensionFileProviderDocumentGroup`): https://github.com/cryptomator/ios
- [C2] Cryptomator `VaultDBManager.removeFileProviderDomain` (remove domain, then delete domain dir): same repo, `CryptomatorCommon/Sources/CryptomatorCommonCore/Manager/VaultDBManager.swift`
- [C3] Cryptomator `LocalURLProvider` sets `isExcludedFromBackup`: same repo, `CryptomatorFileProvider/LocalURLProviderType.swift`
- [C4] Cryptomator forum, decrypted cache in backups (Dec 2021): https://community.cryptomator.org/t/security-issue-with-ios-app-cache-mechanism-and-icloud-backup/9167
- [C5] Cryptomator iOS docs, Vault Management (memory limit, Unlock Duration, Keychain): https://docs.cryptomator.org/ios/vault-management/
- [C6] Cryptomator `FileProviderConnector.getXPC` (`fileProviderServicesForItem` on the domain storage URL): `CryptomatorCommon/Sources/CryptomatorCommonCore/FileProviderXPC/FileProviderConnector.swift`
- [C7] Cryptomator `VaultUnlockingServiceSource.unlockVault(kek:)` and `UnlockVaultViewModel.getKEK` (KDF in FPUI, KEK over XPC): `CryptomatorFileProvider/ServiceSource/VaultUnlockingServiceSource.swift`, `FileProviderExtensionUI/UnlockVaultViewModel.swift`
- [C8] Cryptomator `CryptomatorKeychain` (`AfterFirstUnlockThisDeviceOnly`): `CryptomatorCommon/Sources/CryptomatorCommonCore/CryptomatorKeychain.swift`
- [C9] Cryptomator `LocalFileSystemBookmarkManager` (app-created bookmark resolved in extension): `CryptomatorCommon/Sources/CryptomatorCommonCore/LocalFileSystem/LocalFileSystemBookmarkManager.swift`
- [C10] Cryptomator `FileProviderAdapterManager.getAdapter` (lazy auto-lock on access): `CryptomatorFileProvider/FileProviderAdapterManager.swift`
- [D1] Magnet Forensics, Exploring the Files App in iOS: https://www.magnetforensics.com/blog/exploring-the-files-app-in-ios/
- [D2] d20 Forensics, iOS Files App Part Deux: https://blog.d204n6.com/2020/09/ios-files-app-part-deux-quick-images.html
- [E1] Disk Decipher on the App Store: https://apps.apple.com/app/id516538625
