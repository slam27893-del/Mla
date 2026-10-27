package com.myai.assistant.draft

/**
 * Curated brawler dataset used by the draft rules.
 *
 * This is **not** a scraper of live balance data and **not** a vision model: it is
 * a hand-written, versioned table of roles, ranges and traits (see
 * [DraftEngine] for how the numbers are used). Balance patches move brawlers
 * between tiers, so the table is intentionally coarse — class, range and a few
 * traits — instead of fragile per-brawler power numbers that would silently rot.
 *
 * Update policy: when a patch meaningfully changes a brawler's class or range,
 * edit that row and bump the note in docs/DRAFT.md.
 */
object BrawlerCatalog {

    private fun b(
        name: String,
        cls: BrawlerClass,
        range: Range,
        traits: Set<Trait> = emptySet(),
        vararg aliases: String
    ) = Brawler(name, cls, range, traits, aliases.toSet(), generalist = false)

    /** Same as [b], but flagged as a safe first pick. */
    private fun g(
        name: String,
        cls: BrawlerClass,
        range: Range,
        traits: Set<Trait> = emptySet(),
        vararg aliases: String
    ) = Brawler(name, cls, range, traits, aliases.toSet(), generalist = true)

    private val A = Trait.AREA
    private val MOB = Trait.MOBILITY
    private val HEAL = Trait.HEAL

    val brawlers: List<Brawler> = listOf(
        // ---- Tanks ----
        g("Bull", BrawlerClass.TANK, Range.SHORT, setOf(Trait.WALL_BREAK, MOB),
            "بول", "الثور"),
        b("El Primo", BrawlerClass.TANK, Range.SHORT, setOf(MOB),
            "ال بريمو", "البريمو", "بريمو", "elprim"),
        b("Frank", BrawlerClass.TANK, Range.MID, setOf(A, Trait.HIGH_SUSTAIN),
            "فرانك"),
        b("Rosa", BrawlerClass.TANK, Range.SHORT, setOf(Trait.BUSH_CONTROL, Trait.HIGH_SUSTAIN),
            "روزا"),
        b("Jacky", BrawlerClass.TANK, Range.SHORT, setOf(A),
            "جاكي"),
        b("Darryl", BrawlerClass.TANK, Range.SHORT, setOf(MOB),
            "داريل"),
        b("Bibi", BrawlerClass.TANK, Range.SHORT, setOf(MOB, A),
            "بيبي"),
        b("Ash", BrawlerClass.TANK, Range.SHORT, setOf(Trait.HIGH_SUSTAIN),
            "آش", "اش"),
        b("Sam", BrawlerClass.TANK, Range.SHORT, setOf(MOB),
            "سام"),
        b("Hank", BrawlerClass.TANK, Range.MID, setOf(A),
            "هانك"),
        b("Buster", BrawlerClass.TANK, Range.MID, setOf(Trait.HIGH_SUSTAIN),
            "باستر"),
        b("Doug", BrawlerClass.TANK, Range.SHORT, setOf(HEAL),
            "دوغ"),
        b("Nita", BrawlerClass.TANK, Range.SHORT, setOf(Trait.SUMMON, A),
            "نيتا"),
        b("Pearl", BrawlerClass.TANK, Range.MID, setOf(A),
            "بيرل"),

        // ---- Assassins ----
        b("Edgar", BrawlerClass.ASSASSIN, Range.SHORT, setOf(MOB),
            "إدغار", "ادجار", "ادغار"),
        b("Leon", BrawlerClass.ASSASSIN, Range.MID, setOf(Trait.INVISIBLE, MOB),
            "ليون"),
        b("Crow", BrawlerClass.ASSASSIN, Range.MID, setOf(MOB),
            "كرو"),
        b("Mortis", BrawlerClass.ASSASSIN, Range.SHORT, setOf(MOB),
            "مورتس", "مورتيس"),
        b("Stu", BrawlerClass.ASSASSIN, Range.MID, setOf(MOB),
            "ستو"),
        b("Fang", BrawlerClass.ASSASSIN, Range.MID, setOf(MOB),
            "فانغ", "فانج"),
        b("Buzz", BrawlerClass.ASSASSIN, Range.SHORT, setOf(MOB, Trait.HIGH_SUSTAIN),
            "باز"),
        b("Mico", BrawlerClass.ASSASSIN, Range.SHORT, setOf(MOB),
            "ميكو"),
        b("Lily", BrawlerClass.ASSASSIN, Range.SHORT, setOf(Trait.INVISIBLE, MOB),
            "ليلي"),
        b("Melodie", BrawlerClass.ASSASSIN, Range.SHORT, setOf(MOB),
            "ميلودي"),
        b("Kenji", BrawlerClass.ASSASSIN, Range.SHORT, setOf(MOB, Trait.HIGH_SUSTAIN),
            "كينجي"),
        b("Shade", BrawlerClass.ASSASSIN, Range.SHORT, setOf(MOB),
            "شيد", "شايد"),

        // ---- Damage dealers ----
        g("Shelly", BrawlerClass.DAMAGE, Range.SHORT, setOf(Trait.ANTI_TANK, A),
            "شيللي", "شيلي"),
        b("Colt", BrawlerClass.DAMAGE, Range.LONG, setOf(Trait.WALL_BREAK),
            "كولت"),
        b("Rico", BrawlerClass.DAMAGE, Range.MID, setOf(),
            "ريكو"),
        b("8-Bit", BrawlerClass.DAMAGE, Range.LONG, setOf(Trait.HIGH_SUSTAIN),
            "8bit", "8 بت", "٨ بت", "ثمانية بت"),
        b("Carl", BrawlerClass.DAMAGE, Range.MID, setOf(MOB),
            "كارل"),
        b("Amber", BrawlerClass.DAMAGE, Range.LONG, setOf(A),
            "أمبر", "امبر"),
        b("Emz", BrawlerClass.CONTROLLER, Range.MID, setOf(A),
            "إمز", "امز"),
        b("Griff", BrawlerClass.DAMAGE, Range.MID, setOf(Trait.ANTI_TANK),
            "غريف", "جريف"),
        b("Lola", BrawlerClass.DAMAGE, Range.MID, setOf(Trait.SUMMON),
            "لولا"),
        b("Surge", BrawlerClass.DAMAGE, Range.MID, setOf(MOB),
            "سيرج"),
        b("Chester", BrawlerClass.DAMAGE, Range.MID, setOf(A),
            "تشيستر"),
        b("Colette", BrawlerClass.DAMAGE, Range.MID, setOf(Trait.ANTI_TANK),
            "كوليت"),
        b("Clancy", BrawlerClass.DAMAGE, Range.MID, setOf(MOB),
            "كلانسي"),
        b("Chuck", BrawlerClass.DAMAGE, Range.MID, setOf(MOB),
            "تشاك"),
        b("Moe", BrawlerClass.DAMAGE, Range.MID, setOf(MOB, A),
            "mo"),
        b("Meeple", BrawlerClass.DAMAGE, Range.MID, setOf(),
            "ميبيل"),
        b("Lumi", BrawlerClass.DAMAGE, Range.MID, setOf(),
            "لومي"),
        b("Draco", BrawlerClass.DAMAGE, Range.SHORT, setOf(MOB, Trait.HIGH_SUSTAIN),
            "دراكو"),
        b("Spike", BrawlerClass.DAMAGE, Range.MID, setOf(A),
            "سبايك"),
        b("Jessie", BrawlerClass.DAMAGE, Range.MID, setOf(Trait.SUMMON),
            "جيسي"),
        b("Penny", BrawlerClass.DAMAGE, Range.MID, setOf(A, Trait.SUMMON),
            "بيني"),
        b("Maisie", BrawlerClass.DAMAGE, Range.MID, setOf(A),
            "ميزي", "ميسي"),

        // ---- Marksmen ----
        b("Piper", BrawlerClass.MARKSMAN, Range.LONG, setOf(),
            "بايبر"),
        b("Brock", BrawlerClass.MARKSMAN, Range.LONG, setOf(Trait.WALL_BREAK),
            "بروك"),
        b("Belle", BrawlerClass.MARKSMAN, Range.LONG, setOf(),
            "بيل"),
        b("Mandy", BrawlerClass.MARKSMAN, Range.LONG, setOf(),
            "ماندي"),
        b("Nani", BrawlerClass.MARKSMAN, Range.LONG, setOf(),
            "ناني"),
        b("Bea", BrawlerClass.MARKSMAN, Range.LONG, setOf(),
            "بيا"),
        b("Bonnie", BrawlerClass.MARKSMAN, Range.LONG, setOf(MOB),
            "بوني"),
        b("Janet", BrawlerClass.MARKSMAN, Range.LONG, setOf(MOB),
            "جانيت"),
        b("Angelo", BrawlerClass.MARKSMAN, Range.LONG, setOf(),
            "أنجيلو", "انجيلو"),
        b("Byron", BrawlerClass.MARKSMAN, Range.LONG, setOf(HEAL),
            "بايرون"),
        b("R-T", BrawlerClass.MARKSMAN, Range.LONG, setOf(),
            "ار تي", "آر تي"),
        b("Bo", BrawlerClass.CONTROLLER, Range.MID, setOf(Trait.BUSH_CONTROL),
            "بو", "bo"),

        // ---- Artillery / throwers ----
        b("Dynamike", BrawlerClass.ARTILLERY, Range.MID, setOf(Trait.THROW, Trait.WALL_BREAK),
            "ديناميك", "ديناميت"),
        b("Barley", BrawlerClass.ARTILLERY, Range.MID, setOf(Trait.THROW, A),
            "بارلي"),
        b("Tick", BrawlerClass.ARTILLERY, Range.MID, setOf(Trait.THROW),
            "تيك"),
        b("Grom", BrawlerClass.ARTILLERY, Range.LONG, setOf(Trait.THROW, A),
            "غروم", "جروم"),
        b("Sprout", BrawlerClass.ARTILLERY, Range.LONG, setOf(Trait.THROW, Trait.WALL_BREAK),
            "سبراوت"),
        b("Squeak", BrawlerClass.ARTILLERY, Range.MID, setOf(A),
            "سكيك", "سكويك"),
        b("Willow", BrawlerClass.ARTILLERY, Range.MID, setOf(Trait.THROW),
            "ويلو"),
        b("Larry & Lawrie", BrawlerClass.ARTILLERY, Range.MID, setOf(Trait.THROW, Trait.SUMMON),
            "لاري", "لاري و لوري", "larry"),
        b("Juju", BrawlerClass.ARTILLERY, Range.MID, setOf(Trait.THROW, Trait.SUMMON),
            "جوجو"),
        b("Berry", BrawlerClass.SUPPORT, Range.MID, setOf(HEAL, A),
            "بيري"),

        // ---- Controllers ----
        b("Tara", BrawlerClass.CONTROLLER, Range.MID, setOf(Trait.PULL, Trait.SUMMON),
            "تارا"),
        b("Gene", BrawlerClass.SUPPORT, Range.MID, setOf(Trait.PULL),
            "جين"),
        b("Lou", BrawlerClass.CONTROLLER, Range.MID, setOf(A),
            "lou"),
        b("Mr. P", BrawlerClass.CONTROLLER, Range.LONG, setOf(Trait.SUMMON),
            "مستر بي", "mr p", "mrp"),
        b("Gale", BrawlerClass.CONTROLLER, Range.MID, setOf(A),
            "غيل", "جيل"),
        b("Charlie", BrawlerClass.CONTROLLER, Range.MID, setOf(Trait.PULL),
            "تشارلي"),
        b("Otis", BrawlerClass.CONTROLLER, Range.MID, setOf(),
            "أوتيس", "اوتيس"),
        b("Ollie", BrawlerClass.CONTROLLER, Range.MID, setOf(A),
            "أولي", "اولي"),

        // ---- Support ----
        b("Poco", BrawlerClass.SUPPORT, Range.MID, setOf(HEAL, A),
            "بوكو"),
        b("Pam", BrawlerClass.SUPPORT, Range.MID, setOf(HEAL, Trait.HIGH_SUSTAIN),
            "بام"),
        b("Max", BrawlerClass.SUPPORT, Range.MID, setOf(MOB),
            "ماكس"),
        b("Sandy", BrawlerClass.SUPPORT, Range.MID, setOf(Trait.BUSH_CONTROL, A),
            "ساندي"),
        b("Colonel Ruffs", BrawlerClass.SUPPORT, Range.MID, setOf(),
            "ruffs", "رافس", "كولونيل رافس", "رافز"),
        b("Gray", BrawlerClass.SUPPORT, Range.MID, setOf(MOB),
            "غراي", "جراي"),
        b("Gus", BrawlerClass.SUPPORT, Range.MID, setOf(HEAL),
            "غاس", "جاس"),
        b("Kit", BrawlerClass.SUPPORT, Range.SHORT, setOf(HEAL, MOB),
            "كيت"),
        b("Finx", BrawlerClass.SUPPORT, Range.MID, setOf(),
            "فينكس")
    )

    /** De-duplicated by canonical name; keeps the first definition. */
    private val unique: List<Brawler> = brawlers.distinctBy { it.name }

    val all: List<Brawler> get() = unique

    private val byKey: Map<String, Brawler> = buildMap {
        unique.forEach { brawler ->
            put(DraftText.normalize(brawler.name), brawler)
            put(DraftText.compact(brawler.name), brawler)
            brawler.aliases.forEach { alias ->
                putIfAbsent(DraftText.normalize(alias), brawler)
                putIfAbsent(DraftText.compact(alias), brawler)
            }
        }
    }

    /** Canonical lookup by name or alias. Null when unknown — never a fuzzy guess. */
    fun find(name: String?): Brawler? {
        if (name.isNullOrBlank()) return null
        return byKey[DraftText.normalize(name)] ?: byKey[DraftText.compact(name)]
    }

    /**
     * Every brawler mentioned in [text], in order of appearance, de-duplicated.
     * Longest alias wins, so "Larry & Lawrie" is not reported as "Larry" twice.
     */
    fun findAllInText(text: String): List<Brawler> {
        val normalized = DraftText.normalize(text)
        if (normalized.isEmpty()) return emptyList()
        val hits = mutableListOf<Pair<Int, Brawler>>()
        unique.forEach { brawler ->
            val keys = sequenceOf(brawler.name) + brawler.aliases.asSequence()
            val at = keys.map { key ->
                val nk = DraftText.normalize(key)
                val spaced = DraftText.wordIndex(normalized, nk)
                if (spaced >= 0) spaced else {
                    val compactAt = DraftText.compactIndex(normalized, nk)
                    if (compactAt >= 0) 1_000_000 + compactAt else -1
                }
            }.filter { it >= 0 }.minOrNull() ?: return@forEach
            hits += at to brawler
        }
        return hits.sortedBy { it.first }.map { it.second }.distinctBy { it.name }
    }

    fun byName(name: String): Brawler? = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
