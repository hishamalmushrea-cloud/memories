package com.memorymap.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The share target's one decision, driven directly - no Android, no Intent.
 *
 * The app is offered everything another app can share, so the interesting part is
 * not that it keeps plain text but that it refuses everything else: an image, a
 * file stream, a custom type, a blank body. Each refusal is a case the archive
 * has no business pretending to understand, and each is asserted here.
 */
class ShareCaptureTest {

    @Test
    fun `a plain-text share is captured`() {
        val decision = ShareCapture.from(
            action = ShareCapture.ACTION_SEND,
            mimeType = ShareCapture.MIME_TEXT_PLAIN,
            sharedText = "a note worth keeping",
        )
        assertEquals(ShareCapture.Decision.CaptureText("a note worth keeping"), decision)
    }

    @Test
    fun `the captured text is trimmed`() {
        val decision = ShareCapture.from(
            action = ShareCapture.ACTION_SEND,
            mimeType = ShareCapture.MIME_TEXT_PLAIN,
            sharedText = "  padded both ends\n",
        )
        assertEquals(ShareCapture.Decision.CaptureText("padded both ends"), decision)
    }

    @Test
    fun `a share that is only whitespace is ignored`() {
        val decision = ShareCapture.from(
            action = ShareCapture.ACTION_SEND,
            mimeType = ShareCapture.MIME_TEXT_PLAIN,
            sharedText = "   \n\t ",
        )
        assertEquals(ShareCapture.Decision.Ignore, decision)
    }

    @Test
    fun `a share with no text at all is ignored`() {
        val decision = ShareCapture.from(
            action = ShareCapture.ACTION_SEND,
            mimeType = ShareCapture.MIME_TEXT_PLAIN,
            sharedText = null,
        )
        assertEquals(ShareCapture.Decision.Ignore, decision)
    }

    @Test
    fun `an action other than send is ignored`() {
        // The launcher intent, or any other verb, is not a share even if it happens
        // to carry text.
        val decision = ShareCapture.from(
            action = "android.intent.action.MAIN",
            mimeType = ShareCapture.MIME_TEXT_PLAIN,
            sharedText = "not a share",
        )
        assertEquals(ShareCapture.Decision.Ignore, decision)
    }

    @Test
    fun `a non-text mime type is ignored`() {
        // An image or a file stream is someone else's data shape; the archive keeps words.
        val decision = ShareCapture.from(
            action = ShareCapture.ACTION_SEND,
            mimeType = "image/jpeg",
            sharedText = "caption that came with a photo",
        )
        assertEquals(ShareCapture.Decision.Ignore, decision)
    }

    @Test
    fun `a missing action or mime type is ignored`() {
        assertEquals(
            ShareCapture.Decision.Ignore,
            ShareCapture.from(null, ShareCapture.MIME_TEXT_PLAIN, "text"),
        )
        assertEquals(
            ShareCapture.Decision.Ignore,
            ShareCapture.from(ShareCapture.ACTION_SEND, null, "text"),
        )
    }
}
