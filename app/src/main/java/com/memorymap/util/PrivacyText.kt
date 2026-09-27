package com.memorymap.util

import android.content.Context

/**
 * The privacy policy as the app shows it.
 *
 * Google Play requires the policy to be reachable **inside the app**, not only from the
 * store listing, and the specification lists it as a deliverable in both languages. The
 * text is the same document that lives in `docs/legal/`: it is shipped as a copy under
 * `assets/`, and `ci/check-docs.py` fails if the copy and the document ever differ, so the
 * app and the repository cannot drift apart.
 *
 * Rendering is deliberately plain. The document is Markdown, and the only things its
 * markers do here are get in the way - a policy is read, not navigated - so the heading
 * hashes and the bold markers are removed and the rest of every line is left exactly as
 * written. Nothing reformats the sentences: a paragraph that says what the app does must
 * reach the reader unchanged.
 */
object PrivacyText {

    const val ARABIC_ASSET = "privacy_ar.md"
    const val ENGLISH_ASSET = "privacy_en.md"

    /** The document for [languageTag]; Arabic for anything that is not English. */
    fun assetFor(languageTag: String): String =
        if (languageTag.startsWith("en", ignoreCase = true)) ENGLISH_ASSET else ARABIC_ASSET

    /**
     * The text of the policy, or an empty string when the asset cannot be read.
     *
     * An empty result is not silent: the screen shows its own fallback line, because a
     * blank screen where a policy should be is the one outcome that reads as "there is no
     * policy".
     */
    fun read(context: Context, languageTag: String): String = runCatching {
        context.assets.open(assetFor(languageTag)).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    /**
     * The document with its Markdown markers removed for reading as plain text.
     *
     * Only a heading loses its leading whitespace, because that whitespace is what the
     * hash marks were drawing; the indentation of the sub-lists is left alone, since it is
     * how the reader sees which points belong together.
     */
    fun asPlainText(markdown: String): String = markdown
        .lineSequence()
        .joinToString("\n") { line ->
            val trimmed = line.trimStart()
            val body = if (trimmed.startsWith("#")) trimmed.trimStart('#').trimStart() else line
            body.replace("**", "")
        }
}
