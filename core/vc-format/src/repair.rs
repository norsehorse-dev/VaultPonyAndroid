//! Header backup and restore (doc §6: backup-header restore is the single
//! highest-value support tool the format gives us, and §10: external
//! backup/restore rides along in the CLI).
//!
//! Layout facts: the leading 128 KiB of a container holds the primary
//! header region (64 KiB, header in its first 512 bytes) and the hidden
//! header slot (64 KiB). The trailing 128 KiB mirrors both as embedded
//! backups. Every region is indistinguishable-from-random ciphertext, so
//! backups carry no secrets beyond what the container itself does — but
//! they DO allow decrypting the volume with an *old* password after a
//! password change, which the CLI copy must (and does) warn about.

use crate::{find_header_at, FoundHeader, UnlockSecret};
use vc_types::{consts, HeaderPosition, VcError, VcResult};

/// Size of one header region (header + reserved randomness).
pub const HEADER_REGION: u64 = 65_536;
/// Size of the leading header group (primary + hidden slot) — the unit of
/// external backup.
pub const HEADER_GROUP: u64 = 2 * HEADER_REGION;

/// Export the leading header group (ciphertext, 128 KiB) for offline
/// safekeeping.
pub fn export_headers(dev: &mut dyn vc_io::BlockDevice) -> VcResult<Vec<u8>> {
    if dev.len()? < HEADER_GROUP {
        return Err(VcError::NotFoundOrWrongPassword);
    }
    let mut out = vec![0u8; HEADER_GROUP as usize];
    dev.read_at(0, &mut out)?;
    Ok(out)
}

/// In-memory device over an exported header group, for verification.
struct SliceDevice<'a>(&'a [u8]);

impl vc_io::BlockDevice for SliceDevice<'_> {
    fn len(&mut self) -> VcResult<u64> {
        Ok(self.0.len() as u64)
    }
    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> VcResult<()> {
        let o = offset as usize;
        if o + buf.len() > self.0.len() {
            return Err(VcError::Io(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "read past end of backup",
            )));
        }
        buf.copy_from_slice(&self.0[o..o + buf.len()]);
        Ok(())
    }
    fn write_at(&mut self, _: u64, _: &[u8]) -> VcResult<()> {
        Err(VcError::Internal("backup verification is read-only".into()))
    }
    fn flush(&mut self) -> VcResult<()> {
        Ok(())
    }
}

/// Restore the primary header from an exported backup file. The backup's
/// primary slot must unlock with `secret` before a single byte is written
/// (verify-then-write, never the reverse).
pub fn restore_from_file(
    dev: &mut dyn vc_io::BlockDevice,
    backup: &[u8],
    secret: &UnlockSecret<'_>,
) -> VcResult<FoundHeader> {
    if backup.len() != HEADER_GROUP as usize {
        return Err(VcError::Internal(format!(
            "backup must be {HEADER_GROUP} bytes, got {}",
            backup.len()
        )));
    }
    let found = find_header_at(
        &mut SliceDevice(backup),
        secret,
        &[HeaderPosition::Primary],
        &mut |_, _, _| {},
    )?;
    dev.write_at(0, backup)?;
    dev.flush()?;
    Ok(found)
}

/// Restore the primary header from the container's own embedded backup
/// (size − 128 KiB). Verifies the embedded backup unlocks with `secret`
/// first; on success copies its full 64 KiB region over the primary slot.
pub fn restore_primary_from_embedded(
    dev: &mut dyn vc_io::BlockDevice,
    secret: &UnlockSecret<'_>,
) -> VcResult<FoundHeader> {
    let found = find_header_at(
        dev,
        secret,
        &[HeaderPosition::BackupPrimary],
        &mut |_, _, _| {},
    )?;
    let len = dev.len()?;
    let src = len - consts::BACKUP_STANDARD_FROM_END;
    let mut region = vec![0u8; HEADER_REGION as usize];
    dev.read_at(src, &mut region)?;
    dev.write_at(0, &region)?;
    dev.flush()?;
    Ok(found)
}
