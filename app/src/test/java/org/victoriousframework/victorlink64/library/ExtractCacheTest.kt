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

    @Test fun bytesAreHumanReadable() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 MB", formatBytes(1572864))
        assertEquals("2.00 GB", formatBytes(2L shl 30))
        assertEquals("unknown size", formatBytes(-1))
    }
}
