package dev.norsehorse.vaultpony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLogicTest {

    // -- Open modes ----------------------------------------------------------

    @Test
    fun openModes() {
        assertEquals(OpenMode(read = true, write = false, truncate = false, append = false), OpenMode.parse("r"))
        assertEquals(OpenMode(read = false, write = true, truncate = true, append = false), OpenMode.parse("w"))
        assertEquals(OpenMode(read = false, write = true, truncate = true, append = false), OpenMode.parse("wt"))
        assertEquals(OpenMode(read = false, write = true, truncate = false, append = true), OpenMode.parse("wa"))
        assertEquals(OpenMode(read = true, write = true, truncate = false, append = false), OpenMode.parse("rw"))
        assertEquals(OpenMode(read = true, write = true, truncate = true, append = false), OpenMode.parse("rwt"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownModeIsRefused() {
        OpenMode.parse("x")
    }

    // -- Document ids and paths ---------------------------------------------

    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun docIdRoundTrip() {
        val id = ProviderPaths.docId(token, "/Photos/a:b.jpg")
        assertEquals(token, ProviderPaths.tokenOf(id))
        // Only the first colon separates; the path may contain more.
        assertEquals("/Photos/a:b.jpg", ProviderPaths.pathOf(id))
        assertEquals("/", ProviderPaths.pathOf(ProviderPaths.docId(token, "/")))
        assertNull(ProviderPaths.tokenOf("no-colon"))
        assertNull(ProviderPaths.tokenOf(":/x"))
    }

    @Test
    fun onlyTokensAreAcceptedAsIdPrefixes() {
        // A stable volume id (what rc1 and earlier used) never resolves.
        assertNull(ProviderPaths.tokenOf("1a2b3c4d:/"))
        // Wrong length, upper case, or not hex.
        assertNull(ProviderPaths.tokenOf("0123456789abcdef0123456789abcde:/"))
        assertNull(ProviderPaths.tokenOf("0123456789abcdef0123456789abcdef0:/"))
        assertNull(ProviderPaths.tokenOf("0123456789ABCDEF0123456789ABCDEF:/"))
        assertNull(ProviderPaths.tokenOf("0123456789abcdef0123456789abcdeg:/"))
    }

    @Test
    fun mintedTokensAreFreshAndWellFormed() {
        val seen = HashSet<String>()
        repeat(200) {
            val t = ProviderTokens.mint()
            assertTrue(t, ProviderTokens.isToken(t))
            assertEquals(t, ProviderPaths.tokenOf(ProviderPaths.docId(t, "/a")))
            assertTrue("token repeated", seen.add(t))
        }
    }

    @Test
    fun cleanPaths() {
        assertTrue(ProviderPaths.isCleanPath("/"))
        assertTrue(ProviderPaths.isCleanPath("/a"))
        assertTrue(ProviderPaths.isCleanPath("/a/b c/d.txt"))
        assertTrue(ProviderPaths.isCleanPath("/..hidden"))
        assertFalse(ProviderPaths.isCleanPath(""))
        assertFalse(ProviderPaths.isCleanPath("a/b"))
        assertFalse(ProviderPaths.isCleanPath("/a/"))
        assertFalse(ProviderPaths.isCleanPath("/a//b"))
        assertFalse(ProviderPaths.isCleanPath("/a/../b"))
        assertFalse(ProviderPaths.isCleanPath("/a/./b"))
        assertFalse(ProviderPaths.isCleanPath("/.."))
        assertFalse(ProviderPaths.isCleanPath("/a\\b"))
        assertFalse(ProviderPaths.isCleanPath("/a\u0000b"))
    }

    @Test
    fun withinIsExactAndRespectsSegments() {
        assertTrue(ProviderPaths.isWithin("/", "/"))
        assertTrue(ProviderPaths.isWithin("/", "/a/b"))
        assertTrue(ProviderPaths.isWithin("/Photos", "/Photos"))
        assertTrue(ProviderPaths.isWithin("/Photos", "/Photos/2024/x.jpg"))
        // No case folding of any kind (ids keep the names as listed).
        assertFalse(ProviderPaths.isWithin("/Photos", "/photos/2024/x.jpg"))
        assertFalse(ProviderPaths.isWithin("/\u00C9t\u00E9", "/\u00E9t\u00E9/a"))
        assertFalse(ProviderPaths.isWithin("/Photos", "/Photos2/x.jpg"))
        assertFalse(ProviderPaths.isWithin("/a/b", "/a"))
    }

    @Test
    fun parentLeafChild() {
        assertEquals("/", ProviderPaths.parent("/a"))
        assertEquals("/a", ProviderPaths.parent("/a/b"))
        assertEquals("b", ProviderPaths.leaf("/a/b"))
        assertEquals("/x", ProviderPaths.child("/", "x"))
        assertEquals("/a/x", ProviderPaths.child("/a", "x"))
    }

    @Test
    fun tempFilesAreHidden() {
        assertTrue(ProviderPaths.isHidden("/a/.vaultpony-0123456789ab.part"))
        assertTrue(ProviderPaths.isHidden("/.VAULTPONY-x.PART"))
        assertFalse(ProviderPaths.isHidden("/a/notes.part"))
    }

    // -- Names ---------------------------------------------------------------

    @Test
    fun cleanUsesTheImportSanitizer() {
        assertEquals("a_b", ProviderNames.clean("a/b"))
        assertEquals("_", ProviderNames.clean(".."))
        assertEquals("notes", ProviderNames.clean("notes. "))
    }

    @Test
    fun cleanNeverMakesATempName() {
        val n = ProviderNames.clean(".vaultpony-abc.part")
        assertFalse(BulkPlanner.isTempName(n))
        assertEquals("_.vaultpony-abc.part", n)
        // Still not a temp name when it has to be shortened.
        val long = ProviderNames.clean(".vaultpony-" + "x".repeat(300) + ".part")
        assertFalse(BulkPlanner.isTempName(long))
        assertTrue(long.length <= BulkPlanner.MAX_NAME)
    }

    @Test
    fun mimeExtension() {
        val txt: (String) -> Boolean = { it.equals("txt", ignoreCase = true) }
        assertEquals("notes.txt", ProviderNames.withMimeExtension("notes", "txt", txt))
        assertEquals("notes.TXT", ProviderNames.withMimeExtension("notes.TXT", "txt", txt))
        assertEquals("data.bin.txt", ProviderNames.withMimeExtension("data.bin", "txt", txt))
        assertEquals("anything", ProviderNames.withMimeExtension("anything", null) { false })
        // A dotfile has no extension.
        assertEquals(".env.txt", ProviderNames.withMimeExtension(".env", "txt", txt))
    }

    @Test
    fun uniqueKeepsBoth() {
        assertEquals("a.txt", ProviderNames.unique("a.txt", false, listOf("b.txt")))
        assertEquals("a (1).txt", ProviderNames.unique("a.txt", false, listOf("A.TXT")))
        assertEquals("a (2).txt", ProviderNames.unique("a.txt", false, listOf("a.txt", "a (1).txt")))
        assertEquals("Folder (1)", ProviderNames.unique("Folder", true, listOf("folder")))
    }

    // -- Thumbnails ------------------------------------------------------------

    @Test
    fun sampleSizeStaysAtOrAboveTheHint() {
        assertEquals(1, Thumbnails.sampleSize(400, 300, 512, 512))
        assertEquals(4, Thumbnails.sampleSize(4000, 3000, 512, 512))
        assertEquals(8, Thumbnails.sampleSize(4096, 4096, 512, 512))
        // A zero hint counts as 1 px, so a tiny image still decodes.
        assertEquals(8, Thumbnails.sampleSize(10, 10, 0, 0))
    }

    @Test
    fun thumbnailTypes() {
        assertTrue(Thumbnails.supported("image/jpeg", 26))
        assertTrue(Thumbnails.supported("IMAGE/PNG", 26))
        assertFalse(Thumbnails.supported("image/heic", 27))
        assertTrue(Thumbnails.supported("image/heic", 28))
        assertFalse(Thumbnails.supported("video/mp4", 35))
        assertFalse(Thumbnails.supported("application/octet-stream", 35))
    }

    // -- Change signal ----------------------------------------------------------

    @Test
    fun changeAffectsItsFolderAndAnythingUnderAGonePath() {
        val c = VaultChange("v", dirs = setOf("/a"), gone = setOf("/a/old"))
        assertTrue(c.affects("/a"))
        assertFalse(c.affects("/A"))
        assertTrue(c.affects("/a/old"))
        assertTrue(c.affects("/a/old/deeper"))
        assertFalse(c.affects("/"))
        assertFalse(c.affects("/a/other"))
        assertTrue(VaultChange("v", everything = true).affects("/anything"))
    }
}
