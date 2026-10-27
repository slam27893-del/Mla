package com.myai.assistant.draft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftJsonParserTest {

    private val answer = """
        ```json
        {
          "map": "Rustic Arcade",
          "mode": "GEM_GRAB",
          "bans": ["Edgar", "Fang"],
          "allies": ["Poco"],
          "enemies": ["Bull", "Leon"],
          "pick_slot": 2,
          "my_brawler": null,
          "confidence": 0.72,
          "unreadable": [],
          "notes": ["تمت القراءة بوضوح"]
        }
        ```
    """.trimIndent()

    @Test
    fun `parses a complete answer`() {
        val parsed = DraftJsonParser.parse(answer)
        val context = parsed.context
        assertEquals("Rustic Arcade", context.map)
        assertEquals(GameMode.GEM_GRAB, context.mode)
        assertEquals(listOf("Edgar", "Fang"), context.bans)
        assertEquals(listOf("Poco"), context.allies)
        assertEquals(listOf("Bull", "Leon"), context.enemies)
        assertEquals(2, context.pickSlot)
        assertEquals(0.72, parsed.modelConfidence!!, 0.0001)
        assertNull(parsed.parseError)
        assertEquals(RecognitionSource.VISION_MODEL, context.source)
    }

    @Test
    fun `unknown brawler names are surfaced not silently dropped`() {
        val parsed = DraftJsonParser.parse("""{"enemies":["Bull","SomeFakeBrawler"],"map":"Dry Season"}""")
        assertEquals(listOf("Bull"), parsed.context.enemies)
        assertTrue(parsed.context.unresolved.contains("SomeFakeBrawler"))
    }

    @Test
    fun `a field the model marked unreadable is not trusted`() {
        val parsed = DraftJsonParser.parse("""{"map":"Dry Season","mode":"BOUNTY","unreadable":["map"]}""")
        assertNull(parsed.context.map)
        assertEquals(GameMode.BOUNTY, parsed.context.mode)
    }

    @Test
    fun `mode is derived from the map when the model omits it`() {
        val parsed = DraftJsonParser.parse("""{"map":"Safe Zone"}""")
        assertEquals(GameMode.HEIST, parsed.context.mode)
    }

    @Test
    fun `garbage input reports an error instead of inventing data`() {
        val parsed = DraftJsonParser.parse("عذرًا، لا أستطيع قراءة الصورة")
        assertNotNull(parsed.parseError)
        assertEquals(0, parsed.context.knownFieldCount)
    }

    @Test
    fun `tolerates code fences prose and trailing commas`() {
        val parsed = DraftJsonParser.parse("""نص قبل {"enemies":["Crow"],} نص بعد""")
        assertEquals(listOf("Crow"), parsed.context.enemies)
    }

    @Test
    fun `confidence given as percent is normalised`() {
        assertEquals(0.8, DraftJsonParser.parse("""{"mode":"KNOCKOUT","confidence":80}""").modelConfidence!!, 0.0001)
    }

    @Test
    fun `pick slot given as an ordinal word is understood`() {
        assertEquals(1, DraftJsonParser.parse("""{"pick_slot":"أول اختيار"}""").context.pickSlot)
        assertEquals(3, DraftJsonParser.parse("""{"pick_slot":"last"}""").context.pickSlot)
    }

    @Test
    fun `mini json handles nesting and escapes`() {
        val root = MiniJson.parseObject("""{"a":{"b":["x","y"]},"quote":"سطر\nجديد"}""")
        assertNotNull(root)
        val nested = (root!!["a"] as J.Obj)["b"]
        assertEquals(listOf("x", "y"), nested.asStringList())
        assertEquals("سطر\nجديد", root["quote"]?.asString())
    }
}
