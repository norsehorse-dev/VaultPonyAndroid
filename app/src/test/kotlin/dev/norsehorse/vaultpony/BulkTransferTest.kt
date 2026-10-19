package dev.norsehorse.vaultpony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkTransferTest {

    // -- Names -------------------------------------------------------------

    @Test
    fun sanitizeReplacesFatIllegalCharacters() {
        assertEquals("a_b_c_d_e_f_g_h_i_j", BulkPlanner.sanitizeName("a\\b/c:d*e?f\"g<h>i|j"))
        assertEquals("tab_new_line_", BulkPlanner.sanitizeName("tab\tnew\nline\u0001"))
        assertEquals("plain name.txt", BulkPlanner.sanitizeName("plain name.txt"))
    }

    @Test
    fun sanitizeStripsTrailingDotsAndSpaces() {
        assertEquals("notes", BulkPlanner.sanitizeName("notes. . "))
        assertEquals(" lead", BulkPlanner.sanitizeName(" lead"))
    }

    @Test
    fun sanitizeNeverLeavesAnEmptyOrParentName() {
        assertEquals("_", BulkPlanner.sanitizeName(""))
        assertEquals("_", BulkPlanner.sanitizeName("."))
        assertEquals("_", BulkPlanner.sanitizeName(".."))
        assertEquals("_", BulkPlanner.sanitizeName("   "))
        assertEquals("_", BulkPlanner.sanitizeName("/"))
    }

    @Test
    fun sanitizeCapsLengthKeepingTheExtension() {
        val long = "x".repeat(300) + ".jpeg"
        val out = BulkPlanner.sanitizeName(long)
        assertEquals(BulkPlanner.MAX_NAME, out.length)
        assertTrue(out.endsWith(".jpeg"))
    }

    @Test
    fun sanitizeDoesNotSplitASurrogatePair() {
        // 250 x + emoji (2 units) + ".txt" = 256 units; the cut lands inside the pair.
        val name = "x".repeat(250) + "😀" + ".txt"
        val out = BulkPlanner.sanitizeName(name)
        assertTrue(out.length <= BulkPlanner.MAX_NAME)
        assertFalse(out.substringBefore(".txt").last().isHighSurrogate())
        assertTrue(out.endsWith(".txt"))
    }

    @Test
    fun keepBothNumbersBeforeTheExtension() {
        val taken = setOf("photo.jpg", "photo (1).jpg")
        assertEquals("photo (2).jpg", BulkPlanner.keepBothName("photo.jpg", false) { it in taken })
    }

    @Test
    fun keepBothOnDotfilesAndExtensionlessNames() {
        assertEquals(".bashrc (1)", BulkPlanner.keepBothName(".bashrc", false) { false })
        assertEquals("README (1)", BulkPlanner.keepBothName("README", false) { false })
    }

    @Test
    fun keepBothOnFoldersIgnoresDots() {
        assertEquals("my.folder (1)", BulkPlanner.keepBothName("my.folder", true) { false })
    }

    @Test
    fun keepBothStaysWithinTheNameLimit() {
        val name = "y".repeat(251) + ".txt"
        val out = BulkPlanner.keepBothName(name, false) { false }
        assertEquals(BulkPlanner.MAX_NAME, out.length)
        assertTrue(out.endsWith(" (1).txt"))
    }

    @Test
    fun onDiskRoundsUpToClusters() {
        assertEquals(0L, BulkPlanner.onDisk(0, 4096))
        assertEquals(4096L, BulkPlanner.onDisk(1, 4096))
        assertEquals(4096L, BulkPlanner.onDisk(4096, 4096))
        assertEquals(8192L, BulkPlanner.onDisk(4097, 4096))
        assertEquals(10L, BulkPlanner.onDisk(10, 1))
    }

    @Test
    fun vaultPathJoins() {
        assertEquals("/a/b", BulkPlanner.vaultPath("/", listOf("a", "b")))
        assertEquals("/docs/a", BulkPlanner.vaultPath("/docs", listOf("a")))
        assertEquals("/docs", BulkPlanner.vaultPath("/docs", emptyList()))
        assertEquals("/", BulkPlanner.vaultPath("/", emptyList()))
    }

    // -- Planning ------------------------------------------------------------

    private fun file(vararg path: String, size: Long = 10) =
        SourceEntry(path.toList(), isDir = false, size = size, ref = path.joinToString("/"))

    private fun dir(vararg path: String) =
        SourceEntry(path.toList(), isDir = true, size = 0, ref = path.joinToString("/"))

    private fun lister(tree: Map<List<String>, List<ExistingEntry>>): (List<String>) -> List<ExistingEntry> =
        { rel -> tree[rel] ?: emptyList() }

    private fun dests(plan: TransferPlan) = plan.ops.map {
        when (it) {
            is TransferOp.MakeDir -> "mkdir " + it.dest.joinToString("/")
            is TransferOp.CopyFile -> (if (it.replace) "replace " else "copy ") + it.dest.joinToString("/")
        }
    }

    @Test
    fun noCollisionsInAnEmptyFolder() {
        val plan = BulkPlanner.plan(
            listOf(file("a.txt"), file("b.txt")),
            FsLimits(), CollisionPolicy.KEEP_BOTH, mergeDirs = true, lister(emptyMap()),
        )
        assertEquals(0, plan.collisions)
        assertEquals(2, plan.files)
        assertEquals(listOf("copy a.txt", "copy b.txt"), dests(plan))
    }

    @Test
    fun keepBothIsTheDefaultAndCountsCollisions() {
        val existing = mapOf(emptyList<String>() to listOf(ExistingEntry("A.TXT", false, 5)))
        val plan = BulkPlanner.plan(
            listOf(file("a.txt"), file("b.txt")),
            FsLimits(), CollisionPolicy.KEEP_BOTH, mergeDirs = true, lister(existing),
        )
        assertEquals(1, plan.collisions)
        assertEquals(1, plan.keptBoth)
        assertEquals(listOf("copy a (1).txt", "copy b.txt"), dests(plan))
    }

    @Test
    fun skipLeavesExistingNamesAlone() {
        val existing = mapOf(emptyList<String>() to listOf(ExistingEntry("a.txt", false, 5)))
        val plan = BulkPlanner.plan(
            listOf(file("a.txt"), file("b.txt")),
            FsLimits(), CollisionPolicy.SKIP, mergeDirs = true, lister(existing),
        )
        assertEquals(1, plan.skipped)
        assertEquals(1, plan.files)
        assertEquals(listOf("copy b.txt"), dests(plan))
    }

    @Test
    fun replaceOverwritesUnderTheExistingNameWithNoSpaceCredit() {
        // The new copy is written in full beside the original before the swap,
        // so both need room at once.
        val existing = mapOf(emptyList<String>() to listOf(ExistingEntry("A.txt", false, 5000)))
        val plan = BulkPlanner.plan(
            listOf(file("a.txt", size = 5000)),
            FsLimits(clusterBytes = 4096, freeBytes = 0),
            CollisionPolicy.REPLACE, mergeDirs = true, lister(existing),
        )
        assertEquals(listOf("replace A.txt"), dests(plan))
        assertEquals(8192L, plan.bytesNeeded)
        assertFalse(plan.fits(0))
        assertTrue(plan.fits(8192))
    }

    @Test
    fun tempNamesAreRecognisedWithoutCase() {
        assertTrue(BulkPlanner.isTempName(BulkPlanner.tempName("0123abcd")))
        assertTrue(BulkPlanner.isTempName(".VaultPony-x.PART"))
        assertFalse(BulkPlanner.isTempName(".vaultpony-.part"))
        assertFalse(BulkPlanner.isTempName("vaultpony-x.part"))
        assertFalse(BulkPlanner.isTempName(".vaultpony-x.partial"))
    }

    @Test
    fun leftoverTempFilesNeverCollide() {
        val leftover = BulkPlanner.tempName("deadbeef")
        val existing = mapOf(emptyList<String>() to listOf(ExistingEntry(leftover, false, 99)))
        val plan = BulkPlanner.plan(
            listOf(file("a.txt")),
            FsLimits(), CollisionPolicy.KEEP_BOTH, mergeDirs = true, lister(existing),
        )
        assertEquals(0, plan.collisions)
        assertEquals(listOf("copy a.txt"), dests(plan))
    }

    @Test
    fun aSourceNamedLikeATempFileIsRenamed() {
        val plan = BulkPlanner.plan(
            listOf(file(".vaultpony-x.part")),
            FsLimits(), CollisionPolicy.KEEP_BOTH, mergeDirs = true, lister(emptyMap()),
        )
        assertEquals(listOf("copy _.vaultpony-x.part"), dests(plan))
        assertEquals(1, plan.renamed)
    }

    @Test
    fun replaceNeverSwapsAFileForAFolder() {
        val existing = mapOf(emptyList<String>() to listOf(ExistingEntry("pics", true, 0)))
        val plan = BulkPlanner.plan(
            listOf(file("pics")),
            FsLimits(), CollisionPolicy.REPLACE, mergeDirs = true, lister(existing),
        )
        assertEquals(listOf("copy pics (1)"), dests(plan))
        assertEquals(1, plan.keptBoth)
    }

    @Test
    fun existingFoldersAreMergedAndTheirContentsCollide() {
        val existing = mapOf(
            emptyList<String>() to listOf(ExistingEntry("Trips", true, 0)),
            listOf("Trips") to listOf(ExistingEntry("day1.jpg", false, 1)),
        )
        val plan = BulkPlanner.plan(
            listOf(dir("trips"), file("trips", "day1.jpg"), file("trips", "day2.jpg")),
            FsLimits(), CollisionPolicy.KEEP_BOTH, mergeDirs = true, lister(existing),
        )
        // The folder merges under its existing spelling; no mkdir for it.
        assertEquals(listOf("copy Trips/day1 (1).jpg", "copy Trips/day2.jpg"), dests(plan))
        assertEquals(1, plan.collisions)
    }

    @Test
    fun exportKeepsBothForAnExistingFolder() {
        val existing = mapOf(emptyList<String>() to listOf(ExistingEntry("Trips", true, 0)))
        val plan = BulkPlanner.plan(
            listOf(dir("Trips"), file("Trips", "a.jpg")),
            FsLimits(), CollisionPolicy.KEEP_BOTH, mergeDirs = false, lister(existing),
        )
        assertEquals(listOf("mkdir Trips (1)", "copy Trips (1)/a.jpg"), dests(plan))
    }

    @Test
    fun skippingAFolderSkipsEverythingInIt() {
        val existing = mapOf(emptyList<String>() to listOf(ExistingEntry("Trips", false, 3)))
        val plan = BulkPlanner.plan(
            listOf(dir("Trips"), dir("Trips", "sub"), file("Trips", "sub", "a"), file("Trips", "b")),
            FsLimits(), CollisionPolicy.SKIP, mergeDirs = true, lister(existing),
        )
        assertTrue(plan.ops.isEmpty())
        assertEquals(2, plan.skipped)
    }

    @Test
    fun namesThatClashWithinTheBatchKeepBoth() {
        val plan = BulkPlanner.plan(
            listOf(file("Note.txt"), file("note.txt"), file("a:b"), file("a_b")),
            FsLimits(), CollisionPolicy.SKIP, mergeDirs = true, lister(emptyMap()),
        )
        // Not a destination collision, so Skip doesn't apply; nothing is lost.
        assertEquals(0, plan.collisions)
        assertEquals(listOf("copy Note.txt", "copy note (1).txt", "copy a_b", "copy a_b (1)"), dests(plan))
        assertEquals(2, plan.keptBoth)
        assertEquals(1, plan.renamed)
    }

    @Test
    fun oversizeFilesAreLeftOutOnFat() {
        val fatCap = 4294967295L
        val plan = BulkPlanner.plan(
            listOf(file("big.mkv", size = fatCap + 1), file("ok.mkv", size = fatCap)),
            FsLimits(maxFileBytes = fatCap), CollisionPolicy.KEEP_BOTH, mergeDirs = true, lister(emptyMap()),
        )
        assertEquals(1, plan.oversize)
        assertEquals(listOf("copy ok.mkv"), dests(plan))
    }

    @Test
    fun unknownSizesAreNotTreatedAsOversize() {
        val plan = BulkPlanner.plan(
            listOf(file("stream", size = -1)),
            FsLimits(clusterBytes = 4096, maxFileBytes = 100), CollisionPolicy.KEEP_BOTH,
            mergeDirs = true, lister(emptyMap()),
        )
        assertEquals(0, plan.oversize)
        assertEquals(0L, plan.bytesNeeded)
    }

    @Test
    fun spaceCountsClustersAndNewFolders() {
        val plan = BulkPlanner.plan(
            listOf(dir("d"), file("d", "a", size = 1), file("d", "b", size = 4097)),
            FsLimits(clusterBytes = 4096, freeBytes = 16384), CollisionPolicy.KEEP_BOTH,
            mergeDirs = true, lister(emptyMap()),
        )
        // 1 cluster folder + 1 + 2 clusters of data.
        assertEquals(4L * 4096, plan.bytesNeeded)
        assertEquals(4098L, plan.bytes)
        assertTrue(plan.fits(16384))
        assertFalse(plan.fits(16383))
        assertTrue(plan.fits(null))
    }

    @Test
    fun illegalNamesAreRenamedNotSkipped() {
        val plan = BulkPlanner.plan(
            listOf(dir("a?"), file("a?", "b*.txt")),
            FsLimits(), CollisionPolicy.KEEP_BOTH, mergeDirs = true, lister(emptyMap()),
        )
        assertEquals(listOf("mkdir a_", "copy a_/b_.txt"), dests(plan))
        assertEquals(2, plan.renamed)
    }
}
