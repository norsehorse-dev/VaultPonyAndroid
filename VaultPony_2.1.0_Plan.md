# VaultPony 2.1.0 plan

Started 2026-11-22. Android first. The version number is a working name: read-write ext4 was already earmarked for 2.1, so these items either ride with it or go out earlier as a smaller update.

## 1. Camera zoom (asked for by a user, 2026-11-22)

Today the in-app camera takes photos and video at the default zoom only.

### Where the code stands

- `ui/CameraScreen.kt` binds CameraX with `p.bindToLifecycle(owner, selector, preview, ic)` (and the video variant) and drops the returned `Camera`. Without it there is no `cameraControl` or `cameraInfo`, so nothing can set or read zoom.
- CameraX 1.4.2 is already a dependency. No new library is needed.

### Plan

1. Keep the `Camera` returned by `bindToLifecycle` in state. Clear it on unbind.
2. Pinch on the preview sets `cameraControl.setZoomRatio(...)`, clamped to `cameraInfo.zoomState` min and max.
3. A small row of zoom steps above the shutter (for example 0.5x, 1x, 2x, 5x), showing only the steps inside the range the device reports. Tapping one sets that ratio. The current ratio is shown while pinching.
4. Works the same in photo and video mode, including while recording.
5. Zoom resets to 1x when switching between front and back camera.
6. Touch targets 48dp, labels from string resources, all six languages.

### Decided 2026-11-23

- Both pinch and zoom steps.
- Tap to focus goes in the same pass.
- The zoom ratio carries over a Photo/Video switch. A front/back switch resets it to 1x.
- iOS zoom: first set for the same release, then put on hold the same day. Android goes ahead without it.

### Status

- 2026-11-23: Android done. Unit tests and the device checks passed (zoom kept across Photo/Video and across the background, reset on a lens switch, pinch, steps, zoom while recording, tap to focus). `ui/CameraScreen.kt` keeps the `Camera`; pinch, zoom steps, a ratio readout while pinching and tap to focus with a ring. Step logic and number formatting are in `CameraZoom.kt` with `CameraZoomTest`. Two new strings in all six languages: `camera_zoom_step`, `camera_zoom_desc`.
- Steps offered: the widest ratio when it is below 1x, then 1x, 2x, 5x, 10x as far as the phone's maximum goes. The row is hidden when there is only one step.
- CameraX resets zoom to 1x on every rebind and when the app returns from the background, and hands back the same `Camera` object for the same lens. The screen sets the wanted ratio again after both. This came out of the source review and held up on the device.
- iOS zoom is on hold (decided 2026-11-23, after the Android pass). Not started.

### Notes

- With the default back camera selector, zoom below 1x reaches the ultrawide lens only on phones whose camera stack exposes it as one logical camera. On others the minimum is 1x and the 0.5x step is hidden.
- Test on at least one multi-lens phone and one single-lens phone, photo and video, front and back.

## 2. Import speed for large batches (reported by a user, 2026-11-22)

Report: importing about 2,900 photos was noticeably slower than another vault app. Not an extreme difference.

### Where the code stands

- `VaultRepository.importFile` creates the entry, writes the file in 1 MiB chunks through the FFI, then calls `session.flush()`.
- `flush` reaches `sync_data()` on the container file (`VaultPonyCore/core/vc-io/src/lib.rs`). So a batch of 2,900 photos does 2,900 syncs to storage, one per file.
- This is the likely cost, read from the code. It has not been measured.

### Code reading, 2026-11-23 (corrects the section above)

- `VaultRepository.importFile` has no callers. Batch imports (`importFiles`, `importShared`, `importTree`) all go through `TransferJobs.runImport` and `copyIn`, which already flush every 32 files or 64 MiB (`FLUSH_EVERY_FILES`, `FLUSH_EVERY_BYTES`), not per file.
- The syncs come from inside the core. Every `disk.flush()` in `norse-exfat/src/write.rs` reaches `sync_data()`. On exFAT: `create` syncs once, `write_file` twice per 1 MiB chunk (data, then the size entry), `rename` twice. `copyIn` writes a temp file and renames it, so a 3 MB photo is about 9 syncs and 2,900 of them about 26,000. On FAT, `write_at` syncs once per chunk (`vc-fs/src/fat.rs`).
- Directory work grows with the folder: each chunk write resolves the path and scans the parent directory (`locate`), and `create` and `rename` each read the whole directory several times (`heal_dir_at`, `locate`, `find_or_make_slot_run`). With thousands of files in one folder this is quadratic.
- None of this is measured yet. The measurement has to time inside the core (sync count and time, directory scan time, cipher and write time) as well as the Kotlin side (source read, FFI call, explicit flush).

### Measurement build, 2026-11-23 (not committed)

- Core: `vc_io::stats` counts reads, writes and syncs on the container file with bytes and time, plus time in the cipher. Exposed through the FFI as `io_stats()` and `io_stats_reset()`.
- App: debug builds time each step of `runImport` and `copyIn` (source open and read, create, write, the rename into place, the explicit flushes) and log three lines under the tag `VaultPonyImport` when a batch ends. Counts, sizes and times only.
- To run: 500 photos into one empty folder, once in an exFAT vault and once in a FAT vault.

### Measured, 2026-11-23

Phone: moto g play (2026), Android 16, debug build, AES vaults.

- Six photos (18.7 MB) into a 1 GB FAT vault: 3.3 s. Cipher 2,244 ms (67%), opening the sources 403 ms, 31 syncs 202 ms (6%), container writes 84 ms, source read 68 ms. 22,905 container writes and 49,845 reads of 512 bytes.
- 483 of 500 photos (1.5 GB) into the root of a 16 GiB FAT vault, cancelled after 2 h 41 min: cipher 9,080 s (94% of the time), 2,440 syncs 20 s, container writes 4 s. 409 million reads of 512 bytes (209 GB read to store 1.5 GB). Time per file went from under 1 s to 5 s at about 130 files, then up by about 14 s for every 100 files.
- The same sequence run against the core on a desktop build (a real container, cipher included) reproduces the read counts, so the findings below come from that.

### Findings

1. Bug, not only speed: on FAT32 the vault's top folder is not cleared when it grows past its first cluster. `fatfs` clears a new directory cluster only when the directory has an entry of its own, and the FAT32 root has none (`file.rs`, the `alloc_cluster` call in `write`). Inside an encrypted container the uncleared cluster is random bytes, which read back as hundreds of junk files and folders. After 200 files in the root the listing held 2,155 entries, 1,955 of them junk. A subfolder is not affected, nor is FAT16 (small vaults) or exFAT. Every new file then needs a fresh cluster because the junk leaves no free run of entries, which is what made the import crawl.
2. FAT reads directory and FAT entries a field at a time, and each of those reads decrypts a whole 512-byte unit. With bug 1 fixed in a test copy, 500 files still cost 20.8 million reads, growing by about 160 reads per file already in the folder for each file added. At 2,900 files in one folder that is about 460,000 reads per file.
3. The cipher is slow on the phone: about 22 microseconds per 512-byte unit, roughly 23 MB/s. The build does not turn on the `aes` crate's ARMv8 path (`--cfg aes_armv8`), so AES runs in software. Not yet confirmed on the device.
4. exFAT is flat: about 450 reads and 9 syncs per 3 MB file at file 1 and at file 500. The syncs are the per-chunk ones noted above.
5. The explicit flush every 32 files in `runImport` is not a cost on either filesystem.

### Decided 2026-11-23, after the numbers

- Fix finding 1 in the core now.
- The fix ships in 2.1.0 with the rest, not as a separate 2.0.1.
- A repair for vaults that already have junk in their top folder goes into the same release.
- Speed: a read buffer under the FAT layer first, then the ARMv8 AES build flag as its own step.
- The per-chunk syncs on exFAT stay as they are.

### Order of work (one step at a time, each built and tested before the next)

1. Core: clear new FAT32 root directory clusters (`third_party/fatfs/src/file.rs`), regression test `core/vc-fs/tests/fat_root_growth.rs`, note 6 in `VAULTPONY.md`. Done 2026-11-23, committed in the core. On the phone: 322 photos (1.0 GB) into the top folder of a 3 GB FAT vault in 334 s, a clean listing, 10.2 million reads (cipher 281 s, syncs 11 s). The Android repo's submodule pin moves to the new core commit at release.
2. Repair for affected vaults: 2a core repair, then 2b the damage notice. See the repair design below.
3. Read buffer under the FAT layer: a block cache in `vc-fs/src/io.rs` (16 KiB blocks, at most 1 MiB, zeroed when dropped). Small reads are served from it, writes go to the device first and then patch it, reads of a block or more bypass it. On a desktop build with a real container, the reads to import a 3 MB file into a folder of 500 went from 79,601 to 1,536 and no longer grow with the folder. On the phone, 2026-11-24: the same 322 photos into a new 3 GB FAT vault took 122 s, down from 334 s. Reads fell from 10.2 million to 984,000 and cipher time from 281 s to 80 s. Syncs are unchanged at 10 s. Photos open from the new vault and from the repaired one. Done, committed in the core.
4. ARMv8 AES build flag: `--cfg aes_armv8` in the Android build's RUSTFLAGS and, for every other arm64 build of the core (the Mac's own `cargo test`, desktop, iOS), in `VaultPonyCore/.cargo/config.toml`. The `aes` crate 0.8 runs AES in software on arm64 without it. Done 2026-11-24, committed in both repos. On the phone the same 322 photos took 45 s, down from 122 s, with cipher time down from 80 s to 2.7 s. The cipher tests pass on the Mac through the hardware path, and vaults written by the old build still open.
5. Measurement code, decided 2026-11-24: keep the core counters (`vc_io::stats`, `io_stats()`), committed in the core, and the timing summary that debug builds log after an import. The per-step trace lines and the debug trigger for the damage notice are removed. Done 2026-11-24.
6. Release prep, Android: version 2.1.0 (versionCode 13), `fastlane/metadata/android/en-US/changelogs/13.txt`, core version 2.1.0. Done 2026-11-24, committed. The submodule pin to the core's v2.1.0 tag comes after the core is signed, tagged and pushed.

### Repair design (step 2), agreed 2026-11-24

What an affected top folder looks like: its first cluster is clean. Every later cluster was noise when it was added. Real entries sit among the noise, wherever the noise happened to hold an end-of-directory mark at the time they were written. Reading stops at the first end mark that is left, and everything after it is noise nobody has seen yet, which would surface as more junk the next time a file is added.

Telling junk from real. An entry is junk when it breaks a rule no FAT writer breaks:

- a reserved attribute bit is set, or
- its first cluster lies outside the volume, or
- it has a size but no first cluster, or is a folder with a size, or
- its 8.3 name holds a control character or a character FAT forbids.

Noise passes all four about once in 80,000 entries. A long-name record is kept only when it belongs, by sequence and checksum, to a real entry right after it. An entry that passes the four rules is then checked against the FAT: its cluster chain must exist and match its size. If that fails it is kept and counted as unverified, never removed, because it could be a real file that was damaged.

What the repair writes, in this order:

1. Zero everything after the first end mark, to the end of the folder's clusters. Nothing reads that area today.
2. Mark each junk entry before it as deleted (first byte 0xE5). No entry is moved, no cluster is freed, real entries are not touched.

Each write stands alone, so a power cut at any point leaves a valid folder with less junk, and running it again finishes the job.

Where it lives: a small module in `vc-fs` that works on the block device before `fatfs` mounts, FAT32 only, top folder only. It costs one pass over the top folder at unlock. A read-only vault is left as it is.

Decided 2026-11-24:

- It runs by itself when a vault is unlocked for writing. No prompt.
- Junk is marked deleted in place. The folder is not compacted.
- An entry that passes the format rules but has a broken cluster chain is kept.
- The user is told when real files look damaged. After a repair the whole vault is walked once, and files and folders whose cluster chain is broken (clusters freed, or shared with another file) are listed. This can happen when someone deleted a junk entry under 2.0.0. The repair cannot bring that data back.

Status:

- 2a, core: `vc_fs::fat_repair`, run from `open_volume` before the FAT mount. Report through `Vfs::take_repair_report()`. Tests in `core/vc-fs/tests/fat_root_repair.rs` build volumes the old way with `FsOptions::legacy_unzeroed_root`. Done 2026-11-24, committed in the core; unit tests and the device check on the damaged 16 GiB vault passed. Also checked against a real container: a 3 GB FAT vault with 200 files and 2,947 junk entries in its top folder lists exactly the 200 files after one unlock.
- 2b, the notice: `Session::take_repair_report()` and FFI `takeRepairReport()` in the core; on Android `ui/RepairNotice.kt`, a one-time dialog listing the damaged files, shown from the browser and never over an import or export dialog; three strings in six languages. Done 2026-11-24 on Android, committed in both repos. iOS and desktop later.
- The notice lives in memory only, so it is gone if the vault locks before OK is tapped, and the next unlock has nothing to report. Decided 2026-11-24 to leave it that way: the names can be selected and copied from the dialog, and the app does not write a file the user did not ask for. The core names at most 50 files.
- The debug-only sample notice used to test the dialog is removed (2026-11-24).

### Plan as first written

1. Measure first: time a fixed batch (for example 500 photos) on a device, with a breakdown of read, write and flush time.
2. If the flush dominates: in batch imports (`TransferJobs.importFiles`, `importShared`, `importTree`) flush every N files or every few seconds, and always at the end, on cancel and before the vault locks.
3. If the write path dominates instead: look at the chunk copy across the FFI (`buf.copyOf`, 1 MiB chunks) and at directory updates in the exFAT layer for folders with thousands of entries.
4. Single-file writes (camera capture, Files app writes) keep flushing per file.

### Decided 2026-11-23

- Measure on both exFAT and FAT: 500 photos into one folder, each.
- Whether the core's per-chunk syncs change is decided after the numbers are in.
- Ships as 2.1.0 with both stages.

### Open decisions

- The durability trade-off: with batched flushes, a crash or power loss mid-import can lose the files written since the last flush. The source files are untouched in that case, so the import can be run again. Pick the interval (files, seconds, or bytes).
- Whether "move" imports (delete the source after import) must wait for a flush before deleting the source. Proposal: yes, always.

## 3. Encrypt single files with age or PGP from the file browser (idea, not agreed)

A user asked for per-file encryption outside containers. Out of scope as a built-in feature for now. A cheap version to weigh: a "Send to AgePony / PGPony" action in VaultPony's browser that hands the file to the other app. Waiting on the user's reaction before planning anything.

## 4. Settings screen (added 2026-11-24, done and checked on a device the same day)

The old screen was one long page: ten radio buttons (vault list, auto-lock) always open, then languages, seven pony apps and five links, under small uppercase labels.

### Decided 2026-11-24

- A short main page of rows with dividers, each showing its current value: Vault list, Auto-lock, Hide screen contents (the whole row toggles), Language, Show the intro again, About.
- Vault list, Auto-lock and Language each open their own page, with the explanation text there. The header title and back arrow follow the page, and system back returns to the main page.
- The pony apps, links, version and family line move to an About page. Rows that leave the app carry an opens-outside icon.
- The uppercase section label is replaced in Settings only. The other screens keep it until a separate app-wide UI pass.
- Ships in 2.1.0, before the tag.
- Strings: `settings_about` and `settings_about_version` added in six languages; the three unused section header keys removed.
- StegPony, TapPony and HashPony added to the Pony apps list, and "Pony" is capitalized as a name in all six languages.
