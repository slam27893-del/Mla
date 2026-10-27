package com.myai.assistant.draft

/** Structural features of a map that the rules care about. */
enum class MapTag {
    OPEN,
    WALLED,
    BUSHES,
    WATER,
    TIGHT,
    THROWER_FRIENDLY,
    CENTER_OBJECTIVE
}

/**
 * A known map. [mode] is the competitive mode the map belongs to; when the same
 * name appears in two modes the entry lists the primary one and the parser keeps
 * the mode the user or the vision model provided.
 */
data class MapInfo(
    val name: String,
    val mode: GameMode,
    val tags: Set<MapTag>,
    val aliases: Set<String> = emptySet()
)

/**
 * Curated map list (competitive rotation). Rotations change every season, which
 * is why an unknown map is *not* an error: the engine falls back to mode-only
 * advice and lowers its confidence.
 */
object MapCatalog {

    private fun m(
        name: String,
        mode: GameMode,
        tags: Set<MapTag>,
        vararg aliases: String
    ) = MapInfo(name, mode, tags, aliases.toSet())

    val maps: List<MapInfo> = listOf(
        // Gem Grab
        m("Hard Rock Mine", GameMode.GEM_GRAB, setOf(MapTag.WALLED, MapTag.TIGHT, MapTag.CENTER_OBJECTIVE)),
        m("Crystal Arcade", GameMode.GEM_GRAB, setOf(MapTag.WALLED, MapTag.TIGHT, MapTag.CENTER_OBJECTIVE)),
        m("Deathcap Trap", GameMode.GEM_GRAB, setOf(MapTag.WALLED, MapTag.BUSHES, MapTag.CENTER_OBJECTIVE)),
        m("Double Swoosh", GameMode.GEM_GRAB, setOf(MapTag.OPEN, MapTag.CENTER_OBJECTIVE)),
        m("Gem Fort", GameMode.GEM_GRAB, setOf(MapTag.WALLED, MapTag.TIGHT, MapTag.CENTER_OBJECTIVE)),
        m("Undermine", GameMode.GEM_GRAB, setOf(MapTag.WALLED, MapTag.BUSHES)),
        m("Rustic Arcade", GameMode.GEM_GRAB, setOf(MapTag.WALLED, MapTag.THROWER_FRIENDLY, MapTag.CENTER_OBJECTIVE)),
        m("Steel Hive", GameMode.GEM_GRAB, setOf(MapTag.WALLED, MapTag.TIGHT)),
        m("Snake Prairie", GameMode.BOUNTY, setOf(MapTag.BUSHES, MapTag.OPEN), "سنيك بريري"),
        m("Acute Angle", GameMode.GEM_GRAB, setOf(MapTag.OPEN, MapTag.WALLED)),
        m("Chill Space", GameMode.GEM_GRAB, setOf(MapTag.WALLED, MapTag.TIGHT, MapTag.THROWER_FRIENDLY)),
        m("Four Levels", GameMode.GEM_GRAB, setOf(MapTag.WALLED, MapTag.THROWER_FRIENDLY)),

        // Brawl Ball
        m("Backyard Bowl", GameMode.BRAWL_BALL, setOf(MapTag.OPEN, MapTag.WALLED)),
        m("Center Stage", GameMode.BRAWL_BALL, setOf(MapTag.OPEN, MapTag.CENTER_OBJECTIVE)),
        m("Sneaky Fields", GameMode.BRAWL_BALL, setOf(MapTag.BUSHES, MapTag.WALLED)),
        m("Super Beach", GameMode.BRAWL_BALL, setOf(MapTag.OPEN, MapTag.WATER)),
        m("Penalty Kick", GameMode.BRAWL_BALL, setOf(MapTag.OPEN, MapTag.TIGHT)),
        m("Triple Dribble", GameMode.BRAWL_BALL, setOf(MapTag.WALLED, MapTag.TIGHT)),
        m("Pinball Dreams", GameMode.BRAWL_BALL, setOf(MapTag.WALLED, MapTag.THROWER_FRIENDLY)),
        m("Rooftop Run", GameMode.BRAWL_BALL, setOf(MapTag.WALLED, MapTag.TIGHT)),
        m("Beach Ball", GameMode.BRAWL_BALL, setOf(MapTag.OPEN, MapTag.WATER)),
        m("Pinhole Punt", GameMode.BRAWL_BALL, setOf(MapTag.WALLED, MapTag.TIGHT)),

        // Heist
        m("Safe Zone", GameMode.HEIST, setOf(MapTag.WALLED, MapTag.TIGHT)),
        m("Hot Potato", GameMode.HEIST, setOf(MapTag.WALLED, MapTag.THROWER_FRIENDLY)),
        m("Bridge Too Far", GameMode.HEIST, setOf(MapTag.OPEN, MapTag.WATER)),
        m("Kaboom Canyon", GameMode.HEIST, setOf(MapTag.OPEN, MapTag.WALLED)),
        m("Pit Stop", GameMode.HEIST, setOf(MapTag.WALLED, MapTag.TIGHT)),
        m("Riverside", GameMode.HEIST, setOf(MapTag.WATER, MapTag.OPEN)),
        m("Tornado Valley", GameMode.HEIST, setOf(MapTag.OPEN, MapTag.WALLED)),

        // Bounty
        m("Layer Cake", GameMode.BOUNTY, setOf(MapTag.WALLED, MapTag.THROWER_FRIENDLY)),
        m("Dry Season", GameMode.BOUNTY, setOf(MapTag.OPEN)),
        m("Hideout", GameMode.BOUNTY, setOf(MapTag.BUSHES, MapTag.WALLED)),
        m("Shooting Star", GameMode.BOUNTY, setOf(MapTag.OPEN, MapTag.BUSHES)),
        m("Canal Grande", GameMode.BOUNTY, setOf(MapTag.OPEN, MapTag.WATER)),
        m("Flaring Phoenix", GameMode.BOUNTY, setOf(MapTag.OPEN, MapTag.WALLED)),
        m("Grotto", GameMode.BOUNTY, setOf(MapTag.WALLED, MapTag.TIGHT)),

        // Knockout
        m("Belle's Rock", GameMode.KNOCKOUT, setOf(MapTag.WALLED, MapTag.TIGHT)),
        m("Goldarm Gulch", GameMode.KNOCKOUT, setOf(MapTag.OPEN, MapTag.WALLED)),
        m("Out in the Open", GameMode.KNOCKOUT, setOf(MapTag.OPEN)),
        m("New Horizons", GameMode.KNOCKOUT, setOf(MapTag.WALLED, MapTag.BUSHES)),
        m("Deep Diner", GameMode.KNOCKOUT, setOf(MapTag.WALLED, MapTag.TIGHT)),
        m("Sunny Side", GameMode.KNOCKOUT, setOf(MapTag.OPEN, MapTag.BUSHES)),

        // Hot Zone
        m("Ring of Fire", GameMode.HOT_ZONE, setOf(MapTag.OPEN, MapTag.CENTER_OBJECTIVE)),
        m("Dueling Beetles", GameMode.HOT_ZONE, setOf(MapTag.WALLED, MapTag.CENTER_OBJECTIVE)),
        m("Open Business", GameMode.HOT_ZONE, setOf(MapTag.OPEN, MapTag.CENTER_OBJECTIVE)),
        m("Control Grande", GameMode.HOT_ZONE, setOf(MapTag.WALLED, MapTag.THROWER_FRIENDLY)),
        m("Sizzling Surprise", GameMode.HOT_ZONE, setOf(MapTag.WALLED, MapTag.BUSHES)),
        m("Parallel Plays", GameMode.HOT_ZONE, setOf(MapTag.OPEN, MapTag.WALLED)),

        // Duels / misc
        m("Temple Ruins", GameMode.DUELS, setOf(MapTag.WALLED, MapTag.TIGHT)),
        m("Skull Creek", GameMode.DUELS, setOf(MapTag.OPEN, MapTag.BUSHES))
    )

    private val byKey: Map<String, MapInfo> = buildMap {
        maps.forEach { map ->
            put(DraftText.normalize(map.name), map)
            put(DraftText.compact(map.name), map)
            map.aliases.forEach { alias ->
                put(DraftText.normalize(alias), map)
                put(DraftText.compact(alias), map)
            }
        }
    }

    /** Exact/normalised lookup. Returns null when the map is not in the dataset. */
    fun find(name: String?): MapInfo? {
        if (name.isNullOrBlank()) return null
        return byKey[DraftText.normalize(name)] ?: byKey[DraftText.compact(name)]
    }

    /**
     * Finds a map mentioned inside a block of text, preferring the longest match so
     * "Hard Rock Mine" wins over a hypothetical "Rock".
     */
    fun findInText(text: String): MapInfo? {
        val normalized = DraftText.normalize(text)
        if (normalized.isEmpty()) return null
        return maps
            .filter { map ->
                val keys = sequenceOf(map.name) + map.aliases.asSequence()
                keys.any { key -> DraftText.containsWord(normalized, key) }
            }
            .maxByOrNull { it.name.length }
    }
}
