package com.memorymap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The timestamp conversion at the Room/server boundary.
 *
 * Every assertion here holds in any device timezone, because each one converts
 * there and back rather than assuming a particular offset.
 */
class SyncTimeTest {

    @Test
    fun `a naive local time survives the round trip`() {
        val local = "2026-09-24T21:03:11.482"
        assertEquals(local, SyncTime.toLocalText(SyncTime.toInstantText(local)))
    }

    @Test
    fun `an instant comes back as the same instant`() {
        val instant = "2026-09-24T18:03:11Z"
        val local = SyncTime.toLocalText(instant)
        assertEquals(instant, SyncTime.toInstantText(local))
    }

    @Test
    fun `an offset is honoured when converting an instant`() {
        // 21:03 at +03:00 is the same moment as 18:03 UTC.
        assertEquals(
            SyncTime.toLocalText("2026-09-24T18:03:00Z"),
            SyncTime.toLocalText("2026-09-24T21:03:00+03:00"),
        )
    }

    @Test
    fun `precision is neither invented nor lost`() {
        assertEquals(
            "2026-09-24T21:03",
            SyncTime.toLocalText(SyncTime.toInstantText("2026-09-24T21:03")),
        )
        assertEquals(
            "2026-09-24T21:03:11.482123456",
            SyncTime.toLocalText(SyncTime.toInstantText("2026-09-24T21:03:11.482123456")),
        )
    }

    @Test
    fun `the instant form always carries a zone`() {
        val instant = SyncTime.toInstantText("2026-09-24T21:03:11.482")
        assertEquals(true, instant?.endsWith("Z"))
    }

    @Test
    fun `empty values stay empty`() {
        assertNull(SyncTime.toInstantText(null))
        assertNull(SyncTime.toInstantText(""))
        assertNull(SyncTime.toInstantText("   "))
        assertNull(SyncTime.toLocalText(null))
        assertNull(SyncTime.toLocalText(""))
    }

    @Test
    fun `an unreadable value is passed through rather than dropped`() {
        // Losing a timestamp is recoverable; aborting a sync over one is not.
        assertEquals("nonsense", SyncTime.toInstantText("nonsense"))
        assertEquals("nonsense", SyncTime.toLocalText("nonsense"))
    }
}
