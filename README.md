# VaultPony

Open and edit VeraCrypt® file containers on Android, iOS, and desktop —
zero network, FOSS, one Rust core.

Not affiliated with or endorsed by IDRIX. VeraCrypt is a registered
trademark of IDRIX.

**Status: pre-release scaffolding. Nothing here is public yet.**

## Layout

| Path | What |
|---|---|
| `core/vc-types` | Header layout, cipher/PRF registry (generated), geometry, errors |
| `core/vc-format` | Header discovery, candidate search, decrypt/validate, backup headers |
| `core/vc-crypto` | PBKDF2 header-key derivation, XTS engine incl. cascades |
| `core/vc-io` | Block-device trait: `std::fs` file (desktop) and raw-fd (mobile) |
| `core/vc-fs` | VFS trait + adapters: `fatfs` (RW), `norse-exfat`, `ntfs` (RO) |
| `core/norse-exfat` | Our exFAT implementation (read first; write is its own release) |
| `core/vault-core` | Sessions, unlock flow, mount table, auto-lock |
| `core/vault-ffi` | UniFFI boundary → Kotlin + Swift bindings |
| `core/vaultpony-cli` | Headless CLI; doubles as test harness and support tool |
| `tools/gen-fixtures` | Matrix generator + fixture corpus builder (fixtures are the spec) |

Shells: `android/` (Kotlin + Compose, P3 read-only — see its README);
`ios/` and `desktop/` land with their phases. `third_party/fatfs` is a
patched vendor copy (see its VAULTPONY.md for why and when it dies).

## Build

```
cargo test --workspace --locked
```

Toolchain is pinned in `rust-toolchain.toml`; `Cargo.lock` is committed; CI
and release builds always use `--locked`.

Generate mobile bindings:

```
cargo build -p vault-ffi
cargo run -p vault-ffi --features cli --bin uniffi-bindgen -- generate --library target/debug/libvault_ffi.so --language kotlin --language swift --out-dir bindings/
```

## Design

The planning doc is the source of truth for scope and phasing. Security
posture: see `THREAT_MODEL.md`. Format compatibility matrix: generated from
a pinned VeraCrypt source checkout by `tools/gen-fixtures/gen_matrix.py` —
never hand-edited.

## License

Proposed split (pending final decision): core crates Apache-2.0, apps
GPL-3.0. Clean-room implementation from published format documentation and
independent crates only — no VeraCrypt/TrueCrypt source is used or linked.
