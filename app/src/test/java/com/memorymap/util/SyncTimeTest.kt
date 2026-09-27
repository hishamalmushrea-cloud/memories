package com.memorymap.util

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // --- what a stamp is, and why it is not a clock reading ------------------

    @Test
    fun `a stamp that already carries its offset is left as it is`() {
        assertEquals("2026-09-24T18:03:11Z", SyncTime.asInstantText("2026-09-24T18:03:11Z"))
        // The same moment written with an offset is normalised, not converted.
        assertEquals("2026-09-24T18:03:00Z", SyncTime.asInstantText("2026-09-24T21:03:00+03:00"))
    }

    @Test
    fun `what is written now carries its offset`() {
        val written = SyncTime.nowText()

        assertTrue("expected an instant, got $written", written.endsWith("Z"))
        assertEquals(Instant.parse(written), SyncTime.instant(written))
    }

    /**
     * The failure this whole class exists for.
     *
     * A memory was edited at 10:00 in Sanaa (+03:00) and the phone then flew to
     * London, where the same archive was edited at 09:00 the same morning. The
     * second edit is an hour and a bit later in real time, but its clock reading
     * is smaller - so any comparison that reads both in *one* zone at comparison
     * time picks the first one as the winner, and the newer edit is the one that
     * gets discarded. Stamped as moments, the order is the order they happened in.
     */
    @Test
    fun `an edit made after a flight is still the later one`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Aden"))
            val beforeTheFlight = SyncTime.asInstantText("2026-09-26T10:00:00")

            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            val afterTheFlight = SyncTime.asInstantText("2026-09-26T09:00:00")

            assertEquals("2026-09-26T07:00:00Z", beforeTheFlight)
            // Late September: London is still on summer time, an hour ahead of UTC.
            assertEquals("2026-09-26T08:00:00Z", afterTheFlight)
            assertTrue(
                "the later edit must compare as later",
                SyncTime.instant(afterTheFlight)!!.isAfter(SyncTime.instant(beforeTheFlight)!!),
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /**
     * Rows written before stamps carried an offset.
     *
     * They hold a clock reading and nothing else, so they are read in the zone
     * the device is in - which is the same assumption the code that wrote them
     * was making. This is as good as it gets for those rows, and it is written
     * down here rather than discovered later.
     */
    @Test
    fun `a row written before instants is read in this device's zone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Aden"))
            assertEquals(
                SyncTime.instant("2026-09-26T07:00:00Z"),
                SyncTime.instant("2026-09-26T10:00:00"),
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `a stamp is shown in this device's zone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Aden"))
            assertEquals("2026-09-26T10:00", SyncTime.local("2026-09-26T07:00:00Z").toString())
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
