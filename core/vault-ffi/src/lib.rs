//! UniFFI boundary — generates the Kotlin and Swift bindings (doc §5).
//!
//! Surface (P3): unlock a container from a dup'd file descriptor, browse,
//! read file content in chunks, lock. The fd contract keeps every platform
//! storage API (SAF, security-scoped URLs) on the shell side; the core
//! sees only a file descriptor it owns.
//!
//! Secret handling at this boundary (doc §5, §11): the passphrase arrives
//! as foreign-allocated memory, is wrapped in `Zeroizing` immediately, and
//! the Rust copy is wiped when unlock returns. Auditing the *generated*
//! glue for lingering foreign-side copies is a standing pre-release task
//! (tracked in THREAT_MODEL.md review gates). No error message crossing
//! this boundary carries names, paths, or key material.

use std::sync::Mutex;
use vc_types::VcError;
use zeroize::{Zeroize, Zeroizing};

uniffi::setup_scaffolding!();

/// Core version string for about screens; also the walking-skeleton probe.
#[uniffi::export]
pub fn core_version() -> String {
    vault_core::core_version()
}

/// Create a brand-new container over `fd` (a freshly-created, writable SAF
/// document the caller hands over) and format an empty FAT filesystem inside,
/// so it opens as a usable volume. `scheme`/`prf` are registry names
/// (e.g. "AES", "SHA-512").
#[uniffi::export]
pub fn create_container(
    fd: i32,
    size: u64,
    passphrase: String,
    pim: u32,
    keyfiles: Vec<Vec<u8>>,
    scheme: String,
    prf: String,
) -> FfiResult<()> {
    let passphrase = Zeroizing::new(passphrase);
    let scheme = vc_types::registry::ENCRYPTION_SCHEMES
        .iter()
        .find(|s| s.name == scheme)
        .ok_or(VaultError::Internal)?;
    let prf = vc_types::registry::PRFS
        .iter()
        .find(|p| p.name == prf)
        .ok_or(VaultError::Internal)?;

    // A SAF-created document starts empty; size it so create_volume's writes
    // land at real offsets. Best-effort — create_volume's tail writes extend
    // the file anyway if the provider rejects ftruncate.
    {
        use std::os::fd::{FromRawFd, IntoRawFd};
        let f = unsafe { std::fs::File::from_raw_fd(fd) };
        let _ = f.set_len(size);
        let _ = f.into_raw_fd(); // keep the fd open; RawFdDevice owns it next
    }

    // SAFETY: same fd-ownership contract as unlock_fd / RawFdDevice.
    let dev = unsafe { vc_io::RawFdDevice::from_raw_fd(fd) };
    let params = vault_core::CreateParams {
        scheme,
        prf,
        pim,
        passphrase: passphrase.as_bytes(),
        keyfiles: &keyfiles,
        size,
        sector_size: 512,
    };
    vault_core::create_container(Box::new(dev), &params)?;
    Ok(())
}

/// User-explainable unlock/browse failures. Deliberately coarser than the
/// core's error type: shells branch on these, they don't diagnose.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum VaultError {
    #[error("wrong password/PIM, or not a VeraCrypt container")]
    NotFoundOrWrongPassword,
    #[error("container requires a newer format version than supported")]
    VersionTooNew,
    #[error("system-encryption volumes are not supported")]
    SystemVolume,
    #[error("write blocked to protect the hidden volume; the volume is now read-only")]
    HiddenVolumeProtected,
    #[error("volume header is damaged; the embedded backup header may help")]
    HeaderDamaged,
    #[error("filesystem not supported yet: {name}")]
    UnsupportedFilesystem { name: String },
    // Field is `detail`, not `message`: UniFFI generates each error variant as
    // a Kotlin class extending Exception, and a `message` field collides with
    // Throwable.message (conflicting-declaration / override errors).
    #[error("filesystem error: {detail}")]
    Filesystem { detail: String },
    #[error("I/O error: {detail}")]
    Io { detail: String },
    #[error("internal error")]
    Internal,
}

impl From<VcError> for VaultError {
    fn from(e: VcError) -> Self {
        match e {
            VcError::NotFoundOrWrongPassword => VaultError::NotFoundOrWrongPassword,
            VcError::VersionTooNew { .. } => VaultError::VersionTooNew,
            VcError::SystemVolume => VaultError::SystemVolume,
            VcError::HiddenVolumeProtected => VaultError::HiddenVolumeProtected,
            VcError::HeaderDamaged => VaultError::HeaderDamaged,
            VcError::UnsupportedFilesystem(name) => VaultError::UnsupportedFilesystem { name },
            VcError::UnknownFilesystem => VaultError::UnsupportedFilesystem {
                name: "unrecognized".into(),
            },
            VcError::Filesystem(detail) => VaultError::Filesystem { detail },
            VcError::Io(io) => VaultError::Io {
                // io::Error display strings don't carry paths for our
                // pread/pwrite usage; still, keep it to the kind.
                detail: io.kind().to_string(),
            },
            VcError::Internal(_) => VaultError::Internal,
        }
    }
}

type FfiResult<T> = Result<T, VaultError>;

#[derive(uniffi::Record)]
pub struct DirEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    pub mtime_ms: Option<i64>,
}

#[derive(uniffi::Record)]
pub struct VolumeFacts {
    pub scheme: String,
    pub prf: String,
    pub filesystem: String,
    pub writable: bool,
}

/// Unlock progress: candidate `step` of `total`, currently trying `prf`.
/// Fired from the unlock thread; implementations must be fast and must not
/// call back into the session.
#[uniffi::export(with_foreign)]
pub trait UnlockProgressListener: Send + Sync {
    fn on_progress(&self, step: u32, total: u32, prf: String);
}

/// One unlocked container. Thread-safe; operations serialize on an
/// internal lock. After `lock()` every call fails with `Internal`.
#[derive(uniffi::Object)]
pub struct VaultSession {
    inner: Mutex<Option<vault_core::Session>>,
}

fn with_session<T>(
    slot: &Mutex<Option<vault_core::Session>>,
    f: impl FnOnce(&mut vault_core::Session) -> FfiResult<T>,
) -> FfiResult<T> {
    let mut guard = slot.lock().map_err(|_| VaultError::Internal)?;
    match guard.as_mut() {
        Some(s) => f(s),
        None => Err(VaultError::Internal), // used after lock()
    }
}

#[uniffi::export]
impl VaultSession {
    /// Unlock a container from a file descriptor the caller has dup'd and
    /// hands over completely (Android: `ParcelFileDescriptor.detachFd()`).
    /// The session owns and closes it.
    #[uniffi::constructor]
    pub fn unlock_fd(
        fd: i32,
        passphrase: String,
        pim: u32,
        keyfiles: Vec<Vec<u8>>,
        listener: Option<std::sync::Arc<dyn UnlockProgressListener>>,
    ) -> FfiResult<std::sync::Arc<Self>> {
        let passphrase = Zeroizing::new(passphrase);
        // Fold any keyfiles into the passphrase exactly as the CLI does; an
        // empty list is the identity. The derived secret is `Zeroizing`, and
        // we wipe the raw keyfile bytes as soon as they've been mixed in.
        let mut keyfiles = keyfiles;
        let secret = vc_crypto::apply_keyfiles(passphrase.as_bytes(), &keyfiles);
        for kf in &mut keyfiles {
            kf.zeroize();
        }
        // SAFETY: ownership contract documented above and on RawFdDevice.
        let dev = unsafe { vc_io::RawFdDevice::from_raw_fd(fd) };
        let mut progress = |step: usize, total: usize, prf: &str| {
            if let Some(l) = &listener {
                l.on_progress(step as u32, total as u32, prf.to_string());
            }
        };
        let session =
            vault_core::Session::unlock_device(Box::new(dev), &secret, pim, &mut progress)?;
        Ok(std::sync::Arc::new(Self {
            inner: Mutex::new(Some(session)),
        }))
    }

    pub fn facts(&self) -> FfiResult<VolumeFacts> {
        with_session(&self.inner, |s| {
            let (scheme, prf) = (s.scheme().to_string(), s.prf().to_string());
            let vfs = s.vfs();
            Ok(VolumeFacts {
                scheme,
                prf,
                filesystem: format!("{:?}", vfs.kind()),
                writable: vfs.writable(),
            })
        })
    }

    pub fn list(&self, path: String) -> FfiResult<Vec<DirEntry>> {
        with_session(&self.inner, |s| {
            Ok(s.vfs()
                .list(&path)?
                .into_iter()
                .map(|e| DirEntry {
                    name: e.name,
                    is_dir: e.is_dir,
                    size: e.size,
                    mtime_ms: e.mtime_ms,
                })
                .collect())
        })
    }

    pub fn stat(&self, path: String) -> FfiResult<DirEntry> {
        with_session(&self.inner, |s| {
            let e = s.vfs().stat(&path)?;
            Ok(DirEntry {
                name: e.name,
                is_dir: e.is_dir,
                size: e.size,
                mtime_ms: e.mtime_ms,
            })
        })
    }

    /// Read up to `len` bytes at `offset`. Short only at end of file. This
    /// is the random-access primitive proxy file descriptors are built on
    /// (doc §8 — streaming without extraction).
    pub fn read_at(&self, path: String, offset: u64, len: u32) -> FfiResult<Vec<u8>> {
        // 8 MiB per call keeps a misbehaving caller from ballooning the
        // process; proxy fds read in much smaller chunks.
        let len = len.min(8 << 20) as usize;
        with_session(&self.inner, |s| {
            let mut buf = vec![0u8; len];
            let n = s.vfs().read_at(&path, offset, &mut buf)?;
            buf.truncate(n);
            Ok(buf)
        })
    }

    // -- Write surface (doc §7). Every mutation goes through the same locked
    // session; the caller flushes when a logical operation is complete so the
    // encrypted backing store is consistent. On a read-only filesystem (NTFS)
    // or a read-only backing fd these return an error rather than corrupting.

    /// Write `data` at `offset`, returning bytes written.
    pub fn write_at(&self, path: String, offset: u64, data: Vec<u8>) -> FfiResult<u32> {
        with_session(&self.inner, |s| Ok(s.vfs().write_at(&path, offset, &data)? as u32))
    }

    /// Create an empty regular file (errors if it already exists).
    pub fn create(&self, path: String) -> FfiResult<()> {
        with_session(&self.inner, |s| {
            s.vfs().create(&path)?;
            Ok(())
        })
    }

    /// Create a directory.
    pub fn mkdir(&self, path: String) -> FfiResult<()> {
        with_session(&self.inner, |s| {
            s.vfs().mkdir(&path)?;
            Ok(())
        })
    }

    /// Rename/move within the volume.
    pub fn rename(&self, from: String, to: String) -> FfiResult<()> {
        with_session(&self.inner, |s| {
            s.vfs().rename(&from, &to)?;
            Ok(())
        })
    }

    /// Remove a file or an empty directory.
    pub fn remove(&self, path: String) -> FfiResult<()> {
        with_session(&self.inner, |s| {
            s.vfs().unlink(&path)?;
            Ok(())
        })
    }

    /// Truncate (or extend) a file to `len` bytes.
    pub fn truncate(&self, path: String, len: u64) -> FfiResult<()> {
        with_session(&self.inner, |s| {
            s.vfs().truncate(&path, len)?;
            Ok(())
        })
    }

    /// Flush pending writes through every layer to the backing store. Call
    /// this once a logical operation (e.g. an import) is complete.
    pub fn flush(&self) -> FfiResult<()> {
        with_session(&self.inner, |s| {
            s.vfs().flush()?;
            Ok(())
        })
    }

    /// Zeroize keys through every layer and drop the filesystem. Idempotent.
    pub fn lock(&self) {
        if let Ok(mut guard) = self.inner.lock() {
            if let Some(s) = guard.take() {
                s.lock();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::IntoRawFd;

    fn fixture_fd() -> Option<i32> {
        let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../fixtures/aes-sha_512-fat-512-pim0-plain.vc");
        let f = std::fs::File::open(path).ok()?;
        Some(f.into_raw_fd())
    }

    #[test]
    fn unlock_browse_read_lock_through_the_ffi_types() {
        let Some(fd) = fixture_fd() else {
            eprintln!("SKIP: fixture corpus not present");
            return;
        };
        let s = VaultSession::unlock_fd(fd, "vaultpony-fixture".into(), 0, None).unwrap();
        let facts = s.facts().unwrap();
        assert_eq!(facts.scheme, "AES");
        assert_eq!(facts.filesystem, "Fat");

        let names: Vec<String> = s
            .list("/".into())
            .unwrap()
            .into_iter()
            .map(|e| e.name)
            .collect();
        assert!(names.contains(&"readme.txt".to_string()));

        let data = s.read_at("/readme.txt".into(), 0, 1024).unwrap();
        assert_eq!(data, b"VaultPony fixture tree v1\n");
        // Offset reads work (the proxy-fd primitive).
        let tail = s.read_at("/readme.txt".into(), 10, 1024).unwrap();
        assert_eq!(tail, b"fixture tree v1\n");

        s.lock();
        s.lock(); // idempotent
        assert!(matches!(s.list("/".into()), Err(VaultError::Internal)));
    }

    #[test]
    fn wrong_password_maps_to_the_right_ffi_error() {
        let Some(fd) = fixture_fd() else {
            eprintln!("SKIP: fixture corpus not present");
            return;
        };
        match VaultSession::unlock_fd(fd, "nope".into(), 0, None) {
            Err(VaultError::NotFoundOrWrongPassword) => {}
            Err(other) => panic!("wrong error: {other}"),
            Ok(_) => panic!("wrong password unlocked"),
        }
    }
}
