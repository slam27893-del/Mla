import com.myai.assistant.draft.BrawlerCatalog
import com.myai.assistant.draft.BrawlerClass
import com.myai.assistant.draft.DraftContext
import com.myai.assistant.draft.DraftEngine
import com.myai.assistant.draft.DraftJsonParser
import com.myai.assistant.draft.DraftPrompt
import com.myai.assistant.draft.DraftText
import com.myai.assistant.draft.DraftTextParser
import com.myai.assistant.draft.GameMode
import com.myai.assistant.draft.MapCatalog
import com.myai.assistant.draft.MiniJson
import com.myai.assistant.draft.Range
import com.myai.assistant.draft.RecognitionSource
import com.myai.assistant.draft.Trait
import com.myai.assistant.draft.asDouble
import com.myai.assistant.draft.asString
import com.myai.assistant.draft.asStringList

/**
 * Executes the REAL draft classes on a plain JVM: catalog, text parser, JSON
 * parser, engine and the outbound vision payload.
 *
 * Run with tools/local-verify/run-draft.sh. The authoritative suite is still
 * `./gradlew testDebugUnitTest`; this file exists so the logic can be checked
 * before an APK exists.
 */

var dpassed = 0
var dfailed = 0

fun dcheck(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
        dpassed++
        println("PASS  $name")
    } else {
        dfailed++
        println("FAIL  $name${if (detail.isEmpty()) "" else "  → $detail"}")
    }
}

fun main() {
    catalogLookups()
    textParser()
    jsonParser()
    engineRules()
    visionPayload()
    println()
    println("RESULT  passed=$dpassed failed=$dfailed")
    if (dfailed > 0) kotlin.system.exitProcess(1)
}

private fun catalogLookups() {
    dcheck("catalog: exact name", BrawlerCatalog.find("El Primo")?.name == "El Primo")
    dcheck("catalog: lowercase english", BrawlerCatalog.find("el primo")?.name == "El Primo")
    dcheck("catalog: arabic alias", BrawlerCatalog.find("إل بريمو")?.name == "El Primo")
    dcheck("catalog: arabic without hamza", BrawlerCatalog.find("ادغار")?.name == "Edgar")
    dcheck("catalog: numeric name with dash", BrawlerCatalog.find("8-بت")?.name == "8-Bit")
    dcheck("catalog: ampersand name", BrawlerCatalog.find("larry & lawrie")?.name == "Larry & Lawrie")
    dcheck("catalog: unknown returns null", BrawlerCatalog.find("NotABrawler") == null)
    dcheck("catalog: no duplicate names", BrawlerCatalog.all.map { it.name }.distinct().size == BrawlerCatalog.all.size)
    dcheck(
        "catalog: every entry has a sane range/class",
        BrawlerCatalog.all.all { it.name.isNotBlank() && it.cls in BrawlerClass.entries && it.range in Range.entries }
    )
    dcheck("catalog: text scan finds two names", BrawlerCatalog.findAllInText("فريقي بوكو و شيللي").size == 2)
    dcheck("catalog: 'bo' does not match inside another word", BrawlerCatalog.findAllInText("bobby").isEmpty())
    dcheck("map: lookup known map", MapCatalog.find("rustic arcade")?.name == "Rustic Arcade")
    dcheck("map: unknown map is null", MapCatalog.find("Not A Map") == null)
    dcheck("map: found inside a line", MapCatalog.findInText("الخريطة: Dry Season")?.name == "Dry Season")
    dcheck("mode: arabic keyword", GameMode.fromText("القتال على الجواهر") == GameMode.GEM_GRAB)
    dcheck("mode: english keyword", GameMode.fromText("Brawl Ball") == GameMode.BRAWL_BALL)
    dcheck("mode: unknown stays unknown", GameMode.fromText("شيء آخر") == GameMode.UNKNOWN)
}

private fun textParser() {
    val screen = """
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

    val parsed = DraftTextParser.parse(screen)
    val c = parsed.context
    dcheck("text: bans parsed", c.bans == listOf("Edgar", "Fang"), c.bans.toString())
    dcheck("text: allies parsed", c.allies == listOf("Poco", "Bull"), c.allies.toString())
    dcheck("text: enemies parsed", c.enemies == listOf("Leon", "Crow"), c.enemies.toString())
    dcheck("text: map parsed", c.map == "Hard Rock Mine", c.map.toString())
    dcheck("text: mode derived from map", c.mode == GameMode.GEM_GRAB, c.mode.name)
    dcheck("text: source recorded", c.source == RecognitionSource.SCREEN_TEXT)
    dcheck("text: usable", parsed.isUsable)

    val noHeaders = DraftTextParser.parse("Bull\nLeon\nPoco")
    dcheck("text: no headers, no invented sections", noHeaders.context.allies.isEmpty() &&
        noHeaders.context.enemies.isEmpty() && noHeaders.context.bans.isEmpty())
    dcheck("text: unmatched names reported", noHeaders.context.unresolved.size == 3,
        noHeaders.context.unresolved.toString())

    val arabic = DraftTextParser.parse("""
        فريقك
        بو
        الخصم
        إدغار
        الخريطة: Dry Season
    """.trimIndent())
    dcheck("text: arabic allies", arabic.context.allies == listOf("Bo"), arabic.context.allies.toString())
    dcheck("text: arabic enemies", arabic.context.enemies == listOf("Edgar"), arabic.context.enemies.toString())
    dcheck("text: bounty map", arabic.context.mode == GameMode.BOUNTY, arabic.context.mode.name)

    val junk = DraftTextParser.parse("")
    dcheck("text: empty input is not usable", !junk.isUsable && junk.context.missingFields().isNotEmpty())
}

private fun jsonParser() {
    val good = """
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
    val parsed = DraftJsonParser.parse(good)
    val c = parsed.context
    dcheck("json: map", c.map == "Rustic Arcade", c.map.toString())
    dcheck("json: mode", c.mode == GameMode.GEM_GRAB)
    dcheck("json: bans", c.bans == listOf("Edgar", "Fang"), c.bans.toString())
    dcheck("json: allies", c.allies == listOf("Poco"), c.allies.toString())
    dcheck("json: enemies", c.enemies == listOf("Bull", "Leon"), c.enemies.toString())
    dcheck("json: pick slot", c.pickSlot == 2, c.pickSlot.toString())
    dcheck("json: confidence", parsed.modelConfidence == 0.72, parsed.modelConfidence.toString())
    dcheck("json: no parse error", parsed.parseError == null)

    val unknownName = """{"enemies":["Bull","SomeFakeBrawler"],"map":"Dry Season"}"""
    val u = DraftJsonParser.parse(unknownName)
    dcheck("json: unknown name dropped from enemies", u.context.enemies == listOf("Bull"), u.context.enemies.toString())
    dcheck("json: unknown name surfaced", u.context.unresolved.contains("SomeFakeBrawler"), u.context.unresolved.toString())

    val blocked = """{"map":"Dry Season","mode":"BOUNTY","unreadable":["map"]}"""
    val b = DraftJsonParser.parse(blocked)
    dcheck("json: unreadable field is not trusted", b.context.map == null, b.context.map.toString())

    val garbage = DraftJsonParser.parse("عذرًا، لا أستطيع قراءة الصورة")
    dcheck("json: garbage reported", garbage.parseError != null)
    dcheck("json: garbage yields empty context", garbage.context.knownFieldCount == 0)

    val trailing = """نص قبل {"enemies":["Crow"],} نص بعد"""
    val t = DraftJsonParser.parse(trailing)
    dcheck("json: tolerates prose and trailing comma", t.context.enemies == listOf("Crow"), t.context.enemies.toString())

    val percent = """{"mode":"KNOCKOUT","confidence":80}"""
    dcheck("json: percent confidence", DraftJsonParser.parse(percent).modelConfidence == 0.8)

    dcheck("minijson: parses nested", MiniJson.parseObject("""{"a":{"b":["x","y"]}}""")
        ?.let { it["a"] }?.let { (it as com.myai.assistant.draft.J.Obj)["b"] }
        ?.let { it.asStringList() } == listOf("x", "y"))
    dcheck("minijson: string field", MiniJson.parseObject("""{"map":"Dry Season"}""")?.let { it["map"]?.asString() } == "Dry Season")
    dcheck("minijson: number field", MiniJson.parseObject("""{"confidence":0.5}""")?.let { it["confidence"]?.asDouble() } == 0.5)
    dcheck("drafttext: normalize unifies arabic", DraftText.normalize("إدغار") == DraftText.normalize("ادغار"))
}

private fun engineRules() {
    // 1. Enemy all tanks + an anti-tank specialist available -> anti-tank must win.
    run {
        val ctx = DraftContext(
            map = "Hard Rock Mine",
            mode = GameMode.GEM_GRAB,
            allies = listOf("Poco"),
            enemies = listOf("Bull", "Frank", "El Primo"),
            bans = listOf("Colette", "Griff")
        )
        val result = DraftEngine.analyze(ctx)
        val top = result.recommendations.first()
        dcheck("engine: three recommendations", result.recommendations.size == 3)
        dcheck("engine: anti-tank against triple tank", Trait.ANTI_TANK in top.brawler.traits, top.brawler.name)
        dcheck("engine: top excluded from bans", top.brawler.name !in listOf("Colette", "Griff"))
        dcheck("engine: reasons present", result.recommendations.all { it.reasons.isNotEmpty() })
        dcheck("engine: confidence high with full info", result.confidence >= 70, result.confidence.toString())
        dcheck("engine: picks are distinct", result.recommendations.map { it.brawler.name }.distinct().size == 3)
    }

    // 2. Open map + Bounty -> long range wins.
    run {
        val ctx = DraftContext(map = "Dry Season", mode = GameMode.BOUNTY)
        val result = DraftEngine.analyze(ctx)
        dcheck("engine: marksman on open bounty map", result.recommendations.first().brawler.range == Range.LONG,
            result.recommendations.first().brawler.name)
        dcheck("engine: unknown enemies lowers confidence", result.confidence in 60..85, result.confidence.toString())
    }

    // 3. Brawl Ball tight map -> no artillery/sniper at the top.
    run {
        val ctx = DraftContext(map = "Pinhole Punt", mode = GameMode.BRAWL_BALL, enemies = listOf("Leon"))
        val top = DraftEngine.analyze(ctx).recommendations.first().brawler
        dcheck("engine: brawl ball prefers frontline", top.cls in setOf(BrawlerClass.TANK, BrawlerClass.SUPPORT, BrawlerClass.DAMAGE),
            "${top.name}/${top.cls}")
    }

    // 4. Unknown everything -> still advises, but with low confidence and notes.
    run {
        val ctx = DraftContext()
        val result = DraftEngine.analyze(ctx)
        dcheck("engine: unknown input still returns three", result.recommendations.size == 3)
        dcheck("engine: unknown input confidence low", result.confidence <= 45, result.confidence.toString())
        dcheck("engine: notes explain missing map", result.notes.any { it.contains("نمط") || it.contains("خريطة") } ||
            result.context.missingFields().contains("الخريطة"))
    }

    // 5. Pick order changes the score of the same brawler.
    run {
        val base = DraftContext(map = "Rustic Arcade", mode = GameMode.GEM_GRAB)
        val first = DraftEngine.score(BrawlerCatalog.find("Barley")!!, base.copy(pickSlot = 1), MapCatalog.find("Rustic Arcade"))
        val last = DraftEngine.score(BrawlerCatalog.find("Barley")!!, base.copy(pickSlot = 3), MapCatalog.find("Rustic Arcade"))
        dcheck("engine: slot penalises specialist first pick", first.total < last.total, "${first.total} vs ${last.total}")
    }

    // 6. Already-picked allies are not offered again.
    run {
        val ctx = DraftContext(mode = GameMode.HEIST, allies = listOf("Colt"), enemies = listOf("Bull"))
        val names = DraftEngine.analyze(ctx).recommendations.map { it.brawler.name }
        dcheck("engine: ally pick excluded", "Colt" !in names, names.toString())
        dcheck("engine: enemy pick excluded", "Bull" !in names, names.toString())
    }

    // 7. Determinism.
    run {
        val ctx = DraftContext(map = "Safe Zone", mode = GameMode.HEIST, enemies = listOf("Crow"), bans = listOf("Colt"))
        val a = DraftEngine.analyze(ctx).recommendations.map { it.brawler.name }
        val b = DraftEngine.analyze(ctx).recommendations.map { it.brawler.name }
        dcheck("engine: deterministic", a == b, "$a vs $b")
    }

    // 8. Bans apply to both teams: a banned brawler never appears.
    run {
        val ctx = DraftContext(mode = GameMode.GEM_GRAB, bans = listOf("Shelly", "Bull", "Poco", "Leon"))
        val names = DraftEngine.analyze(ctx).recommendations.map { it.brawler.name }
        dcheck("engine: bans respected", names.none { it in listOf("Shelly", "Bull", "Poco", "Leon") }, names.toString())
    }

    // 9. Unknown map name is reported, not silently replaced.
    run {
        val ctx = DraftContext(map = "Map From Next Season", mode = GameMode.HOT_ZONE)
        val result = DraftEngine.analyze(ctx)
        dcheck("engine: unknown map noted", result.notes.any { it.contains("Map From Next Season") }, result.notes.toString())
        dcheck("engine: unknown map keeps mode advice", result.confidence in 25..60, result.confidence.toString())
    }
}

private fun visionPayload() {
    val payload = DraftPrompt.buildVisionPayload("vision-model", "QUJD")
    val root = MiniJson.parseObject(payload)
    dcheck("payload: parses as json", root != null)
    dcheck("payload: model set", root?.let { it["model"]?.asString() } == "vision-model")
    dcheck("payload: exactly one image part", payload.split("\"type\":\"image_url\"").size - 1 == 1, payload.take(120))
    dcheck("payload: base64 embedded once", payload.split("data:image/jpeg;base64,QUJD").size - 1 == 1)
    dcheck("payload: no history/other fields", !payload.contains("turns") && !payload.contains("history"))
    dcheck("payload: system prompt carries the schema",
        DraftPrompt.SYSTEM_PROMPT.contains("pick_slot") && DraftPrompt.SYSTEM_PROMPT.contains("unreadable"))
    dcheck("payload: prompt forbids guessing", DraftPrompt.SYSTEM_PROMPT.contains("لا تخمّن"))
}
