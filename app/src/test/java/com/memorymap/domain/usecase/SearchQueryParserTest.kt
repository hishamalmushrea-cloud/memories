package com.memorymap.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a search box as structured text.
 *
 * The cases come straight from the specification's own examples, plus the ones
 * that would quietly break: Arabic-Indic digits, the two-word Levantine months,
 * and punctuation the keyboard adds on its own.
 */
class SearchQueryParserTest {

    @Test
    fun `a plain question leaves only the words worth matching`() {
        val query = SearchQueryParser.parse("ماذا كتبت عن العمل؟")

        // `عن` left in would match most of the archive.
        assertEquals(listOf("كتبت", "العمل"), query.terms)
        assertNull(query.person)
        assertNull(query.place)
        assertNull(query.month)
        assertNull(query.year)
    }

    @Test
    fun `a month name is read as a month, not as text`() {
        val query = SearchQueryParser.parse("مذكرات سبتمبر")

        assertEquals(9, query.month)
        assertEquals(listOf("مذكرات"), query.terms)
    }

    @Test
    fun `the word for month is dropped once the month it introduced is read`() {
        val query = SearchQueryParser.parse("ذكريات شهر أغسطس")

        assertEquals(8, query.month)
        assertEquals(listOf("ذكريات"), query.terms)
    }

    @Test
    fun `a place follows its preposition`() {
        val query = SearchQueryParser.parse("كل ما سجلته في صنعاء")

        assertEquals("صنعاء", query.place)
        assertEquals(listOf("سجلته"), query.terms)
    }

    @Test
    fun `a person follows their preposition`() {
        val query = SearchQueryParser.parse("الأحداث مع أحمد")

        assertEquals("أحمد", query.person)
        assertEquals(listOf("الأحداث"), query.terms)
    }

    @Test
    fun `a name of several words is kept together`() {
        assertEquals("أحمد علي", SearchQueryParser.parse("مع أحمد علي").person)
        assertEquals("Ahmed Ali", SearchQueryParser.parse("with Ahmed Ali").person)
    }

    @Test
    fun `a day written beside a month is a date, not a count`() {
        val query = SearchQueryParser.parse("23 سبتمبر")

        assertEquals(23, query.day)
        assertEquals(9, query.month)
        assertTrue(query.terms.isEmpty())
    }

    @Test
    fun `arabic-indic digits read the same as western ones`() {
        val query = SearchQueryParser.parse("٢٣ سبتمبر ٢٠٢٤")

        assertEquals(23, query.day)
        assertEquals(9, query.month)
        assertEquals(2024, query.year)
    }

    @Test
    fun `a bare four digit number is a year`() {
        val query = SearchQueryParser.parse("2024")

        assertEquals(2024, query.year)
        assertTrue(query.terms.isEmpty())
    }

    @Test
    fun `english reads the same way`() {
        val query = SearchQueryParser.parse("in Sanaa with Ahmed 2024")

        assertEquals("Sanaa", query.place)
        assertEquals("Ahmed", query.person)
        assertEquals(2024, query.year)
        assertTrue(query.terms.isEmpty())
    }

    @Test
    fun `levantine month names are recognised, including the two word ones`() {
        assertEquals(12, SearchQueryParser.parse("كانون الأول").month)
        assertEquals(1, SearchQueryParser.parse("كانون الثاني").month)
        assertEquals(10, SearchQueryParser.parse("تشرين الأول").month)
        assertEquals(2, SearchQueryParser.parse("شباط").month)
        assertEquals(9, SearchQueryParser.parse("أيلول").month)
    }

    @Test
    fun `a month name split in two does not leave a meaningless term behind`() {
        val query = SearchQueryParser.parse("ذكرى كانون الأول")

        assertEquals(12, query.month)
        assertEquals(listOf("ذكرى"), query.terms)
    }

    @Test
    fun `spelling variants of the same month agree`() {
        assertEquals(8, SearchQueryParser.parse("أغسطس").month)
        assertEquals(8, SearchQueryParser.parse("اغسطس").month)
        assertEquals(8, SearchQueryParser.parse("august").month)
        assertEquals(8, SearchQueryParser.parse("Aug").month)
        assertEquals(8, SearchQueryParser.parse("أوت").month)
    }

    @Test
    fun `a word that only looks like a month is left alone`() {
        // `أب` folds to the same letters as the Levantine name for August, so it
        // is deliberately not a month: it is the word for father.
        val query = SearchQueryParser.parse("أب")

        assertNull(query.month)
        assertEquals(listOf("أب"), query.terms)
    }

    @Test
    fun `punctuation does not become part of a term`() {
        val query = SearchQueryParser.parse("العمل، الاجتماع (الأول)")

        assertEquals(listOf("العمل", "الاجتماع", "الأول"), query.terms)
    }

    @Test
    fun `an empty box asks for nothing`() {
        assertTrue(SearchQueryParser.parse("").isEmpty)
        assertTrue(SearchQueryParser.parse("   ").isEmpty)
        assertTrue(SearchQueryParser.parse("في").isEmpty)
        assertTrue(SearchQueryParser.parse("شهر").isEmpty)
    }

    @Test
    fun `a preposition with nothing after it is simply dropped`() {
        val query = SearchQueryParser.parse("اجتماع مع")

        assertNull(query.person)
        assertEquals(listOf("اجتماع"), query.terms)
    }

    @Test
    fun `the user's own spelling is kept in the terms`() {
        // Folding is for recognition only; matching happens against what the
        // user actually typed.
        assertEquals(listOf("الإجتماع"), SearchQueryParser.parse("الإجتماع").terms)
    }

    @Test
    fun `everything at once`() {
        val query = SearchQueryParser.parse("رحلة في عدن مع سالم 15 مارس 2019")

        assertEquals("عدن", query.place)
        assertEquals("سالم", query.person)
        assertEquals(15, query.day)
        assertEquals(3, query.month)
        assertEquals(2019, query.year)
        assertEquals(listOf("رحلة"), query.terms)
    }
}
