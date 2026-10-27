package com.myai.assistant.draft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftEngineTest {

    private fun analyze(vararg args: Pair<String, Any?>) = DraftEngine.analyze(mutableContext(args.toList()))

    private fun mutableContext(args: List<Pair<String, Any?>>): DraftContext {
        var ctx = DraftContext()
        args.forEach { (key, value) ->
            ctx = when (key) {
                "map" -> ctx.copy(map = value as String?)
                "mode" -> ctx.copy(mode = value as GameMode)
                "allies" -> ctx.copy(allies = value as List<String>)
                "enemies" -> ctx.copy(enemies = value as List<String>)
                "bans" -> ctx.copy(bans = value as List<String>)
                "slot" -> ctx.copy(pickSlot = value as Int?)
                else -> ctx
            }
        }
        return ctx
    }

    @Test
    fun `returns three ranked recommendations with reasons`() {
        val result = analyze("map" to "Hard Rock Mine", "mode" to GameMode.GEM_GRAB)
        assertEquals(3, result.recommendations.size)
        assertTrue(result.recommendations.all { it.reasons.isNotEmpty() })
        val scores = result.recommendations.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun `anti tank is preferred against a tank heavy enemy team`() {
        val result = analyze(
            "map" to "Hard Rock Mine",
            "mode" to GameMode.GEM_GRAB,
            "allies" to listOf("Poco"),
            "enemies" to listOf("Bull", "Frank", "El Primo"),
            "bans" to listOf("Colette", "Griff")
        )
        assertTrue(Trait.ANTI_TANK in result.recommendations.first().brawler.traits)
    }

    @Test
    fun `open bounty map favours long range`() {
        val result = analyze("map" to "Dry Season", "mode" to GameMode.BOUNTY)
        assertEquals(Range.LONG, result.recommendations.first().brawler.range)
    }

    @Test
    fun `close quarters brawl ball avoids artillery at the top`() {
        val result = analyze("map" to "Pinhole Punt", "mode" to GameMode.BRAWL_BALL, "enemies" to listOf("Leon"))
        assertFalse(result.recommendations.first().brawler.cls == BrawlerClass.ARTILLERY)
    }

    @Test
    fun `banned brawlers are never recommended`() {
        val bans = listOf("Shelly", "Bull", "Poco", "Leon")
        val names = analyze("mode" to GameMode.GEM_GRAB, "bans" to bans).recommendations.map { it.brawler.name }
        assertTrue(names.none { it in bans })
    }

    @Test
    fun `brawlers already picked are excluded`() {
        val names = analyze(
            "mode" to GameMode.HEIST,
            "allies" to listOf("Colt"),
            "enemies" to listOf("Bull")
        ).recommendations.map { it.brawler.name }
        assertFalse(names.contains("Colt"))
        assertFalse(names.contains("Bull"))
    }

    @Test
    fun `unknown input still answers but with low confidence and notes`() {
        val result = analyze()
        assertEquals(3, result.recommendations.size)
        assertTrue(result.confidence <= 45)
        assertTrue(result.notes.isNotEmpty() || result.context.missingFields().isNotEmpty())
    }

    @Test
    fun `confidence grows with the amount of known information`() {
        val empty = analyze()
        val full = analyze(
            "map" to "Rustic Arcade",
            "mode" to GameMode.GEM_GRAB,
            "allies" to listOf("Poco"),
            "enemies" to listOf("Bull", "Leon"),
            "bans" to listOf("Edgar"),
            "slot" to 2
        )
        assertTrue(full.confidence > empty.confidence)
        assertTrue(full.confidence in 1..95)
    }

    @Test
    fun `an unknown map is reported instead of faked`() {
        val result = analyze("map" to "Map From Next Season", "mode" to GameMode.HOT_ZONE)
        assertTrue(result.notes.any { it.contains("Map From Next Season") })
        assertEquals(null, result.mapInfo)
    }

    @Test
    fun `pick order changes the ranking of specialists`() {
        val map = "Rustic Arcade"
        val mapInfo = MapCatalog.find(map)
        val barley = BrawlerCatalog.find("Barley")!!
        val first = DraftEngine.score(barley, DraftContext(map = map, mode = GameMode.GEM_GRAB, pickSlot = 1), mapInfo)
        val last = DraftEngine.score(barley, DraftContext(map = map, mode = GameMode.GEM_GRAB, pickSlot = 3), mapInfo)
        assertTrue(first.total < last.total)
    }

    @Test
    fun `analysis is deterministic`() {
        val ctx = DraftContext(map = "Safe Zone", mode = GameMode.HEIST, enemies = listOf("Crow"), bans = listOf("Colt"))
        assertEquals(
            DraftEngine.analyze(ctx).recommendations.map { it.brawler.name },
            DraftEngine.analyze(ctx).recommendations.map { it.brawler.name }
        )
    }

    @Test
    fun `every known brawler produces a finite score`() {
        val ctx = DraftContext(map = "Goldarm Gulch", mode = GameMode.KNOCKOUT, enemies = listOf("Edgar"))
        BrawlerCatalog.all.forEach { brawler ->
            val score = DraftEngine.score(brawler, ctx, MapCatalog.find("Goldarm Gulch")).total
            assertTrue("score out of range for ${brawler.name}", score in 1..200)
        }
    }
}
