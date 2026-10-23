package dev.norsehorse.vaultpony

/*
 * Core error classification for user-facing text (2.0.0 rc2, R4). Plain
 * Kotlin with no Android or FFI types so it runs under JVM unit tests;
 * CoreErrorMap turns a generated VaultException into a [CoreVariant] and the
 * UI turns a [VaultErrorKind] into a localized string. The core's own text
 * never reaches the screen, only the log.
 */

/** Mirror of the FFI's VaultError variants (VaultPonyCore vault-ffi lib.rs). */
enum class CoreVariant {
    NOT_FOUND_OR_WRONG_PASSWORD,
    VERSION_TOO_NEW,
    SYSTEM_VOLUME,
    HIDDEN_VOLUME_PROTECTED,
    HEADER_DAMAGED,
    UNSUPPORTED_FILESYSTEM,
    FILESYSTEM,
    IO,
    INTERNAL,
    KDF_OUT_OF_MEMORY,
    CANCELLED,
    PASSWORD_TOO_SHORT_FOR_PIM,
    /** A keyfile with no bytes; VeraCrypt refuses those (2.0.0 rc4). */
    EMPTY_KEYFILE,
    /** A write to a TrueCrypt session (2.0.0 rc5, plan 6.2). */
    TRUECRYPT_READ_ONLY,
    /** A TrueCrypt header from before 6.0 (2.0.0 rc5, plan 6.2). */
    TRUECRYPT_VERSION_UNSUPPORTED,
    /** An ext4 feature the reader doesn't support (2.0.0 rc5, plan 6.3). */
    EXT4_UNSUPPORTED,
}

/** What the user is told. Each maps to one string resource. */
enum class VaultErrorKind {
    HIDDEN_PROTECTED,
    NOT_FOUND,
    FILESYSTEM,
    IO,
    WRONG_PASSWORD,
    UNSUPPORTED_FS,
    READ_ONLY,
    FULL,
    EMPTY_KEYFILE,
    TRUECRYPT_READ_ONLY,
    TRUECRYPT_TOO_OLD,
    /** Its text carries the core's name of the feature (BatchError.detail). */
    EXT4_UNSUPPORTED,
    OTHER,
}

object CoreErrors {

    /**
     * Classify a core error. [detail] is the variant's detail text
     * (Filesystem and Io carry one). Returns null for a cancel, which is the
     * user's own action and needs no message.
     *
     * The filesystem adapters wrap lower-level errors into Filesystem text,
     * so some meanings only show up there, and only the exact messages the
     * core produces count (same rules as the desktop app's errors.rs):
     * - the hidden-volume gate sits under the filesystem, so its refusal
     *   arrives as Filesystem carrying the gate's own message, bare on FAT
     *   and after "I/O: " on exFAT;
     * - a full volume is exFAT's "invalid exFAT volume: no free clusters" or
     *   fatfs's own "No space left on device", matched whole so a host-side
     *   ENOSPC ("... (os error 28)") on the container file is not read as a
     *   full vault;
     * - "not found", "not found: <name>", fatfs "No such file or directory";
     * - NTFS writes say "NTFS is read-only", ext4 writes "ext4 is read-only
     *   in this version" (2.0.0 rc5).
     * Anything else is the generic filesystem or I/O text.
     */
    fun classify(variant: CoreVariant, detail: String?): VaultErrorKind? = when (variant) {
        CoreVariant.CANCELLED -> null
        CoreVariant.HIDDEN_VOLUME_PROTECTED -> VaultErrorKind.HIDDEN_PROTECTED
        CoreVariant.NOT_FOUND_OR_WRONG_PASSWORD -> VaultErrorKind.WRONG_PASSWORD
        CoreVariant.UNSUPPORTED_FILESYSTEM -> VaultErrorKind.UNSUPPORTED_FS
        CoreVariant.FILESYSTEM -> ofFilesystem(detail.orEmpty())
        CoreVariant.IO -> VaultErrorKind.IO
        CoreVariant.EMPTY_KEYFILE -> VaultErrorKind.EMPTY_KEYFILE
        CoreVariant.TRUECRYPT_READ_ONLY -> VaultErrorKind.TRUECRYPT_READ_ONLY
        CoreVariant.TRUECRYPT_VERSION_UNSUPPORTED -> VaultErrorKind.TRUECRYPT_TOO_OLD
        CoreVariant.EXT4_UNSUPPORTED -> VaultErrorKind.EXT4_UNSUPPORTED
        else -> VaultErrorKind.OTHER
    }

    private fun ofFilesystem(msg: String): VaultErrorKind = when {
        HIDDEN_GATE in msg -> VaultErrorKind.HIDDEN_PROTECTED
        "no free clusters" in msg || msg == "No space left on device" -> VaultErrorKind.FULL
        msg == "not found" || msg.startsWith("not found: ") || msg == "No such file or directory" ->
            VaultErrorKind.NOT_FOUND
        msg == "NTFS is read-only" || msg == EXT4_READ_ONLY -> VaultErrorKind.READ_ONLY
        else -> VaultErrorKind.FILESYSTEM
    }

    /** vc-fs ext4.rs READ_ONLY, verbatim. */
    const val EXT4_READ_ONLY = "ext4 is read-only in this version"

    /** vc-types VcError::HiddenVolumeProtected's message, verbatim. */
    const val HIDDEN_GATE = "write blocked to protect the hidden volume; the volume is now read-only"
}
