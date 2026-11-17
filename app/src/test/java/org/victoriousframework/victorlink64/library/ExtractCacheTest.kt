package org.victoriousframework.victorlink64.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtractCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun put(c: ExtractCache, id: String, bytes: Int, age: Long): File {
        val slot = c.slotFor(id, 100, 1, "Game $id.iso")
        slot.parentFile!!.mkdirs(); slot.writeBytes(ByteArray(bytes))
        c.writeInfo(slot, "Game $id", "$id.zip")
        slot.parentFile!!.setLastModified(age)
        return slot
    }

    @Test fun slotsAreStableAndKeepExtension() {
        val c = ExtractCache(tmp.root)
        val a = c.slotFor("content://x/1", 10, 5, "dir/Game (Disc 1).iso")
        assertEquals(a, c.slotFor("content://x/1", 10, 5, "dir/Game (Disc 1).iso"))
        assertNotEquals(a, c.slotFor("content://x/1", 11, 5, "dir/Game (Disc 1).iso"))
        assertNotEquals(a, c.slotFor("content://x/1", 10, 5, "dir/Game (Disc 2).iso"))
        assertEquals("iso", a.extension)
        assertNull(c.reuse(a))
    }

    @Test fun reuseListAndCleanup() {
        val c = ExtractCache(tmp.root)
        val s = put(c, "a", 1000, 1_000_000L)
        put(c, "b", 500, 2_000_000L)
        assertEquals(s, c.reuse(s))
        assertEquals(2, c.entries().size)
        assertEquals("Game a", c.entries().first().title) // just reused → most recent
        assertTrue(c.totalBytes() >= 1500)
        File(s.parentFile, "x.part").writeText("partial")
        c.cleanPartials()
        assertTrue(c.entries().none { it.file.name.endsWith(".part") })
        c.delete(c.entries().first { it.title == "Game b" })
        assertEquals(1, c.entries().size)
        c.clear()
        assertEquals(0, c.entries().size)
    }

    @Test fun ensureSpaceEvictsOldestThenGivesUp() {
        val c = ExtractCache(tmp.root)
        val old = put(c, "old", 10, 1_000L)
        val mid = put(c, "mid", 10, 2_000L)
        var free = (64L shl 20) + 5
        val fake = { free }
        // Deleting "old" (the oldest) frees enough.
        c.ensureSpace(10, freeBytes = { if (!old.exists()) (64L shl 20) + 100 else fake() })
        assertTrue(!old.exists() && mid.exists())
        try {
            c.ensureSpace(1L shl 40, keep = mid, freeBytes = fake)
            fail("expected not-enough-space")
        } catch (e: FriendlyException) {
            assertTrue("Not enough free space" in e.message!!)
        }
        assertTrue(mid.exists()) // the game being launched is never evicted
    }

    @Test fun sizeCapEvictsLeastRecentlyUsedAndSparesTheGameBeingPlayed() {
        val c = ExtractCache(tmp.root)
        val a = put(c, "a", 1000, 1_000L)
        val b = put(c, "b", 1000, 2_000L)
        val d = put(c, "d", 1000, 3_000L)
        val plenty = { 1L shl 40 }
        // Room for one more 1000-byte game under a 2500-byte cap: the two oldest go.
        c.ensureSpace(1000, keep = null, capBytes = 2500, freeBytes = plenty)
        assertTrue(!a.exists() && !b.exists() && d.exists())
        // A game bigger than the cap is still allowed (soft cap); the one being played is never removed.
        val e = put(c, "e", 1000, 4_000L)
        c.ensureSpace(5000, keep = e, capBytes = 2500, freeBytes = plenty)
        assertTrue(!d.exists() && e.exists())
        // No cap (0) removes nothing.
        put(c, "f", 1000, 5_000L)
        c.trimToCap(0)
        assertEquals(2, c.entries().size)
    }

    @Test fun trimToCapAfterUnpack() {
        val c = ExtractCache(tmp.root)
        val a = put(c, "a", 1000, 1_000L)
        val b = put(c, "b", 1000, 2_000L)
        c.trimToCap(1500, keep = b)
        assertTrue(!a.exists() && b.exists())
    }

    @Test fun bytesAreHumanReadable() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 MB", formatBytes(1572864))
        assertEquals("2.00 GB", formatBytes(2L shl 30))
        assertEquals("unknown size", formatBytes(-1))
    }
}
