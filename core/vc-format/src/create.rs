//! Container creation (planning doc §2 P10, §6). Builds a standard
//! (non-hidden) VeraCrypt volume from scratch: random salt and master keys,
//! a header with both CRCs, encrypted with the header key derived from the
//! password (+ keyfiles), written as primary + embedded backup. The header
//! regions and hidden slots are filled with random so the result is
//! indistinguishable from a VeraCrypt-created container.
//!
//! Verified by the P10 gate: desktop VeraCrypt opens what we create across
//! the cipher/PRF matrix, and keyfile containers interop both directions.

use vc_types::header::{offsets, MASTER_KEY_AREA_LEN};
use vc_types::{consts, EncryptionScheme, Prf, VcError, VcResult};

/// Parameters for a new container.
pub struct CreateParams<'a> {
    pub scheme: &'static EncryptionScheme,
    pub prf: &'static Prf,
    pub pim: u32,
    pub passphrase: &'a [u8],
    pub keyfiles: &'a [Vec<u8>],
    /// Total container size in bytes. Must exceed 2 * 128 KiB by enough to
    /// hold a usable data area (we require ≥ 256 KiB + one cluster).
    pub size: u64,
    pub sector_size: u32,
}

fn fill_random(buf: &mut [u8]) -> VcResult<()> {
    getrandom::getrandom(buf).map_err(|e| VcError::Internal(format!("rng: {e}")))
}

fn be16(v: u16) -> [u8; 2] {
    v.to_be_bytes()
}
fn be32(v: u32) -> [u8; 4] {
    v.to_be_bytes()
}
fn be64(v: u64) -> [u8; 8] {
    v.to_be_bytes()
}

/// Build the 448-byte plaintext header for a normal volume, with both CRCs
/// filled. `master_key_area` is the full 256-byte area.
fn build_header(
    data_area_start: u64,
    data_area_size: u64,
    sector_size: u32,
    master_key_area: &[u8; MASTER_KEY_AREA_LEN],
) -> [u8; consts::HEADER_ENC_LEN] {
    let mut h = [0u8; consts::HEADER_ENC_LEN];
    h[offsets::MAGIC..offsets::MAGIC + 4].copy_from_slice(&consts::HEADER_MAGIC);
    h[offsets::HEADER_VERSION..offsets::HEADER_VERSION + 2].copy_from_slice(&be16(5));
    // Min program version 1.11 — the value current VeraCrypt accepts and our
    // parser supports; keeps the volume openable by any modern VeraCrypt.
    h[offsets::MIN_PROGRAM_VERSION..offsets::MIN_PROGRAM_VERSION + 2]
        .copy_from_slice(&be16(0x010b));
    h[offsets::HIDDEN_VOLUME_SIZE..offsets::HIDDEN_VOLUME_SIZE + 8].copy_from_slice(&be64(0));
    h[offsets::VOLUME_SIZE..offsets::VOLUME_SIZE + 8].copy_from_slice(&be64(data_area_size));
    h[offsets::ENCRYPTED_AREA_START..offsets::ENCRYPTED_AREA_START + 8]
        .copy_from_slice(&be64(data_area_start));
    h[offsets::ENCRYPTED_AREA_SIZE..offsets::ENCRYPTED_AREA_SIZE + 8]
        .copy_from_slice(&be64(data_area_size));
    h[offsets::FLAGS..offsets::FLAGS + 4].copy_from_slice(&be32(0));
    h[offsets::SECTOR_SIZE..offsets::SECTOR_SIZE + 4].copy_from_slice(&be32(sector_size));
    h[offsets::MASTER_KEYS..offsets::MASTER_KEYS + MASTER_KEY_AREA_LEN]
        .copy_from_slice(master_key_area);

    // Master-key CRC-32 over the 256-byte key area.
    let mk_crc =
        crc32fast::hash(&h[offsets::MASTER_KEYS..offsets::MASTER_KEYS + MASTER_KEY_AREA_LEN]);
    h[offsets::MASTER_KEY_CRC32..offsets::MASTER_KEY_CRC32 + 4].copy_from_slice(&be32(mk_crc));
    // Header-fields CRC-32 over bytes 0..188.
    let fields_crc = crc32fast::hash(&h[..vc_types::header::HEADER_FIELDS_CRC_LEN]);
    h[offsets::HEADER_CRC32..offsets::HEADER_CRC32 + 4].copy_from_slice(&be32(fields_crc));
    h
}

/// Encrypt one header copy under a fresh salt and write `salt ‖ ciphertext`
/// at `offset`. Returns nothing; the plaintext (and its keys) are the
/// caller's to zeroize.
fn write_header_copy(
    dev: &mut dyn vc_io::BlockDevice,
    offset: u64,
    plaintext: &[u8; consts::HEADER_ENC_LEN],
    params: &CreateParams<'_>,
) -> VcResult<()> {
    let mut salt = [0u8; consts::SALT_LEN];
    fill_random(&mut salt)?;

    let secret = vc_crypto::apply_keyfiles(params.passphrase, params.keyfiles);
    let iterations = vc_types::registry::iterations_for_pim(params.prf, params.pim);
    let key = vc_crypto::derive_header_key(
        params.prf.name,
        &secret,
        &salt,
        iterations,
        params.scheme.key_bytes(),
    )?;
    let xts = vc_crypto::SchemeXts::new(params.scheme, key.as_bytes())?;

    let mut enc = *plaintext;
    xts.encrypt_header(&mut enc);

    let mut region = [0u8; consts::HEADER_REGION_LEN];
    region[..consts::SALT_LEN].copy_from_slice(&salt);
    region[consts::SALT_LEN..].copy_from_slice(&enc);
    dev.write_at(offset, &region)?;
    Ok(())
}

/// Create a standard volume in `dev` (which must already be `size` bytes).
/// Returns the data-area start/size so the caller can lay a filesystem
/// inside via the normal `Session` write path.
pub fn create_volume(
    dev: &mut dyn vc_io::BlockDevice,
    params: &CreateParams<'_>,
) -> VcResult<(u64, u64)> {
    if !consts::SECTOR_SIZES.contains(&params.sector_size) {
        return Err(VcError::Internal("unsupported sector size".into()));
    }
    let front_back = 2 * consts::BACKUP_STANDARD_FROM_END; // 256 KiB reserved
    if params.size < front_back + 512 {
        return Err(VcError::Internal("container too small".into()));
    }
    let data_area_start = consts::BACKUP_STANDARD_FROM_END; // 131072
    let data_area_size = params.size - front_back;

    // Random master-key area; the first key_bytes are the XTS keys, laid out
    // exactly as our reader (and VeraCrypt) interpret them.
    let mut master_key_area = [0u8; MASTER_KEY_AREA_LEN];
    fill_random(&mut master_key_area)?;

    let header = build_header(
        data_area_start,
        data_area_size,
        params.sector_size,
        &master_key_area,
    );

    // Random-fill the four header regions so nothing distinguishes a fresh
    // container from one that has hidden data (doc §11). Data area stays
    // zero — a filesystem is laid over it next.
    let mut rnd = [0u8; consts::HEADER_REGION_LEN];
    for off in [
        consts::PRIMARY_HEADER_OFFSET,
        consts::HIDDEN_HEADER_OFFSET,
        params.size - consts::BACKUP_STANDARD_FROM_END,
        params.size - consts::BACKUP_HIDDEN_FROM_END,
    ] {
        fill_random(&mut rnd)?;
        dev.write_at(off, &rnd)?;
    }

    // Overwrite the primary and backup header regions with real headers.
    write_header_copy(dev, consts::PRIMARY_HEADER_OFFSET, &header, params)?;
    write_header_copy(
        dev,
        params.size - consts::BACKUP_STANDARD_FROM_END,
        &header,
        params,
    )?;
    dev.flush()?;

    // Wipe the plaintext key area copy we still hold.
    use zeroize::Zeroize;
    master_key_area.zeroize();

    Ok((data_area_start, data_area_size))
}
