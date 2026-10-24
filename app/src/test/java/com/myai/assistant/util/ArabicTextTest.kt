package com.myai.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicTextTest {

    @Test
    fun `normalizes hamza forms and diacritics`() {
        assertEquals("افتح", ArabicText.normalize("أفتح"))
        assertEquals("اعدادات", ArabicText.normalize("إعدادات"))
        assertEquals("شغل", ArabicText.normalize("شغّل"))
        assertEquals("موسيقي", ArabicText.normalize("موسيقى"))
        assertEquals("رساله", ArabicText.normalize("رسالة"))
    }

    @Test
    fun `strips definite article for matching`() {
        assertEquals("واتساب", ArabicText.stripDefiniteArticle("الواتساب"))
        assertEquals("ات", ArabicText.stripDefiniteArticle("ات"))
    }

    @Test
    fun `looseEquals ignores article and hamza`() {
        assertTrue(ArabicText.looseEquals("الواتساب", "واتساب"))
        assertFalse(ArabicText.looseEquals("تليجرام", "واتساب"))
    }

    @Test
    fun `digitsOnly keeps leading plus`() {
        assertEquals("+966501234567", ArabicText.digitsOnly("+966 50 123-4567"))
        assertEquals("0501234567", ArabicText.digitsOnly("050 123 4567"))
    }
}
