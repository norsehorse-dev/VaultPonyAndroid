# VaultPonyAndroid

VeraCrypt-compatible encrypted vaults on Android. Create and open encrypted
containers, including hidden volumes, backed by the shared Rust core
(VaultPonyCore). Kotlin and Jetpack Compose over a UniFFI boundary.

- Create and open VeraCrypt-compatible containers; pick cipher, hash, and
  filesystem (FAT or exFAT).
- Hidden volumes, with write protection for the outer volume.
- Header backup and recovery, change password, and keyfiles.
- Biometric unlock, auto-lock on background, hidden screen, and a no-trace mode.
- Localized in English, German, Spanish, French, Russian, and Brazilian
  Portuguese, with live in-app switching.

## Building

Prereqs: Android Studio or the SDK with NDK r27, rustup with the toolchain
pinned by VaultPonyCore, the Android Rust targets, and cargo-ndk:

    rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi
    cargo install cargo-ndk

The Rust core is a separate repo (VaultPonyCore), resolved as a sibling checkout
at ../VaultPonyCore when present, otherwise the git submodule at ./VaultPonyCore.
Clone with --recursive, or after cloning run:

    git submodule update --init --recursive

Then build and install:

    ./gradlew installDebug

Gradle builds the core with cargo-ndk and regenerates the UniFFI bindings on each
build; both outputs are gitignored. Set VAULTPONY_CORE to point at a core
checkout in a non-default location.

## License

Apache-2.0. See LICENSE. VaultPony is not affiliated with or endorsed by IDRIX;
VeraCrypt is a registered trademark of IDRIX.
