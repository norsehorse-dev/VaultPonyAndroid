package dev.norsehorse.vaultpony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.vault_ffi.VaultException

class CoreErrorsTest {

    private fun fs(detail: String) = CoreErrors.classify(CoreVariant.FILESYSTEM, detail)
    private fun io(detail: String) = CoreErrors.classify(CoreVariant.IO, detail)

    @Test
    fun variantsWithTheirOwnMeaning() {
        assertEquals(VaultErrorKind.HIDDEN_PROTECTED, CoreErrors.classify(CoreVariant.HIDDEN_VOLUME_PROTECTED, null))
        assertEquals(VaultErrorKind.WRONG_PASSWORD, CoreErrors.classify(CoreVariant.NOT_FOUND_OR_WRONG_PASSWORD, null))
        assertEquals(VaultErrorKind.UNSUPPORTED_FS, CoreErrors.classify(CoreVariant.UNSUPPORTED_FILESYSTEM, "Ext4"))
        assertEquals(VaultErrorKind.EMPTY_KEYFILE, CoreErrors.classify(CoreVariant.EMPTY_KEYFILE, null))
    }

    @Test
    fun trueCryptAndExt4HaveTheirOwnText() {
        assertEquals(VaultErrorKind.TRUECRYPT_READ_ONLY, CoreErrors.classify(CoreVariant.TRUECRYPT_READ_ONLY, null))
        assertEquals(
            VaultErrorKind.TRUECRYPT_TOO_OLD,
            CoreErrors.classify(CoreVariant.TRUECRYPT_VERSION_UNSUPPORTED, null),
        )
        assertEquals(VaultErrorKind.EXT4_UNSUPPORTED, CoreErrors.classify(CoreVariant.EXT4_UNSUPPORTED, "inline data"))
    }

    @Test
    fun generatedExceptionsClassifyWithoutTheirMessage() {
        // UniFFI's variants without fields have an empty message, so the
        // text must come from the table, never from e.message (2.0.0 rc5).
        assertEquals("", VaultException.NotFoundOrWrongPassword().message)
        assertEquals(VaultErrorKind.WRONG_PASSWORD, CoreErrorMap.kindOf(VaultException.NotFoundOrWrongPassword()))
        assertEquals(VaultErrorKind.TRUECRYPT_READ_ONLY, CoreErrorMap.kindOf(VaultException.TrueCryptReadOnly()))
        assertEquals(VaultErrorKind.READ_ONLY, CoreErrorMap.kindOf(VaultException.Filesystem(CoreErrors.EXT4_READ_ONLY)))
        assertEquals(VaultErrorKind.OTHER, CoreErrorMap.kindOf(VaultException.HeaderDamaged()))
        assertEquals("inline data", CoreErrorMap.messageDetail(VaultException.Ext4Unsupported("inline data")))
        assertNull(CoreErrorMap.messageDetail(VaultException.Filesystem("x")))
        assertNull(CoreErrorMap.kindOf(VaultException.Cancelled()))
        assertNull(CoreErrorMap.kindOf(IllegalStateException("not from the core")))
    }

    @Test
    fun cancelHasNoMessage() {
        assertNull(CoreErrors.classify(CoreVariant.CANCELLED, null))
    }

    @Test
    fun everythingElseIsTheGenericText() {
        for (v in listOf(
            CoreVariant.INTERNAL, CoreVariant.VERSION_TOO_NEW, CoreVariant.SYSTEM_VOLUME,
            CoreVariant.HEADER_DAMAGED, CoreVariant.KDF_OUT_OF_MEMORY, CoreVariant.PASSWORD_TOO_SHORT_FOR_PIM,
        )) {
            assertEquals(v.name, VaultErrorKind.OTHER, CoreErrors.classify(v, null))
        }
    }

    @Test
    fun hiddenProtectionWrappedByTheFilesystemAdapters() {
        // FAT: the gate's text bare. exFAT: ExfatError::Io displays as "I/O: ...".
        assertEquals(VaultErrorKind.HIDDEN_PROTECTED, fs(CoreErrors.HIDDEN_GATE))
        assertEquals(VaultErrorKind.HIDDEN_PROTECTED, fs("I/O: ${CoreErrors.HIDDEN_GATE}"))
        // Only the whole gate message counts.
        assertEquals(VaultErrorKind.FILESYSTEM, fs("protect the hidden volume"))
    }

    @Test
    fun outOfSpaceMatchesOnlyTheAdaptersMessages() {
        assertEquals(VaultErrorKind.FULL, fs("invalid exFAT volume: no free clusters"))
        assertEquals(VaultErrorKind.FULL, fs("No space left on device"))
        // A host-side ENOSPC on the container file is not a full vault.
        assertEquals(VaultErrorKind.FILESYSTEM, fs("No space left on device (os error 28)"))
        assertEquals(VaultErrorKind.IO, io("No space left on device (os error 28)"))
    }

    @Test
    fun readOnlyIsOnlyNtfsAndExt4() {
        assertEquals(VaultErrorKind.READ_ONLY, fs("NTFS is read-only"))
        assertEquals(VaultErrorKind.READ_ONLY, fs(CoreErrors.EXT4_READ_ONLY))
        assertEquals(VaultErrorKind.FILESYSTEM, fs("Read-only file system (os error 30)"))
        assertEquals(VaultErrorKind.FILESYSTEM, fs("Bad file descriptor (os error 9)"))
    }

    @Test
    fun notFound() {
        assertEquals(VaultErrorKind.NOT_FOUND, fs("not found"))
        assertEquals(VaultErrorKind.NOT_FOUND, fs("not found: photo.jpg"))
        assertEquals(VaultErrorKind.NOT_FOUND, fs("No such file or directory"))
    }

    @Test
    fun unrecognizedTextFallsBackByVariant() {
        assertEquals(VaultErrorKind.FILESYSTEM, fs("Directory not empty"))
        assertEquals(VaultErrorKind.FILESYSTEM, fs("invalid exFAT volume: destination exists"))
        assertEquals(VaultErrorKind.FILESYSTEM, fs(""))
        assertEquals(VaultErrorKind.FILESYSTEM, fs("Directory not found"))
        assertEquals(VaultErrorKind.IO, io("unexpected end of file"))
        assertEquals(VaultErrorKind.IO, CoreErrors.classify(CoreVariant.IO, null))
        assertEquals(VaultErrorKind.IO, io("entity not found"))
    }
}
