package com.memorymap.domain.usecase

import java.time.LocalDate

/**
 * Renders the archive as a Markdown document a person can read.
 *
 * This is not the JSON backup - that is a machine format for restoring a phone.
 * This is the memories themselves as prose, so they can be printed, emailed, or
 * opened years from now in any text editor and still say what they meant.
 *
 * The rule that is not obvious, and the reason this has tests of its own: the text
 * belongs to the user, and a memory that happens to contain `#`, `*`, `[...]()` or
 * `<...>` must come out reading exactly as it was written. It must never be
 * reformatted into a heading, a bullet, a link or a script tag by whatever renders
 * the file. So every field is escaped on the way in. Fidelity to the user's own
 * words is the whole point of a readable export, and [escape] is what keeps it.
 *
 * This is pure on purpose - the data layer resolves dates and labels, which need a
 * Context and a Locale, and hands plain strings here, so the shape of the document
 * and the escaping are ordinary code a JVM test can drive with no Android.
 */
object MarkdownExport {

    /**
     * One record, already resolved to display text.
     *
     * [dateLabel] is the formatted date and [emotionLabel] the localized emotion
     * (null when the record carries none). [date] is the real date, kept only so
     * records can be ordered newest-first without parsing display text back.
     */
    data class Record(
        val date: LocalDate,
        val dateLabel: String,
        val kindLabel: String,
        val title: String,
        val body: String,
        val emotionLabel: String? = null,
        val placeName: String? = null,
    )

    /** The characters Markdown gives meaning to in the middle of a line. */
    private val INLINE_SPECIAL = charArrayOf('\\', '`', '*', '_', '[', ']', '<', '>', '|', '~')

    private val WHITESPACE = Regex("\\s+")

    /**
     * The whole archive as one Markdown document: a title, then every record
     * newest-first as its own dated section. An empty archive is just the title -
     * the caller decides whether that is worth writing at all.
     */
    fun render(documentTitle: String, records: List<Record>): String {
        val builder = StringBuilder()
        builder.append("# ").append(escape(documentTitle)).append('\n')
        for (record in records.sortedByDescending { it.date }) {
            builder.append('\n').append("## ").append(heading(record)).append('\n')
            builder.append('\n').append("> ").append(metadata(record)).append('\n')
            if (record.body.isNotBlank()) {
                builder.append('\n').append(escape(record.body)).append('\n')
            }
        }
        return builder.toString()
    }

    /**
     * Escapes [text] so a Markdown renderer shows it verbatim.
     *
     * Two passes, because Markdown reads a line's start differently from its
     * middle. Inline metacharacters are backslash-escaped wherever they appear. A
     * line that would *begin* a block - a `#` heading, a `>` quote, a `-`/`+`
     * bullet, a `1.` numbered item, or a `---` rule - has that one marker escaped
     * so it stays text. Everything else, including the user's line breaks, is left
     * exactly as it was.
     */
    fun escape(text: String): String =
        text.split('\n').joinToString("\n") { line -> blockStart(inline(line)) }

    private fun inline(text: String): String {
        val builder = StringBuilder(text.length + 8)
        for (ch in text) {
            if (ch in INLINE_SPECIAL) builder.append('\\')
            builder.append(ch)
        }
        return builder.toString()
    }

    private fun blockStart(line: String): String {
        val first = line.indexOfFirst { it != ' ' }
        if (first < 0) return line
        val indent = line.substring(0, first)
        val rest = line.substring(first)
        return when {
            rest[0] == '#' || rest[0] == '-' || rest[0] == '+' -> indent + '\\' + rest
            orderedListMarker(rest) >= 0 -> {
                val at = orderedListMarker(rest)
                indent + rest.substring(0, at) + '\\' + rest.substring(at)
            }
            else -> line
        }
    }

    /** The index of the `.`/`)` in a leading `1.`-style marker, or -1. */
    private fun orderedListMarker(text: String): Int {
        var i = 0
        while (i < text.length && text[i].isDigit()) i++
        if (i == 0 || i >= text.length) return -1
        if (text[i] != '.' && text[i] != ')') return -1
        val after = i + 1
        if (after < text.length && !text[after].isWhitespace()) return -1
        return i
    }

    private fun heading(record: Record): String {
        val title = collapse(record.title)
        return if (title.isEmpty()) {
            escape(record.dateLabel)
        } else {
            escape(record.dateLabel) + " — " + escape(title)
        }
    }

    /** The one-line "kind · emotion · place" strip under each heading. */
    private fun metadata(record: Record): String {
        val parts = buildList {
            add(collapse(record.kindLabel))
            record.emotionLabel?.takeIf { it.isNotBlank() }?.let { add(collapse(it)) }
            record.placeName?.takeIf { it.isNotBlank() }?.let { add(collapse(it)) }
        }
        return escape(parts.joinToString(" · "))
    }

    private fun collapse(text: String): String = text.replace(WHITESPACE, " ").trim()
}
