package com.myai.assistant.draft

/** Structured result from a vision model (or from a replayed fixture in tests). */
data class VisionDraft(
    val context: DraftContext,
    /** Model-reported confidence 0..1, when present. */
    val modelConfidence: Double?,
    val unreadable: List<String>,
    val modelNotes: List<String>,
    val parseError: String? = null
)

/**
 * Maps a vision model's JSON answer onto [DraftContext].
 *
 * Rules that keep the feature honest:
 * * Unknown brawler names are not dropped and not guessed — they surface in
 *   `unresolved` so the UI can ask the user.
 * * A missing field stays `null`/`UNKNOWN` and is listed in [DraftContext.missingFields].
 * * A field the model itself marked as unreadable is never accepted, even if the
 *   model also filled it in.
 */
object DraftJsonParser {

    private val MAP_KEYS = listOf("map", "map_name", "mapName", "arena")
    private val MODE_KEYS = listOf("mode", "game_mode", "gameMode", "gamemode")
    private val BAN_KEYS = listOf("bans", "banned", "banned_brawlers", "ban_list")
    private val ALLY_KEYS = listOf("allies", "allies_brawlers", "my_team", "team", "teammates", "my_team_brawlers")
    private val ENEMY_KEYS = listOf("enemies", "enemy_team", "enemies_brawlers", "opponents", "enemy_brawlers")
    private val PICK_KEYS = listOf("pick_slot", "pickSlot", "my_pick", "pick_order", "pick")
    private val MY_BRAWLER_KEYS = listOf("my_brawler", "selected_brawler", "locked_brawler", "my_pick_brawler")
    private val UNREADABLE_KEYS = listOf("unreadable", "uncertain", "not_visible", "unknown_fields")
    private val CONFIDENCE_KEYS = listOf("confidence", "certainty")
    private val NOTE_KEYS = listOf("notes", "note", "comment")

    fun parse(raw: String, source: RecognitionSource = RecognitionSource.VISION_MODEL): VisionDraft {
        val root = MiniJson.parseObject(raw)
            ?: return VisionDraft(
                context = DraftContext(source = source),
                modelConfidence = null,
                unreadable = emptyList(),
                modelNotes = emptyList(),
                parseError = "لم أستطع قراءة استجابة النموذج كـ JSON."
            )

        val unreadable = UNREADABLE_KEYS.firstNotNullOfOrNull { root[it]?.asStringList() }
            ?.map { DraftText.normalize(it) }
            ?: emptyList()

        fun readString(keys: List<String>): String? = keys.firstNotNullOfOrNull { root[it]?.asString() }

        fun blocked(vararg names: String): Boolean =
            unreadable.any { u -> names.any { n -> u == DraftText.normalize(n) || u.contains(DraftText.normalize(n)) } }

        val rawMap = if (blocked("map", "map_name", "arena")) null else readString(MAP_KEYS)
        val mapInfo = MapCatalog.find(rawMap)

        val rawMode = if (blocked("mode", "game_mode")) null else readString(MODE_KEYS)
        val mode = when {
            rawMode != null && GameMode.fromText(rawMode) != GameMode.UNKNOWN -> GameMode.fromText(rawMode)
            mapInfo != null -> mapInfo.mode
            else -> GameMode.UNKNOWN
        }

        val unresolved = mutableListOf<String>()
        fun brawlers(blockedKey: String, keys: List<String>): List<String> {
            if (blocked(blockedKey)) return emptyList()
            val rawNames = keys.firstNotNullOfOrNull { root[it]?.asStringList() } ?: return emptyList()
            return rawNames.mapNotNull { raw ->
                val found = BrawlerCatalog.find(raw)
                if (found == null) {
                    unresolved += raw
                    null
                } else {
                    found.name
                }
            }.distinct()
        }

        val bans = brawlers("bans", BAN_KEYS)
        val allies = brawlers("allies", ALLY_KEYS) - bans.toSet()
        val enemies = brawlers("enemies", ENEMY_KEYS) - bans.toSet() - allies.toSet()

        val pickSlot = if (blocked("pick_slot", "pick_order", "pick")) null else {
            PICK_KEYS.firstNotNullOfOrNull { root[it]?.asInt() }?.takeIf { it in 1..3 }
                ?: PICK_KEYS.firstNotNullOfOrNull { root[it]?.asString() }
                    ?.let { ordinal(it) }
        }

        val myBrawler = if (blocked("my_brawler", "selected_brawler")) null else {
            MY_BRAWLER_KEYS.firstNotNullOfOrNull { root[it]?.asString() }
                ?.let { BrawlerCatalog.find(it)?.name ?: run { unresolved += it; null } }
        }

        val confidence = CONFIDENCE_KEYS.firstNotNullOfOrNull { root[it]?.asDouble() }
            ?.let { if (it > 1.0) it / 100.0 else it }
            ?.takeIf { it in 0.0..1.0 }
        val notes = NOTE_KEYS.firstNotNullOfOrNull { root[it]?.asStringList() } ?: emptyList()

        return VisionDraft(
            context = DraftContext(
                map = mapInfo?.name ?: rawMap,
                mode = mode,
                allies = allies,
                enemies = enemies,
                bans = bans,
                pickSlot = pickSlot,
                myBrawler = myBrawler,
                source = source,
                unresolved = unresolved.distinct().take(6)
            ),
            modelConfidence = confidence,
            unreadable = unreadable,
            modelNotes = notes
        )
    }

    private fun ordinal(raw: String): Int? {
        val key = DraftText.normalize(raw)
        return when {
            key.contains("1") || key.contains("اول") || key.contains("first") -> 1
            key.contains("2") || key.contains("ثاني") || key.contains("second") -> 2
            key.contains("3") || key.contains("ثالث") || key.contains("third") || key.contains("last") -> 3
            else -> null
        }
    }
}
