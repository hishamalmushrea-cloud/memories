package com.memorymap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The importer decides what counts as an attachment, so these are the rules that
 * keep a random file out of the archive. Only image, audio and video are
 * accepted, and nothing is ever inspected beyond its type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaImporterTest {

    @Test
    fun `the three supported families map onto the three media types`() {
        assertEquals(com.memorymap.domain.model.MediaType.PHOTO, MediaImporter.mediaTypeFor("image/jpeg"))
        assertEquals(com.memorymap.domain.model.MediaType.AUDIO, MediaImporter.mediaTypeFor("audio/mpeg"))
        assertEquals(com.memorymap.domain.model.MediaType.VIDEO, MediaImporter.mediaTypeFor("video/mp4"))
    }

    @Test
    fun `mime matching ignores case and surrounding space`() {
        assertEquals(com.memorymap.domain.model.MediaType.PHOTO, MediaImporter.mediaTypeFor("  IMAGE/PNG "))
        assertEquals(com.memorymap.domain.model.MediaType.VIDEO, MediaImporter.mediaTypeFor("Video/WebM"))
    }

    @Test
    fun `anything that is not a photo, a recording or a video is refused`() {
        assertNull(MediaImporter.mediaTypeFor(null))
        assertNull(MediaImporter.mediaTypeFor(""))
        assertNull(MediaImporter.mediaTypeFor("   "))
        assertNull(MediaImporter.mediaTypeFor("application/pdf"))
        assertNull(MediaImporter.mediaTypeFor("text/plain"))
        // A prefix that merely starts like a supported family is not one.
        assertNull(MediaImporter.mediaTypeFor("imagezip"))
    }

    @Test
    fun `known mimes get a real extension and everything else a neutral one`() {
        assertEquals("jpg", MediaImporter.extensionFor("image/jpeg"))
        assertEquals("png", MediaImporter.extensionFor("image/png"))
        assertEquals("m4a", MediaImporter.extensionFor("audio/x-m4a"))
        assertEquals("mp4", MediaImporter.extensionFor("video/mp4"))
        assertEquals("bin", MediaImporter.extensionFor("application/octet-stream"))
        assertEquals("bin", MediaImporter.extensionFor(null))
    }

    @Test
    fun `durations read as minutes and zero padded seconds`() {
        assertEquals("0:00", MediaImporter.formatDuration(null))
        assertEquals("0:00", MediaImporter.formatDuration(0L))
        assertEquals("0:00", MediaImporter.formatDuration(-1L))
        assertEquals("0:05", MediaImporter.formatDuration(5_000L))
        assertEquals("0:09", MediaImporter.formatDuration(9_999L))
        assertEquals("1:05", MediaImporter.formatDuration(65_000L))
        assertEquals("60:00", MediaImporter.formatDuration(3_600_000L))
    }
}
