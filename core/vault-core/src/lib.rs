//! Session layer: unlock flow, mount table, auto-lock timers, and the path
//! API the shells (Android/iOS/desktop/CLI) consume (planning doc §5).
//!
//! Owns every secret lifecycle: passphrases and keys live exactly as long
//! as a session needs them and are zeroized on lock (doc §11).

pub mod probe;
pub mod session;

pub use probe::{probe, HeaderSource, VolumeInfo};
pub use session::{create_container, Session, SessionId};
pub use vc_format::CreateParams;

/// Core version string, threaded through to every shell's about screen and
/// used by the FFI walking skeleton to prove the toolchain end to end.
pub fn core_version() -> String {
    format!("vault-core {}", env!("CARGO_PKG_VERSION"))
}

#[cfg(test)]
mod tests {
    #[test]
    fn version_is_populated() {
        assert!(super::core_version().starts_with("vault-core 0."));
    }
}
