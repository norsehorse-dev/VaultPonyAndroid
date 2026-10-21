# VaultPony 2.0.0 Plan

Rev 4, 2026-10-21. Scope decided (section 0). Section 10 holds what is still open; section 11
covers issues #4 to #6.

VaultPony 2.0.0 is the release that makes VaultPony work as an everyday EDS Lite
replacement, not just a correct VeraCrypt reader. It starts from the three points in
issue #3 (hidden volume clarity, auto-lock, bulk import), which were answered publicly
with a commitment to the next release, and builds out from there: the vault as a
writable location in the system file manager on Android and iOS, plus a set of extras.

Platforms: Android (this repo), iOS (`~/Apps/VaultPony`), and Desktop
(`~/Apps/VaultPonyDesktop`). All three ship as 2.0.0. Core changes land in
VaultPonyCore first, never patched from an app repo.

Baseline: Android 1.1.0 (versionCode 7), iOS 1.1.0, Desktop 1.1.0 (workspace version),
core with Argon2id.

---

## 0. Decisions (2026-10-16)

- D1. Headline: the vault as a writable location in the system file manager.
- D2. iOS File Provider extension ships in 2.0.0 (it was deferred post-v1 in the iOS
  PLAN.md as P8). Android writable `DocumentsProvider` likewise.
- D3. Desktop joins 2.0.0 and takes the version number.
- D4. Extras in 2.0.0: share into vault, media playback, browser upgrades (grid with
  in-memory thumbnails, sort, search, multi-select), recents privacy, keyfiles in Change
  Password, camera capture into the vault, TrueCrypt mode.
- D5. Auto-lock delay tops out at 1 hour on every platform. Android gets the iOS list
  minus "Never", and iOS drops "Never" so both match. Immediately stays the default.
- D6. Bulk import name collisions default to keep both (`photo (1).jpg`), nothing lost.
- D7. TrueCrypt mode is in 2.0.0 (scope in 6.2).
- D8. Out of 2.0.0: LUKS, EncFS, growing a container.
- D9. TrueCrypt volumes get a "Convert to VeraCrypt" flow in 2.0.0.
- D10. Android camera: photos and video with sound (CAMERA + RECORD_AUDIO, each
  requested at first use).
- D11. iOS video capture may use a short-lived temp file if `AVAssetWriter` can't stream
  into the vault (handling in 5.6).
- D12. iOS large imports pause when the app is backgrounded and resume on return if the
  vault is still unlocked.
- D13 (2026-10-19, rc2). A running copy keeps only its own vault unlocked until it ends;
  the "A vault is unlocked" notification shows only in the background and not during a
  copy; Desktop gets a tray "Lock all"; core errors are shown as translated messages.
- D14 (2026-10-19, rc2). Android provider document ids come from a random token minted
  at every unlock, so no grant survives a lock and nothing stored elsewhere reveals the
  container or volume.
- D15 (2026-10-21, issue #4). Android gets an auto-lock option "Until I lock it". It is
  only offered while notifications are allowed, the unlocked notification always shows
  while it applies, and picking it shows a warning. iOS stays capped at 1 hour.
- D16 (2026-10-21, issues #5 and #6). Cipher cascades show under VeraCrypt's names
  ("AES-Twofish-Serpent", "Camellia-Kuznyechik"), and unlock gets an optional hash
  choice. Both land in rc4.

---

## 1. Where 1.1.0 stands on the issue #3 areas

| Area | Android 1.1.0 | iOS 1.1.0 |
|---|---|---|
| Opening the hidden volume | Works (hidden password alone in the main field), but nothing in the app says so. The only labelled hidden path is "This vault has a hidden volume; protect it", which opens the outer volume | Same gap. Toggle reads "Outer volume, protect hidden" |
| Which volume is open | Not shown. `VolumeFacts` has scheme, prf, filesystem, writable only | Not shown |
| Free space | Not shown, and the core has no API for it | Not shown |
| Auto-lock | Fixed: locks on any background/screen-off (`VaultPonyApp` onStop -> `SessionRegistry.lockAll`). Settings row is static text | Configurable: 30 s, 1/5/15 min, 1 h, Never |
| Unlock screen survives app switch | No. `lockAll` always bumps `lockGeneration`, and `VaultPonyRoot` sends the UI home even with nothing unlocked | Check in rc1 |
| Autofill on password fields | No. Compose BOM 2024.12.01 (Compose 1.7) predates `ContentType` semantics | Check `.textContentType` is set |
| Import | Single file (`OpenDocument`) | Multi-select, no folders |
| Export | Single file (`CreateDocument`) | Share sheet / move to Files |
| System file manager | `VaultDocumentsProvider`: read-only root, `require(mode == "r")`. Root disappears on app switch because of auto-lock | None (File Provider was P8) |

Desktop already mounts volumes as real drives (behind the `fuse` feature), so its
"file manager" story exists; it joins 2.0.0 for the shared items (section 7).

### Bug found while reading: the create-screen hidden warning is backwards

`create_hidden_warning` says: "Keep the outer vault light, and only ever fill it when you
unlock with the outer password alone." Unlocking with the outer password alone is the mode
where writes are NOT protected and can overwrite the hidden volume. The safe way to put
decoy files in the outer volume is the protect-hidden mode (both passwords), which is also
VeraCrypt's guidance. Fix the English string and all five translations in rc1; check the
iOS and Desktop equivalents.

---

## 2. Issue #3 work (committed publicly)

### 2.1 Hidden volume clarity

- Create screen: under the hidden-volume switch, two plain lines: the hidden password
  alone opens the hidden vault; both passwords together open the outer vault with the
  hidden area protected. Replace the backwards warning.
- Unlock screen: rename the protect option so it says what it does ("Open the outer
  volume and protect a hidden one"), with a one-line note that a hidden volume opens by
  typing its password in the normal field. Same text on every unlock screen whether or
  not the container has a hidden volume, so the screen leaks nothing.
- Browser: "Hidden volume" label when the hidden volume is open, "Hidden area protected"
  in protect mode (the user typed both passwords, so nothing is revealed). A plain outer
  unlock shows no label.
- No in-app switch from outer to hidden. Lock and unlock with the other password.
- Onboarding "Hidden volumes" page gets the same one-line explanation.

### 2.2 Free space and volume info

- Browser header: used / free / total.
- "Volume info" sheet: scheme, PRF, filesystem, size, free, read-only state, the label
  above, and the 4 GiB per-file limit when the filesystem is FAT.

### 2.3 Auto-lock

- Android: Immediately (default) / 30 s / 1 / 5 / 15 min / 1 h (D5). iOS: remove "Never";
  anyone with "Never" saved migrates to 1 h, noted in the release notes.
- Android implementation: on process `onStop`, record `SystemClock.elapsedRealtime()` and
  schedule the lock. On `onStart`, cancel; if the deadline already passed (the cached-app
  freezer can stall the timer), lock before showing anything. Screen-off counts as
  backgrounded, as today.
- Only navigate home when something was actually locked, so an unlock screen keeps its
  fields across an app switch.
- Threat-model note: with a delay, the master key stays in process memory while
  backgrounded. Document it next to the setting and in SECURITY.md / THREAT_MODEL.

### 2.4 Autofill

- Android: bump the Compose BOM to one with `ContentType` semantics (Compose 1.8+). Unlock
  and hidden-password fields `ContentType.Password`; create and change-password fields
  `NewPassword`. PIM is not marked (Q3).
- Confirm the FLAG_SECURE hidden-screen setting doesn't block autofill (test KeePassDX and
  Bitwarden).
- iOS: `.textContentType(.password)` / `.newPassword` on the matching fields.

### 2.5 Bulk import and export

- Android multi-select (`OpenMultipleDocuments`).
- Folder import everywhere: Android `OpenDocumentTree` walking
  `buildChildDocumentsUriUsingTree`; iOS `fileImporter` with `.folder` and a
  security-scoped walk; Desktop folder picker.
- Built for thousands of files: streaming chunked copy through `write_at`, one progress UI
  (files and bytes), cancel that stops after the current file and leaves the vault
  consistent, then `flush()`.
- Pre-flight before writing: total size against free space, files over 4 GiB on FAT,
  names illegal on FAT/exFAT (`\ / : * ? " < > |`, trailing dots or spaces) with a
  sanitize-and-report pass, path-length limits.
- Collisions: keep both by default (D6), with skip and replace as per-batch alternatives.
- Folder export (recursive) on all three.
- Long jobs survive backgrounding (4.3).

---

## 3. Core changes (VaultPonyCore)

1. `VolumeFacts` grows `hidden` (header `position.is_hidden()`), `outer_protected`,
   `total_bytes`, `free_bytes`. Also `truecrypt: bool` (6.2).
2. `Vfs::space()` per filesystem. FAT: count free clusters (FSInfo is only a hint), cached
   per session and updated on allocate/free. exFAT: popcount of the allocation bitmap,
   same caching. Tested against VeraCrypt-made fixtures.
3. A streaming `copy_in` helper so the three shells don't each reimplement chunked copy and
   the core refuses early on space (decide in rc1).
4. TrueCrypt mode (6.2).
5. Core minor bump; all three app repos move their submodule pointer together.

---

## 4. Headline: the vault in the system file manager

### 4.1 Android: writable DocumentsProvider

- Extend `VaultDocumentsProvider` with `createDocument`, `deleteDocument`,
  `renameDocument`, `moveDocument`, and `openDocument` in "w"/"rw" through the existing
  `openProxyFileDescriptor` path (`onWrite` -> `write_at`, `onFsync`/`onRelease` ->
  `flush`). Flags: `FLAG_SUPPORTS_CREATE` on the root; `FLAG_SUPPORTS_WRITE`,
  `FLAG_SUPPORTS_DELETE`, `FLAG_SUPPORTS_RENAME`, `FLAG_SUPPORTS_MOVE`,
  `FLAG_DIR_SUPPORTS_CREATE` on documents; `FLAG_SUPPORTS_THUMBNAIL` with thumbnails made
  in memory only.
- No plaintext touches disk on VaultPony's side: the proxy fd streams from the core.
  (The consuming app may copy; that's outside VaultPony's control and goes in the threat
  model.)
- Outer-protected sessions already turn read-only when a write would hit the hidden area.
  The provider reports that as a write error, never silent loss.
- Read-only sessions and TrueCrypt volumes (read-only, 6.2) expose no write flags.

### 4.2 iOS: File Provider extension

The big new piece. Notes for the Phase 0 spike, each a real risk:

- Model: `NSFileProviderReplicatedExtension`, one domain per unlocked vault, added with
  `NSFileProviderManager.add` on unlock and removed on lock.
- Plaintext on disk. A replicated provider materializes files the user opens into
  system-managed storage, so decrypted copies exist on disk while the vault is unlocked.
  Mitigations: remove the domain with `.removeAll` on lock and on auto-lock, mark items
  so the system evicts eagerly, and keep the extension off entirely when no-trace mode is
  on. This is a threat-model change for THREAT_MODEL-IOS.md and has to be explained to
  users in the setting itself.
- Separate process, no shared session. The extension needs the vault's keys without the
  app's memory. Options: (a) the extension unlocks itself with the password via a
  FileProviderUI authentication screen; (b) the app hands over derived keys through an
  app-group Keychain item that lives only while unlocked (`ThisDeviceOnly`, deleted on
  lock). (b) is smoother but puts key material in the Keychain; (a) is cleaner but needs
  the KDF to fit in the extension (Q1).
- Memory ceiling. File Provider extensions have a much smaller memory limit than the app.
  Argon2id at VeraCrypt's PIMs can need hundreds of MiB (`argon2_memory_mib`), which may
  not fit; another reason to prefer handing over derived keys. Measure in the spike.
- Container access. The container is a security-scoped bookmark held by the app; the
  extension must resolve it from the app group. Verify bookmarks for iCloud Drive and
  third-party providers resolve inside the extension.
- Auto-lock: the extension has no scene phase. The app's lock path (timer, lock action,
  background expiry) removes the domain, which tears down the extension's session.
- iPad drag and drop works against the domain for free once it exists.

### 4.3 Lock controls and long operations

- Android: ongoing "Vault unlocked" notification with a Lock action whenever a vault
  stays unlocked in the background, and a Quick Settings tile "Lock vaults".
- Android: bulk import/export in a foreground service (`dataSync`) with progress in the
  same notification. Auto-lock waits for the job, then starts its timer.
- iOS: Home Screen quick action "Lock vaults". Large imports pause when the app goes to
  the background (finishing the current file inside a short background task) and resume
  on return if the vault is still unlocked; if it locked meanwhile, the import reports
  what was copied and offers to continue after unlock (D12).
- Desktop: lock-all in the tray/menu if the app has one (Q2).

---

## 5. Extras (D4)

### 5.1 Share into a vault

- Android: `ACTION_SEND` / `ACTION_SEND_MULTIPLE` target. Pick or unlock a vault, pick a
  folder, import with the 2.5 pipeline. No new permissions.
- iOS: share extension as a hardened hand-off to the app (the AgePony 5.0.1 pattern: the
  extension never touches a vault). Large shares need the item handed over without
  copying it whole into the extension's memory.

### 5.2 Media playback

Stream video and audio from the vault with no temp file. Android: Media3 ExoPlayer with a
`DataSource` over `read_at` (or `MediaDataSource` for `MediaPlayer`). iOS:
`AVAssetResourceLoaderDelegate` over `read_at`. Seeking must map to `read_at` offsets
without reading ahead the whole file. Desktop: rely on the mount and the OS player, or an
in-app player (Q2).

### 5.3 Browser upgrades

Grid view with thumbnails generated in memory and never cached to disk, sort (name, size,
date), search (current folder and whole vault), multi-select for delete, export, move.

### 5.4 Recents privacy

Setting: remember vaults on the home screen (default, as today), remember without file
names, or don't remember. With "don't remember", existing bookmarks are deleted, not just
hidden.

### 5.5 Keyfiles in Change Password

Android says keyfile vaults need the CLI. Close the gap on Android, check iOS and Desktop.

### 5.6 Camera capture into the vault

- Android: in-app capture with CameraX writing straight to a vault file through the core
  (photos from an in-memory buffer; video through a pipe or `ParcelFileDescriptor` to
  `write_at`, never a temp file in app storage). New permissions: CAMERA and
  RECORD_AUDIO (D10), each runtime-requested only when first needed (audio only when
  recording video).
- iOS: `AVCaptureSession` with photo output from memory. Video via `AVAssetWriter` needs a
  file URL; try fragmented MP4 segments (`AVAssetWriter` delegate output,
  `outputFileTypeProfile` `.mpeg4AppleHLS`) streamed into the vault first. If that can't
  work, a temp file is allowed (D11): written in the app's own container with
  `NSFileProtectionComplete`, excluded from backup, copied into the vault and deleted as
  soon as recording stops, and swept on every launch in case of a crash mid-recording.
  Document the window in THREAT_MODEL-IOS.md.
- Store listings and privacy pages note the new permission and that captures never reach
  the gallery or Photos.

---

## 6. Formats

### 6.1 Unchanged

VeraCrypt containers with every PRF and scheme the core supports today. LUKS, EncFS, and
container growth are out of 2.0.0 (D8).

### 6.2 TrueCrypt mode (D7)

VeraCrypt 1.26 dropped TrueCrypt mode, so EDS Lite users with old TrueCrypt containers
have nowhere to open them. Scope:

- Header magic `TRUE` alongside `VERA`; TrueCrypt's PBKDF2 iteration counts (RIPEMD-160
  2000, SHA-512 1000, Whirlpool 1000) and its header version fields. Hidden TrueCrypt
  volumes too.
- RIPEMD-160 comes back in vc-crypto for the TrueCrypt path only. Today
  `derive_header_key("RIPEMD-160", ...)` is rejected on purpose and a test asserts it;
  that stays true for VeraCrypt headers.
- Read-only. Writing would mean maintaining a format upstream abandoned.
- "Convert to VeraCrypt" (D9): from an unlocked TrueCrypt volume, create a new VeraCrypt
  container (size defaulting to the TrueCrypt volume's, user picks password, PRF, and
  scheme) and copy everything across with the 2.5 pipeline, then verify file counts and
  sizes. The TrueCrypt container is never modified or deleted by the app; the user removes
  it when satisfied. A TrueCrypt hidden volume converts as its own new container; the
  app says plainly that hidden-volume structure doesn't carry over.
- An unlock checkbox "TrueCrypt mode", like VeraCrypt's, so ordinary unlocks don't pay for
  the extra KDF attempts.
- Fixtures made with TrueCrypt 7.1a (the last real release), covering each PRF, cascades,
  and a hidden volume.

---

## 7. Desktop in 2.0.0 (D3)

Desktop takes the shared items: hidden-volume clarity, volume info and free space,
auto-lock delay to match (if Desktop auto-locks today; check), folder import/export with
the same collision default, recents privacy, keyfiles in change password, TrueCrypt mode
(core), browser sort/search. Its file-manager headline is the existing mount. Not on
desktop: share into vault, camera. Media playback and tray lock depend on Q2.

---

## 8. Release shape

One RC at a time; nothing from the next RC is written before the current one is built and
tested. Each RC covers all three platforms where its items apply.

- rc1: issue #3 (2.1 to 2.5), core facts and space (3.1 to 3.3), the warning fix,
  auto-lock and autofill, folder import/export. This is what issue #3 was promised.
- rc2: Android writable provider, lock controls, foreground service (4.1, 4.3).
- rc3: iOS File Provider extension (4.2), after a Phase 0 spike on keys, memory, and
  bookmark access.
- rc4: share into vault, media playback, browser upgrades, recents privacy, keyfiles in
  change password (5.1 to 5.5), plus issues #4 to #6 (section 11).
- rc5: camera capture (5.6) and TrueCrypt mode (6.2).

Release paths unchanged: Android through the reproducible Docker harness (builds from the
pushed tag; F-Droid picks up the tag); iOS through App Store Connect without "veracrypt" in
keywords (the File Provider extension and the camera need new review notes); Desktop
through its RELEASING.md.

Localization: every new string in en, de, es, fr, ru, pt-BR on all three platforms.
Planned as a multi-agent pass per RC with a final sweep for strings left in English.

Public credit: issue #3 by number only. No user names in release notes, changelogs,
commits, or this document.

---

---

## 9. Testing

- Core: `space()` against FAT and exFAT fixtures (empty, partly full, fragmented) and after
  create/write/remove cycles; `VolumeFacts.hidden`/`outer_protected` for all three unlock
  modes; TrueCrypt fixtures for every PRF, cascade, and hidden volume.
- Import: 2,000+ small files and a multi-GiB file; cancel mid-batch, remount, and check the
  filesystem with VeraCrypt on desktop; illegal-name and over-4-GiB pre-flight.
- Auto-lock: each delay with app switch, screen off, process freeze, and process death;
  iOS "Never" migration.
- Providers: create, write, rename, move, delete from the Files apps; protect mode
  refusing writes into the hidden area with a visible error; iOS domain removal on lock
  leaving no materialized files behind (inspect the device container).
- Autofill: KeePassDX and Bitwarden on Android; iCloud Keychain and a third-party manager
  on iOS.
- Media: seek across a multi-GiB video without memory growth.
- Camera: no file appears in the gallery, Photos, or app caches after capture.
- Cross-tool: every vault touched by 2.0.0 opens cleanly in VeraCrypt 1.26.x.

---

## 10. Open questions

Q1. iOS File Provider keys: extension unlocks itself (password via FileProviderUI) or the
app hands over derived keys through a short-lived app-group Keychain item? Decide after
the Phase 0 spike measures the extension's memory limit against Argon2id.

Q2. Desktop extras: in-app media player, and a tray/menu "Lock all"?

Q3. PIM fields stay out of autofill. Confirm.

---

## 11. Issues #4 to #6 (opened 2026-10-21)

### 11.1 Issue #4: reaching the unlocked vault from the Files app

What happened: on 1.1.0 every vault locks the moment VaultPony leaves the foreground, so
the "VaultPony" root disappears from the system Files app as soon as the user switches to
it, and the Settings auto-lock row was static text. rc1 (auto-lock delay up to 1 hour) and
rc2 (writable Files root, lock controls, per-unlock document ids) already fix the reported
behaviour. What is left is the request to keep a vault reachable for an unlimited time.

Plan (D15), Android only:
- Settings > Auto-lock gains "Until I lock it" below "After 1 hour".
- Offered only when notifications are allowed (POST_NOTIFICATIONS on 13+ and the "Unlocked
  vaults" channel enabled). If notifications are later turned off, the setting reads back
  as "After 1 hour" and says why.
- Picking it shows a confirmation: the vault's key stays in memory and other apps can read
  and write the vault until you lock it from the notification, the tile or the app.
- While it applies, the "A vault is unlocked" notification always shows when the app is
  in the background (including during a copy, where the copy notification already carries
  Lock).
- Process death still locks (sessions live in memory), and a reboot locks.
- iOS keeps the 1 hour cap (D5): iOS suspends the app anyway. Desktop has its own idle
  lock; no change.
- SECURITY.md: describe the option and its trade-off.
- Website: the "reach the unlocked vault system-wide from Files" claim is only true from
  2.0.0 on; update the site copy at release.
- The reporter offered to test; invite them to the Android RC.

### 11.2 Issues #5 and #6: Camellia-Kuznyechik and AES-Twofish-Serpent

Both cascades are already supported for opening and creating on every platform: the core
implements all 15 VeraCrypt schemes (`Camellia(Kuznyechik)` and `AES(Twofish(Serpent))`
in the core's nested naming), and both are in the VeraCrypt-made fixture matrix. Unlock
detects the cipher by itself; the "Key derivation" picker the reports point at is the KDF
family (Auto, PBKDF2, Argon2id), not the cipher. Picking a cipher would not make unlocking
faster: trying a wrong cipher is cheap, deriving a header key with the wrong hash is not.

Plan (D16):
- Names: VeraCrypt's display names everywhere a user sees a scheme (create pickers, Volume
  info, desktop CLI output): "AES-Twofish-Serpent", "Camellia-Kuznyechik", and so on. Add a
  display-name field to the core registry and an FFI function returning it; the internal
  names stay the API and the on-disk logic is untouched.
- Hash choice on unlock: an optional "Hash" picker under Key derivation, like VeraCrypt's
  mount dialog: Auto (default), SHA-512, SHA-256, BLAKE2s-256, Whirlpool, Streebog,
  Argon2id. Core: extend the unlock filter so a single PRF can be requested (today
  KdfFilter is Auto / PBKDF2-only / Argon2id-only); UniFFI change, so Android, iOS and
  Desktop move together. The existing KDF picker and the new one should become one control
  if that reads better on small screens (decide in rc4 design).
- Remembered per vault with the existing "found KDF" memory (Face ID / biometric unlock
  already stores which KDF a vault uses), so the second unlock is fast without any choice.
- Tests: unlock each fixture with its exact hash and with a wrong single hash (must fail
  fast with the normal wrong-password error); display names match VeraCrypt's list.
- Reply on both issues: supported already, auto-detected; the names and the hash choice
  come in 2.0.0.
