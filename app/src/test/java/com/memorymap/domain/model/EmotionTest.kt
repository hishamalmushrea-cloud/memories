package com.memorymap.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmotionTest {

    @Test
    fun `the palette matches the design specification exactly`() {
        assertEquals(
            mapOf(
                Emotion.HAPPY to "#FFC107",
                Emotion.SAD to "#2196F3",
                Emotion.LOVE to "#E91E63",
                Emotion.FEAR to "#9C27B0",
                Emotion.PRIDE to "#FF9800",
                Emotion.NOSTALGIA to "#795548",
            ),
            Emotion.entries.associateWith { it.colorHex },
        )
    }

    @Test
    fun `parsing is case insensitive and unknown values give null`() {
        assertEquals(Emotion.LOVE, Emotion.fromName("love"))
        assertEquals(Emotion.PRIDE, Emotion.fromName("PRIDE"))
        assertNull(Emotion.fromName("angry"))
        assertNull(Emotion.fromName(null))
    }

    @Test
    fun `visibility defaults to private for unknown or missing values`() {
        assertEquals(Visibility.PRIVATE, Visibility.fromName(null))
        assertEquals(Visibility.PRIVATE, Visibility.fromName("nonsense"))
        assertEquals(Visibility.SHARED, Visibility.fromName("shared"))
    }

    @Test
    fun `sync status knows which states still owe the server something`() {
        assertEquals(
            setOf(SyncStatus.PENDING_CREATE, SyncStatus.PENDING_UPDATE, SyncStatus.PENDING_DELETE, SyncStatus.SYNC_ERROR),
            SyncStatus.entries.filter { it.isPending }.toSet(),
        )
        assertEquals(SyncStatus.PENDING_CREATE, SyncStatus.fromName(null))
        assertEquals(SyncStatus.SYNCED, SyncStatus.fromName("SYNCED"))
    }

    @Test
    fun `media types cover photo, audio and video only`() {
        assertEquals(listOf("PHOTO", "AUDIO", "VIDEO"), MediaType.entries.map { it.name })
        assertEquals(MediaType.VIDEO, MediaType.fromName("video"))
        assertNull(MediaType.fromName("document"))
    }
}
