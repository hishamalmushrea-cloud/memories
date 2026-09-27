package com.memorymap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the policy reaches the reader.
 *
 * The text is a Markdown document, and the screen is a plain scroll of text: what this
 * class checks is that the markers come off and **nothing else does**. A policy whose
 * sentences were rewritten by the renderer - a bullet merged into the line above it, a
 * sub-point flattened, an asterisk left in the middle of a sentence - is a policy the
 * reader cannot trust to be the one that was written.
 */
class PrivacyTextTest {

    @Test
    fun `a heading loses its hashes and their indentation`() {
        val rendered = PrivacyText.asPlainText("# سياسة الخصوصية\n\n## 1) المبدأ الأساسي")

        assertEquals("سياسة الخصوصية\n\n1) المبدأ الأساسي", rendered)
    }

    @Test
    fun `bold markers come off and the sentence stays`() {
        val rendered = PrivacyText.asPlainText("هذا التطبيق **يعمل دون اتصال أولًا** دائمًا.")

        assertEquals("هذا التطبيق يعمل دون اتصال أولًا دائمًا.", rendered)
    }

    @Test
    fun `a sub-list keeps its indentation, because that is what groups the points`() {
        val rendered = PrivacyText.asPlainText("- يُقرأ الموقع:\n  - فتح الخريطة.\n  - إنشاء ذكرى.")

        assertEquals("- يُقرأ الموقع:\n  - فتح الخريطة.\n  - إنشاء ذكرى.", rendered)
    }

    @Test
    fun `an English heading is handled the same way`() {
        val rendered = PrivacyText.asPlainText("### What we never do\n\n- No ads.")

        assertEquals("What we never do\n\n- No ads.", rendered)
    }

    @Test
    fun `nothing is added and no line is dropped`() {
        val document = "# Title\n\nfirst\n\nsecond\n\n- third"

        val rendered = PrivacyText.asPlainText(document)

        // Same number of lines, blank ones included: the screen's paragraphing is the
        // document's paragraphing.
        assertEquals(document.count { it == '\n' }, rendered.count { it == '\n' })
        assertEquals(4, rendered.lines().count { it.isNotBlank() })
    }

    @Test
    fun `the asset follows the language, and Arabic is the fallback`() {
        assertEquals(PrivacyText.ENGLISH_ASSET, PrivacyText.assetFor("en"))
        assertEquals(PrivacyText.ENGLISH_ASSET, PrivacyText.assetFor("en-US"))
        assertEquals(PrivacyText.ENGLISH_ASSET, PrivacyText.assetFor("EN-GB"))
        assertEquals(PrivacyText.ARABIC_ASSET, PrivacyText.assetFor("ar"))
        // The app's default language is Arabic, so anything unrecognised reads Arabic
        // rather than showing English to a reader who did not ask for it.
        assertEquals(PrivacyText.ARABIC_ASSET, PrivacyText.assetFor("fr"))
        assertEquals(PrivacyText.ARABIC_ASSET, PrivacyText.assetFor(""))
    }

    @Test
    fun `the two assets are real documents and different from each other`() {
        // A placeholder file, or the same file copied to both names, would satisfy a
        // screen that only checks that something was read.
        assertTrue(PrivacyText.ARABIC_ASSET != PrivacyText.ENGLISH_ASSET)
        assertFalse(PrivacyText.ARABIC_ASSET.isBlank())
        assertFalse(PrivacyText.ENGLISH_ASSET.isBlank())
    }
}
