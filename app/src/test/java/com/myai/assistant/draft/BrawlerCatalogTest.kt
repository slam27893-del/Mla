package com.myai.assistant.draft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrawlerCatalogTest {

    @Test
    fun `finds brawlers by english name regardless of case`() {
        assertEquals("El Primo", BrawlerCatalog.find("el primo")?.name)
        assertEquals("El Primo", BrawlerCatalog.find("ELPRIMO")?.name)
    }

    @Test
    fun `finds brawlers by arabic alias and normalises hamza`() {
        assertEquals("El Primo", BrawlerCatalog.find("إل بريمو")?.name)
        assertEquals("Edgar", BrawlerCatalog.find("ادغار")?.name)
        assertEquals("Bo", BrawlerCatalog.find("بو")?.name)
    }

    @Test
    fun `handles names with digits and punctuation`() {
        assertEquals("8-Bit", BrawlerCatalog.find("8-بت")?.name)
        assertEquals("Larry & Lawrie", BrawlerCatalog.find("larry & lawrie")?.name)
        assertEquals("Mr. P", BrawlerCatalog.find("mrp")?.name)
    }

    @Test
    fun `unknown name is null and never guessed`() {
        assertNull(BrawlerCatalog.find("NotABrawler"))
        assertNull(BrawlerCatalog.find(""))
        assertNull(BrawlerCatalog.find(null))
    }

    @Test
    fun `catalogue has no duplicate canonical names`() {
        val names = BrawlerCatalog.all.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue("catalogue should be reasonably complete", names.size >= 80)
    }

    @Test
    fun `short alias does not match inside a longer word`() {
        assertTrue(BrawlerCatalog.findAllInText("bobby").isEmpty())
        assertEquals(2, BrawlerCatalog.findAllInText("فريقي بوكو و شيللي").size)
    }

    @Test
    fun `bo is matched as a whole word`() {
        assertEquals(listOf("Bo"), BrawlerCatalog.findAllInText("your team: Bo").map { it.name })
        assertEquals(listOf("Poco"), BrawlerCatalog.findAllInText("بوكو").map { it.name })
    }

    @Test
    fun `every entry has a usable role and range`() {
        BrawlerCatalog.all.forEach { brawler ->
            assertTrue(brawler.name.isNotBlank())
            assertNotNull(brawler.cls)
            assertNotNull(brawler.range)
        }
    }

    @Test
    fun `map catalogue resolves known and unknown maps`() {
        assertEquals("Rustic Arcade", MapCatalog.find("rustic arcade")?.name)
        assertEquals(GameMode.GEM_GRAB, MapCatalog.find("Rustic Arcade")?.mode)
        assertNull(MapCatalog.find("Map From Next Season"))
        assertEquals("Dry Season", MapCatalog.findInText("الخريطة: Dry Season")?.name)
    }

    @Test
    fun `mode detection understands arabic and english`() {
        assertEquals(GameMode.GEM_GRAB, GameMode.fromText("القتال على الجواهر"))
        assertEquals(GameMode.BRAWL_BALL, GameMode.fromText("brawl ball"))
        assertEquals(GameMode.HEIST, GameMode.fromText("السرقة"))
        assertEquals(GameMode.UNKNOWN, GameMode.fromText("شيء غير معروف"))
    }

    @Test
    fun `normalisation unifies arabic letter variants`() {
        assertEquals(DraftText.normalize("إدغار"), DraftText.normalize("ادغار"))
        assertEquals("ab", DraftText.normalize(" A-b "))
    }
}
