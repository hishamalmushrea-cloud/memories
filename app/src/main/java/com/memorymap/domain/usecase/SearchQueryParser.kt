package com.memorymap.domain.usecase

import java.util.Locale

/**
 * What a search box entry actually meant.
 *
 * The free text is split into the parts the database can act on — a person, a
 * place, a month, a day, a year — and whatever is left over stays in [terms] and
 * is matched as text. Nothing here guesses or infers meaning: every field comes
 * from a pattern that is written down below.
 */
data class SearchQuery(
    val raw: String,
    /** Free-text terms, lower-cased, stop words removed. */
    val terms: List<String> = emptyList(),
    val person: String? = null,
    val place: String? = null,
    /** 1..12 */
    val month: Int? = null,
    /** 1..31, only when it appeared next to a month name. */
    val day: Int? = null,
    val year: Int? = null,
) {
    val isBlank: Boolean get() = raw.isBlank()

    /** True when there is nothing at all to search for. */
    val isEmpty: Boolean
        get() = terms.isEmpty() && person == null && place == null &&
            month == null && day == null && year == null
}

/**
 * Reads a search box entry as structured text.
 *
 * The specification asks for exactly this: structured text search, and no AI.
 * So the whole behaviour is a list of patterns and a stop-word list, all of it
 * here where it can be tested, rather than a model that cannot be explained.
 *
 * Recognised, in Arabic and English:
 *  - `مع أحمد` / `with Ahmed` — a person;
 *  - `في صنعاء` / `in Sanaa` — a place;
 *  - a month name, optionally preceded by `شهر`, in the Gregorian Arabic,
 *    Levantine and English sets;
 *  - a day written next to a month name, as in `23 سبتمبر`;
 *  - a bare four-digit year;
 *  - everything else becomes a text term, minus stop words that would otherwise
 *    match almost the entire archive.
 */
object SearchQueryParser {

    fun parse(raw: String): SearchQuery {
        if (raw.isBlank()) return SearchQuery(raw = raw)

        var tokens = tokenize(raw)
        var month: Int? = null
        var day: Int? = null
        var year: Int? = null

        // Two-word month names are matched first, or `كانون` alone would be
        // taken and `الاول` left behind as a meaningless term.
        val bigramIndex = findBigramMonth(tokens)
        if (bigramIndex != null) {
            month = bigramMonth(tokens[bigramIndex.first], tokens[bigramIndex.second])
            tokens = tokens.filterIndexed { i, _ -> i != bigramIndex.first && i != bigramIndex.second }
        }

        // A day number written immediately before a month name, as in
        // `23 سبتمبر`, belongs together: it is a date, not a count.
        val monthIndex = tokens.indexOfFirst { normalize(it) in MONTHS }
        if (monthIndex >= 0) {
            month = month ?: MONTHS[normalize(tokens[monthIndex])]
            val dayCandidate = tokens.getOrNull(monthIndex - 1)?.toWesternDigits()?.toIntOrNull()
            if (dayCandidate != null && dayCandidate in 1..31) {
                day = dayCandidate
                tokens = tokens.filterIndexed { i, _ -> i != monthIndex && i != monthIndex - 1 }
            } else {
                tokens = tokens.filterIndexed { i, _ -> i != monthIndex }
            }
        }

        // A bare four-digit number is read as a year. Only one is taken; a range
        // is what the date filter is for.
        if (year == null) {
            val yearIndex = tokens.indexOfFirst { isYear(it) }
            if (yearIndex >= 0) {
                year = tokens[yearIndex].toWesternDigits().toIntOrNull()
                tokens = tokens.filterIndexed { i, _ -> i != yearIndex }
            }
        }

        // `شهر` on its own is a filler once the month it introduced is gone.
        tokens = tokens.filter { normalize(it) !in FILLERS }

        val person = captureAfter(tokens, PERSON_PREPOSITIONS)
        val place = captureAfter(person.remaining, PLACE_PREPOSITIONS)

        val terms = place.remaining
            .filter { normalize(it) !in STOP_WORDS }
            .filter { it.isNotBlank() }
            .map { it.lowercase(Locale.ROOT) }

        return SearchQuery(
            raw = raw,
            terms = terms,
            person = person.value,
            place = place.value,
            month = month,
            day = day,
            year = year,
        )
    }

    private data class Capture(val value: String?, val remaining: List<String>)

    /**
     * Takes the words following a preposition as one name, up to the next word
     * that is itself a keyword, so `مع أحمد علي` is one person and `في صنعاء في
     * ٢٠٢٤` still leaves the year to be read.
     */
    private fun captureAfter(tokens: List<String>, prepositions: Set<String>): Capture {
        val index = tokens.indexOfFirst { normalize(it) in prepositions }
        if (index < 0) return Capture(null, tokens)

        val name = mutableListOf<String>()
        val remaining = mutableListOf<String>()
        var taking = false

        tokens.forEachIndexed { i, token ->
            when {
                i == index -> taking = true
                taking && isKeyword(token) -> {
                    taking = false
                    remaining += token
                }
                taking -> name += token
                else -> remaining += token
            }
        }

        val value = name.joinToString(" ").trim().ifBlank { null }
        return Capture(value, remaining)
    }

    private fun isKeyword(token: String): Boolean {
        val normalized = normalize(token)
        return normalized in MONTHS || normalized in PERSON_PREPOSITIONS ||
            normalized in PLACE_PREPOSITIONS || normalized in FILLERS || isYear(token)
    }

    private fun findBigramMonth(tokens: List<String>): Pair<Int, Int>? {
        for (i in 0 until tokens.size - 1) {
            if (bigramMonth(tokens[i], tokens[i + 1]) != null) return i to i + 1
        }
        return null
    }

    private fun bigramMonth(first: String, second: String): Int? =
        BIGRAM_MONTHS[normalize(first) to normalize(second)]

    private fun isYear(token: String): Boolean {
        val value = token.toWesternDigits().toIntOrNull() ?: return false
        return value in 1900..2100
    }

    /** `٢٠٢٤` is the same number as `2024`; the keyboard should not matter. */
    private fun String.toWesternDigits(): String = buildString(length) {
        this@toWesternDigits.forEach { character ->
            append(
                when (character) {
                    in '٠'..'٩' -> '0' + (character - '٠')
                    in '۰'..'۹' -> '0' + (character - '۰')
                    else -> character
                },
            )
        }
    }

    /** Splits on whitespace and strips the punctuation a search box accumulates. */
    private fun tokenize(raw: String): List<String> = raw
        .split(WHITESPACE)
        .map { it.trim(*PUNCTUATION) }
        .filter { it.isNotBlank() }

    /**
     * Folds the differences that are spelling, not meaning, so `أغسطس` and
     * `اغسطس` are the same month and `العمل؟` is the same term as `العمل`.
     *
     * Used for recognition only: the terms returned to the caller keep the
     * user's own spelling, because that is what has to match the database.
     */
    internal fun normalize(token: String): String = token
        .trim(*PUNCTUATION)
        .replace(ARABIC_DIACRITICS, "")
        .replace('أ', 'ا')
        .replace('إ', 'ا')
        .replace('آ', 'ا')
        .replace('ٱ', 'ا')
        .replace('ى', 'ي')
        .replace('ؤ', 'و')
        .replace('ئ', 'ي')
        .replace('ة', 'ه')
        .lowercase(Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")
    private val PUNCTUATION: CharArray = ".,;:!?\"'()[]{}«»؟،ـ\u0640".toCharArray()
    private val ARABIC_DIACRITICS = Regex("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED]")

    // Every set below is compared against `normalize(token)`, so the members are
    // stored folded as well. Writing `إلى` here and comparing `الي` would never
    // match, and the failure would be silent.

    /** `مع` / `with` and friends. */
    private val PERSON_PREPOSITIONS = setOf("مع", "بمعية", "with", "alongside").mapTo(mutableSetOf()) { normalize(it) }

    /** `في` / `in` and friends. */
    private val PLACE_PREPOSITIONS = setOf("في", "إلى", "in", "at", "near").mapTo(mutableSetOf()) { normalize(it) }

    /** Words that carry no search meaning once the month they introduced is gone. */
    private val FILLERS = setOf("شهر", "أشهر", "month", "months", "خلال", "during").mapTo(mutableSetOf()) { normalize(it) }

    /**
     * Dropped from the free-text terms.
     *
     * Left in, `ماذا كتبت عن العمل` would search for `عن` and match most of the
     * archive. This list is deliberately short: dropping a word the user did
     * mean is worse than keeping one they did not.
     */
    private val STOP_WORDS = setOf(
        "في", "من", "على", "عن", "الي", "ما", "ماذا", "متي", "اين", "كل", "التي", "الذي",
        "هذا", "هذه", "هو", "هي", "و", "ثم", "او", "قد", "كان", "كانت", "لو", "لا",
        "the", "a", "an", "of", "to", "and", "or", "what", "when", "where", "which",
        "for", "with", "in", "on", "at", "is", "was", "were", "my", "me",
    ).mapTo(mutableSetOf()) { normalize(it) }

    /** Single-word month names, in the sets an Arabic speaker is likely to type. */
    private val MONTHS: Map<String, Int> = buildMap {
        // Gregorian Arabic, as used across the Gulf, Yemen and Egypt.
        put("يناير", 1)
        put("فبراير", 2)
        put("مارس", 3)
        put("ابريل", 4)
        put("مايو", 5)
        put("يونيو", 6)
        put("يوليو", 7)
        put("اغسطس", 8)
        put("سبتمبر", 9)
        put("اكتوبر", 10)
        put("نوفمبر", 11)
        put("ديسمبر", 12)
        // Maghrebi variants of the same months.
        put("جانفي", 1)
        put("فيفري", 2)
        put("افريل", 4)
        put("جوان", 6)
        put("جويليه", 7)
        put("اوت", 8)
        // Levantine and Iraqi names that are a single word. `آب` is left out on
        // purpose: it folds to the same letters as the word for father, so
        // reading it as August would silently eat a real search.
        put("شباط", 2)
        put("اذار", 3)
        put("نيسان", 4)
        put("ايار", 5)
        put("حزيران", 6)
        put("تموز", 7)
        put("ايلول", 9)
        // English, full and abbreviated.
        put("january", 1)
        put("february", 2)
        put("march", 3)
        put("april", 4)
        put("may", 5)
        put("june", 6)
        put("july", 7)
        put("august", 8)
        put("september", 9)
        put("october", 10)
        put("november", 11)
        put("december", 12)
        put("jan", 1)
        put("feb", 2)
        put("mar", 3)
        put("apr", 4)
        put("jun", 6)
        put("jul", 7)
        put("aug", 8)
        put("sep", 9)
        put("sept", 9)
        put("oct", 10)
        put("nov", 11)
        put("dec", 12)
    }.mapKeys { normalize(it.key) }

    /** The Levantine months whose name is two words. */
    private val BIGRAM_MONTHS: Map<Pair<String, String>, Int> = mapOf(
        ("كانون" to "الثاني") to 1,
        ("تشرين" to "الاول") to 10,
        ("تشرين" to "الثاني") to 11,
        ("كانون" to "الاول") to 12,
    ).mapKeys { normalize(it.key.first) to normalize(it.key.second) }
}
