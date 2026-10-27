package com.myai.assistant.draft

/**
 * Core data model for the Draft assistant.
 *
 * Everything in this package is plain Kotlin with no Android imports: the
 * recognition parsers and the recommendation engine are pure functions so they
 * can be unit-tested on a JVM and reviewed without a device.
 *
 * Honesty rule: a field is `null`/`UNKNOWN` when it was not actually read. The
 * engine never fills a guess into [DraftContext]; guesses belong in the UI as
 * suggestions the user must confirm.
 */

/** Game mode of the match being drafted. */
enum class GameMode(val labelAr: String) {
    GEM_GRAB("القتال على الجواهر"),
    BRAWL_BALL("كرة القدم"),
    HEIST("السرقة"),
    BOUNTY("المكافأة"),
    KNOCKOUT("الإقصاء"),
    HOT_ZONE("المنطقة الساخنة"),
    DUELS("المبارزات"),
    UNKNOWN("غير معروف");

    companion object {
        /** Accepts English identifiers and the Arabic names used inside the game. */
        fun fromText(raw: String): GameMode {
            val t = DraftText.normalize(raw)
            if (t.isBlank()) return UNKNOWN
            KEYWORDS.firstOrNull { (keywords, _) ->
                keywords.any { t.contains(DraftText.normalize(it)) }
            }?.let { (_, mode) -> return mode }
            return UNKNOWN
        }

        private val KEYWORDS: List<Pair<List<String>, GameMode>> = listOf(
            listOf("gem grab", "gemgrab", "gems", "جواهر", "الجواهر") to GEM_GRAB,
            listOf("brawl ball", "brawlball", "football", "soccer", "كرة القدم", "كره القدم", "كرة") to BRAWL_BALL,
            listOf("heist", "safe", "السرقة", "سرقة", "الخزنة") to HEIST,
            listOf("bounty", "stars", "المكافأة", "مكافأة", "النجوم") to BOUNTY,
            listOf("knockout", "knock out", "الإقصاء", "اقصاء", "إقصاء") to KNOCKOUT,
            listOf("hot zone", "hotzone", "zones", "المنطقة الساخنة", "منطقة ساخنة", "المناطق") to HOT_ZONE,
            listOf("duels", "duel", "المبارزات", "مبارزة") to DUELS
        )
    }
}

/** Broad role of a brawler. Used by the recommendation rules. */
enum class BrawlerClass(val labelAr: String) {
    TANK("دبابة"),
    ASSASSIN("قاتل تسلّل"),
    DAMAGE("مُدمّر"),
    MARKSMAN("قنّاص"),
    ARTILLERY("قاذف"),
    CONTROLLER("متحكّم"),
    SUPPORT("دعم")
}

/** Effective range band. */
enum class Range(val labelAr: String) {
    SHORT("قصير"),
    MID("متوسط"),
    LONG("بعيد")
}

/** Behavioural traits that the counter rules care about. */
enum class Trait {
    WALL_BREAK,
    MOBILITY,
    INVISIBLE,
    SUMMON,
    HEAL,
    AREA,
    ANTI_TANK,
    BUSH_CONTROL,
    HIGH_SUSTAIN,
    LONG_RANGE,
    THROW,
    PULL
}

/** A single brawler entry in the curated dataset. */
data class Brawler(
    val name: String,
    val cls: BrawlerClass,
    val range: Range,
    val traits: Set<Trait> = emptySet(),
    /** Extra spellings seen in the game UI or typed by users. */
    val aliases: Set<String> = emptySet(),
    /** True for brawlers that are broadly safe as a first pick. */
    val generalist: Boolean = false
) {
    val hasAntiTank: Boolean get() = Trait.ANTI_TANK in traits
}

/** Where the information in [DraftContext] came from. */
enum class RecognitionSource {
    /** Nothing was read; every field is the user's own input. */
    MANUAL,
    /** Read from the on-screen text via the app's accessibility service (no image). */
    SCREEN_TEXT,
    /** Read from a screenshot by a vision model the user configured, with consent. */
    VISION_MODEL
}

/**
 * What is known about the draft.
 *
 * `null` means "not known" — never "guessed". [unresolved] keeps names that were
 * seen but could not be matched to the dataset, so the UI can ask instead of
 * inventing a brawler.
 */
data class DraftContext(
    val map: String? = null,
    val mode: GameMode = GameMode.UNKNOWN,
    val allies: List<String> = emptyList(),
    val enemies: List<String> = emptyList(),
    val bans: List<String> = emptyList(),
    /** 1 = first pick of your team, 3 = last pick. Null when not readable. */
    val pickSlot: Int? = null,
    /** Your own locked brawler, when the match already knows it. */
    val myBrawler: String? = null,
    val source: RecognitionSource = RecognitionSource.MANUAL,
    val unresolved: List<String> = emptyList()
) {
    val knownFieldCount: Int
        get() = listOfNotNull(
            map,
            mode.takeIf { it != GameMode.UNKNOWN },
            pickSlot,
            myBrawler,
            enemies.takeIf { it.isNotEmpty() },
            allies.takeIf { it.isNotEmpty() },
            bans.takeIf { it.isNotEmpty() }
        ).size

    /** Human readable list of what is still missing, for the "what I could not read" line. */
    fun missingFields(): List<String> = buildList {
        if (map.isNullOrBlank()) add("الخريطة")
        if (mode == GameMode.UNKNOWN) add("نمط اللعب")
        if (allies.isEmpty()) add("اختيارات فريقك")
        if (enemies.isEmpty()) add("اختيارات الخصم")
        if (pickSlot == null) add("دور الاختيار")
    }
}

/** One suggestion produced by the engine. */
data class Recommendation(
    val brawler: Brawler,
    /** 0..99, relative strength for this draft. */
    val score: Int,
    val reasons: List<String>,
    val caveats: List<String> = emptyList()
)

/** Result of one analysis. */
data class DraftAnalysis(
    val context: DraftContext,
    val mapInfo: MapInfo?,
    val recommendations: List<Recommendation>,
    /** 0..100, confidence in the *analysis*, not in a single brawler. */
    val confidence: Int,
    val notes: List<String> = emptyList()
) {
    val hasRecommendations: Boolean get() = recommendations.isNotEmpty()

    companion object {
        fun empty(context: DraftContext, note: String) = DraftAnalysis(
            context = context,
            mapInfo = MapCatalog.find(context.map),
            recommendations = emptyList(),
            confidence = 0,
            notes = listOf(note)
        )
    }
}
