package com.memorymap.domain.usecase

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readable export, driven directly - no Android, no database, no file.
 *
 * Two things are worth holding here. The first is fidelity: the text belongs to
 * the user, so a memory that contains `#`, `*`, a link or a tag must come out
 * reading exactly as it was written, never reformatted into structure by whatever
 * renders the file. Every escaping case below is one such character being
 * defused. The second is shape: the archive comes out newest-first, each record a
 * dated section, and an empty archive is just a title.
 */
class MarkdownExportTest {

    // ---- escaping: inline metacharacters are defused wherever they appear ----

    @Test
    fun `plain text passes through untouched`() {
        assertEquals("plain text", MarkdownExport.escape("plain text"))
    }

    @Test
    fun `a hash cannot become a heading`() {
        assertEquals("\\# Title", MarkdownExport.escape("# Title"))
    }

    @Test
    fun `emphasis markers are shown, not felt`() {
        assertEquals("See \\*stars\\* and \\_sea\\_", MarkdownExport.escape("See *stars* and _sea_"))
    }

    @Test
    fun `a link is text, not a link`() {
        assertEquals("Click \\[here\\](http://x)", MarkdownExport.escape("Click [here](http://x)"))
    }

    @Test
    fun `a tag is text, not markup`() {
        assertEquals("\\<b\\>bold\\</b\\>", MarkdownExport.escape("<b>bold</b>"))
    }

    @Test
    fun `a backslash is shown literally`() {
        assertEquals("back\\\\slash", MarkdownExport.escape("back\\slash"))
    }

    // ---- escaping: a marker only starts a block at the start of a line ----

    @Test
    fun `a leading bullet is text`() {
        assertEquals("\\- not a bullet", MarkdownExport.escape("- not a bullet"))
    }

    @Test
    fun `a leading number is text`() {
        assertEquals("1\\. not a list", MarkdownExport.escape("1. not a list"))
    }

    @Test
    fun `a leading quote is text`() {
        assertEquals("\\> not a quote", MarkdownExport.escape("> not a quote"))
    }

    @Test
    fun `a marker mid-line is already handled by the inline pass`() {
        // Only a line's *start* can begin a block, so "a - b" needs no block escape;
        // the hyphen is ordinary text and is left alone.
        assertEquals("a - b", MarkdownExport.escape("a - b"))
    }

    @Test
    fun `line breaks are kept and each line is judged on its own`() {
        assertEquals("ok\n\\# x", MarkdownExport.escape("ok\n# x"))
    }

    // ---- shape ----

    @Test
    fun `an empty archive is just its title`() {
        assertEquals("# My Archive\n", MarkdownExport.render("My Archive", emptyList()))
    }

    @Test
    fun `one record renders as a dated section with its strip and body`() {
        val record = MarkdownExport.Record(
            date = LocalDate.of(2019, 5, 3),
            dateLabel = "3 May 2019",
            kindLabel = "Memory",
            title = "The old road",
            body = "We walked it every summer.",
            emotionLabel = "Nostalgia",
            placeName = "Sanaa",
        )
        val expected = "# My Archive\n" +
            "\n## 3 May 2019 — The old road\n" +
            "\n> Memory · Nostalgia · Sanaa\n" +
            "\nWe walked it every summer.\n"
        assertEquals(expected, MarkdownExport.render("My Archive", listOf(record)))
    }

    @Test
    fun `records come out newest first`() {
        val older = record(date = LocalDate.of(2018, 1, 1), title = "Older")
        val newer = record(date = LocalDate.of(2021, 1, 1), title = "Newer")
        val out = MarkdownExport.render("A", listOf(older, newer))
        assertTrue(out.indexOf("Newer") < out.indexOf("Older"))
    }

    @Test
    fun `a record with no title falls back to its date`() {
        val out = MarkdownExport.render("A", listOf(record(title = "  ", dateLabel = "3 May 2019")))
        assertTrue(out.contains("## 3 May 2019\n"))
    }

    @Test
    fun `the strip omits an emotion and place the record does not carry`() {
        val out = MarkdownExport.render(
            "A",
            listOf(record(emotionLabel = null, placeName = null, kindLabel = "Diary entry")),
        )
        assertTrue(out.contains("> Diary entry\n"))
    }

    @Test
    fun `a record with no body renders no body block`() {
        val out = MarkdownExport.render("A", listOf(record(body = "  ")))
        // With a blank body the document ends on the metadata strip - nothing is
        // appended after it. The helper's defaults carry an emotion and a place,
        // so the strip is the full "kind · emotion · place", not the kind alone.
        assertTrue(out.endsWith("> Memory · Nostalgia · Sanaa\n"))
    }

    // ---- fidelity end to end, through the document ----

    @Test
    fun `a title and body full of markup stay literal in the document`() {
        val out = MarkdownExport.render(
            "A",
            listOf(record(title = "# Heading", body = "* hi")),
        )
        assertTrue(out.contains("— \\# Heading"))
        assertTrue(out.contains("\n\\* hi"))
    }

    private fun record(
        date: LocalDate = LocalDate.of(2019, 5, 3),
        dateLabel: String = "3 May 2019",
        kindLabel: String = "Memory",
        title: String = "Title",
        body: String = "Body",
        emotionLabel: String? = "Nostalgia",
        placeName: String? = "Sanaa",
    ) = MarkdownExport.Record(date, dateLabel, kindLabel, title, body, emotionLabel, placeName)
}
