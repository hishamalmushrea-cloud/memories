package com.memorymap.domain.usecase

/**
 * What the app should do with an intent another app fired at it.
 *
 * Sharing a note, a quote or a message into the archive is the fastest way to
 * keep it: the user is already reading the thing, so the app should take the text
 * and open a ready-to-save record rather than make them retype it. But an intent
 * is a firehose - the app is offered everything from a single image to a stream
 * of files it has no business touching - so the decision of what is actually a
 * plain-text share worth keeping is worth writing down on its own, where a plain
 * JVM test can drive every case with no Android and no [android.content.Intent].
 *
 * The string constants deliberately mirror [android.content.Intent.ACTION_SEND]
 * and the `text/plain` MIME type instead of importing them, which is what keeps
 * this object pure and testable off the device.
 */
object ShareCapture {

    /** Mirrors `Intent.ACTION_SEND`: the "share this" verb. */
    const val ACTION_SEND = "android.intent.action.SEND"

    /** The one MIME type captured: a plain run of text. */
    const val MIME_TEXT_PLAIN = "text/plain"

    sealed interface Decision {
        /** Not a plain-text share the archive can keep; do nothing. */
        data object Ignore : Decision

        /** A plain-text share: keep [text], already trimmed, as a new record's body. */
        data class CaptureText(val text: String) : Decision
    }

    /**
     * @param action the intent's action, e.g. [ACTION_SEND].
     * @param mimeType the intent's resolved type, e.g. [MIME_TEXT_PLAIN].
     * @param sharedText the `EXTRA_TEXT` the sending app attached, if any.
     */
    fun from(action: String?, mimeType: String?, sharedText: String?): Decision {
        // Only the share verb, and only plain text. An image, a file stream or a
        // custom type is someone else's data shape; the archive keeps words.
        if (action != ACTION_SEND) return Decision.Ignore
        if (mimeType != MIME_TEXT_PLAIN) return Decision.Ignore
        // Trimmed here so the editor never opens with stray leading or trailing
        // whitespace, and an all-whitespace share is treated as nothing at all.
        val text = sharedText?.trim().orEmpty()
        if (text.isEmpty()) return Decision.Ignore
        return Decision.CaptureText(text)
    }
}
