# VaultPony Android (P3, read-only)

Kotlin + Compose shell over the shared Rust core. Zero permissions — no
INTERNET, no storage; pure SAF (doc §8).

## One-time setup

```
rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi
cargo install cargo-ndk
```

Android Studio (or `sdkmanager`) must have an NDK installed; `cargo-ndk`
finds it via `ANDROID_NDK_HOME` or the SDK's default location.

## Build

Open `android/` in Android Studio, or:

```
cd android
./gradlew assembleDebug
```

The `cargoBuild` Gradle task cross-compiles `vault-ffi` for all three ABIs
with the 16 KB page-size link flag (Play requirement, the PGPony 4.0.1
lesson) and drops the `.so`s into `app/src/main/jniLibs/`. The
`generateBindings` task then regenerates `uniffi/vault_ffi/vault_ffi.kt`
from the built library. Both directories are gitignored — generated, never
committed.

No gradle wrapper is committed yet: generate it with a locally-installed
Gradle 8.10+ (`gradle wrapper`) and commit the wrapper per the F-Droid
reproducibility plan (doc §14) — the wrapper must be pinned in-tree before
the first tagged release.

## Standing gates (doc §8)

- Minified release verified on real hardware before any tester build
  (R8 + UniFFI/JNA keep rules are in `proguard-rules.pro`, but the gate is
  the device, not the rules).
- Any new permission or OS-facing surface is a THREAT_MODEL.md review
  event.
- FLAG_SECURE stays default-on; the relax toggle ships with the settings
  screen, not before.

## Layout

| Path | What |
|---|---|
| `MainActivity` | SAF picker (`*/*`), FLAG_SECURE, open-with entry |
| `VaultRepository` | The only FFI call site; fd dup/detach contract |
| `SessionRegistry` | App-side mount table; one lock path |
| `ui/UnlockScreen` | Password + optional PIM, per-PRF progress |
| `ui/BrowserScreen` | Read-only browser |
| `provider/VaultDocumentsProvider` | System-wide Files integration, proxy-fd streaming |
