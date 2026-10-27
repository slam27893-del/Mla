package com.myai.assistant.draft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftTextParserTest {

    private val screen = """
        محظور
        Edgar
        Fang
        فريقك
        Poco
        Bull
        الخصم
        Leon
        Crow
        الخريطة: Hard Rock Mine
    """.trimIndent()

    @Test
    fun `parses bans allies enemies and map from labelled sections`() {
        val context = DraftTextParser.parse(screen).context
        assertEquals(listOf("Edgar", "Fang"), context.bans)
        assertEquals(listOf("Poco", "Bull"), context.allies)
        assertEquals(listOf("Leon", "Crow"), context.enemies)
        assertEquals("Hard Rock Mine", context.map)
        assertEquals(GameMode.GEM_GRAB, context.mode)
        assertEquals(RecognitionSource.SCREEN_TEXT, context.source)
    }

    @Test
    fun `names without a section header are never assigned to a team`() {
        val result = DraftTextParser.parse("Bull\nLeon\nPoco")
        assertTrue(result.context.allies.isEmpty())
        assertTrue(result.context.enemies.isEmpty())
        assertTrue(result.context.bans.isEmpty())
        assertEquals(3, result.context.unresolved.size)
    }

    @Test
    fun `empty or unrelated text is not usable`() {
        assertFalse(DraftTextParser.parse("").isUsable)
        assertFalse(DraftTextParser.parse("مرحبًا بك في اللعبة").isUsable)
    }

    @Test
    fun `arabic aliases are mapped to canonical names`() {
        val context = DraftTextParser.parse(
            """
            فريقك
            بو
            الخصم
            إدغار
            الخريطة: Dry Season
            """.trimIndent()
        ).context
        assertEquals(listOf("Bo"), context.allies)
        assertEquals(listOf("Edgar"), context.enemies)
        assertEquals(GameMode.BOUNTY, context.mode)
    }

    @Test
    fun `a brawler cannot be in both teams`() {
        val context = DraftTextParser.parse(
            """
            فريقك
            Poco
            الخصم
            Poco
            """.trimIndent()
        ).context
        assertEquals(listOf("Poco"), context.allies)
        assertTrue(context.enemies.isEmpty())
    }

    @Test
    fun `explicit pick slot is read only when present`() {
        val withSlot = DraftTextParser.parse("pick 2\nالخريطة: Safe Zone").context
        assertEquals(2, withSlot.pickSlot)
        val without = DraftTextParser.parse("الخريطة: Safe Zone").context
        assertEquals(null, without.pickSlot)
    }

    @Test
    fun `missing fields are reported for the ui`() {
        val context = DraftTextParser.parse("الخريطة: Safe Zone").context
        val missing = context.missingFields()
        assertTrue(missing.contains("نمط اللعب").not())
        assertTrue(missing.contains("اختيارات الخصم"))
        assertTrue(missing.contains("دور الاختيار"))
    }
}
