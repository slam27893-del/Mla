package com.myai.assistant.draft

/** Result of turning on-screen text into a draft context. */
data class TextParseResult(
    val context: DraftContext,
    /** How many lines were actually examined. */
    val lines: Int,
    /** True when at least one brawler name was matched. */
    val matchedAnything: Boolean
) {
    val isUsable: Boolean
        get() = context.map != null || context.mode != GameMode.UNKNOWN ||
            context.allies.isNotEmpty() || context.enemies.isNotEmpty() || context.bans.isNotEmpty()
}

/**
 * Turns the text of the draft screen into a [DraftContext].
 *
 * This is a **text** parser, not vision: it only works when the labels are
 * available as text (the accessibility service can see them on some devices).
 * It never guesses a section: a name is only assigned to bans/allies/enemies when
 * the screen shows a matching header, otherwise it is reported back as
 * [DraftContext.unresolved] for the user to place manually.
 */
object DraftTextParser {

    private val BAN_HEADERS = listOf("محظور", "محظورة", "حظر", "banned", "ban", "bans")
    private val ALLY_HEADERS = listOf("فريقي", "فريقك", "فريقنا", "اختيارك", "الزرقاء", "blue team", "your team", "allies", "ally")
    private val ENEMY_HEADERS = listOf("الخصم", "خصمك", "الخصوم", "الحمراء", "red team", "enemy team", "enemies", "enemy", "opponent")
    private val PICK_PATTERNS = listOf(
        Regex("(?:pick|اختيار|دور)\\D{0,6}([123])"),
        Regex("([123])\\D{0,6}(?:pick|اختيار)")
    )

    fun parse(rawText: String, source: RecognitionSource = RecognitionSource.SCREEN_TEXT): TextParseResult {
        val lines = rawText.split('\n', '\r')
            .map { DraftText.cleanLine(it) }
            .filter { it.isNotEmpty() }

        val bans = mutableListOf<String>()
        val allies = mutableListOf<String>()
        val enemies = mutableListOf<String>()
        val unresolved = mutableListOf<String>()

        var section = Section.NONE
        var exitOnDuplicate = false
        lines.forEach { line ->
            val key = DraftText.normalize(line)
            val header = headerOf(key)
            if (header != null) {
                // A repeated header means the previous block ended; later names move on.
                exitOnDuplicate = section != Section.NONE && section != header
                section = header
                return@forEach
            }
            if (key in IGNORED) return@forEach

            val names = BrawlerCatalog.findAllInText(line).map { it.name }
            if (names.isEmpty()) {
                if (section != Section.NONE && looksLikeName(line)) unresolved += line
                return@forEach
            }
            names.forEach { name ->
                when (section) {
                    Section.BAN -> if (name !in bans) bans += name
                    // A brawler cannot play for both teams: if the screen shows the
                    // same name twice, keep the first assignment instead of
                    // duplicating it into the opposite list.
                    Section.ALLY -> if (name !in allies && name !in bans && name !in enemies) allies += name
                    Section.ENEMY -> if (name !in enemies && name !in bans && name !in allies) enemies += name
                    Section.NONE -> if (name !in unresolved) unresolved += name
                }
            }
        }

        val map = MapCatalog.findInText(rawText)?.name
        val mode = when {
            map != null -> MapCatalog.find(map)?.mode ?: GameMode.UNKNOWN
            else -> GameMode.fromText(rawText)
        }
        val pickSlot = PICK_PATTERNS.firstNotNullOfOrNull { regex ->
            regex.find(DraftText.normalize(rawText).replace(" ", ""))?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in 1..3 }
        }

        val context = DraftContext(
            map = map,
            mode = mode,
            allies = allies.distinct(),
            enemies = enemies.distinct(),
            bans = bans.distinct(),
            pickSlot = pickSlot,
            source = source,
            unresolved = unresolved.distinct().take(6)
        )
        val matched = map != null || allies.isNotEmpty() || enemies.isNotEmpty() || bans.isNotEmpty()
        return TextParseResult(context, lines.size, matched)
    }

    private fun headerOf(key: String): Section? = when {
        BAN_HEADERS.any { key.contains(DraftText.normalize(it)) } -> Section.BAN
        ENEMY_HEADERS.any { key.contains(DraftText.normalize(it)) } -> Section.ENEMY
        ALLY_HEADERS.any { key.contains(DraftText.normalize(it)) } -> Section.ALLY
        else -> null
    }

    private fun looksLikeName(line: String): Boolean {
        if (line.length !in 3..24) return false
        if (line.all { it.isDigit() || it.isWhitespace() }) return false
        return line.any { it.isLetter() }
    }

    private enum class Section { NONE, BAN, ALLY, ENEMY }

    private val IGNORED = setOf(
        DraftText.normalize("اختر"), DraftText.normalize("اختر بطلًا"),
        DraftText.normalize("الوقت"), DraftText.normalize("ban"), DraftText.normalize("pick"),
        DraftText.normalize("جاهز"), DraftText.normalize("تخطي")
    )
}
