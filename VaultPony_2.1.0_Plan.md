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

### Plan

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
