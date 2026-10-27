package com.myai.assistant.draft

/** Text helpers shared by the parsers. Kept dependency-free so it stays testable. */
object DraftText {

    private val PROCLITICS = setOf('و', 'ب', 'ل', 'ك', 'ف')

    /**
     * Normalises Arabic/English text for matching: lowercases, removes diacritics
     * and tatweel, unifies alef/ya/ta-marbuta variants, turns runs of whitespace
     * into a single space and drops punctuation.
     *
     * Spaces are kept on purpose: they are what makes whole-word matching possible,
     * so "bo" cannot match inside "bobby" and "Dry Season" still matches inside a
     * line like "الخريطة: Dry Season".
     */
    fun normalize(raw: String): String {
        val lowered = raw.lowercase()
        val mapped = buildString(lowered.length) {
            lowered.forEach { ch ->
                when {
                    ch in '\u0610'..'\u061A' || ch in '\u064B'..'\u065F' ||
                        ch == '\u0670' || ch in '\u06D6'..'\u06ED' || ch == '\u0640' -> Unit
                    ch == '\u0623' || ch == '\u0625' || ch == '\u0622' || ch == '\u0671' -> append('\u0627')
                    ch == '\u0629' -> append('\u0647') // ta marbuta
                    ch == '\u0649' -> append('\u064A') // alef maqsura
                    ch == '\u0624' -> append('\u0648')
                    ch == '\u0626' -> append('\u064A')
                    else -> append(ch)
                }
            }
        }
        return buildString(mapped.length) {
            var pendingSpace = false
            mapped.forEach { ch ->
                when {
                    ch.isLetterOrDigit() -> {
                        if (pendingSpace && isNotEmpty()) append(' ')
                        pendingSpace = false
                        append(ch)
                    }
                    ch.isWhitespace() -> pendingSpace = true
                    else -> Unit
                }
            }
        }
    }

    /** Same text with every space removed, for users who type "elprimo". */
    fun compact(text: String): String = normalize(text).replace(" ", "")

    /** Index of the first whole-word occurrence of [key] inside already-normalised [text]. */
    fun wordIndex(text: String, key: String): Int {
        if (key.isEmpty() || text.isEmpty()) return -1
        var from = 0
        while (true) {
            val at = text.indexOf(key, from)
            if (at < 0) return -1
            val end = at + key.length
            if (isEndBoundary(text, end) && isStartBoundary(text, at, key)) return at
            from = at + 1
        }
    }

    /** Index of a compact (space-less) occurrence; only for long keys, to avoid substring noise. */
    fun compactIndex(text: String, key: String): Int {
        val compactKey = key.replace(" ", "")
        if (compactKey.length < MIN_COMPACT_KEY) return -1
        return text.replace(" ", "").indexOf(compactKey)
    }

    /** True when [text] contains [alias] as a whole word. */
    fun containsWord(text: String, alias: String): Boolean {
        val key = normalize(alias)
        return wordIndex(text, key) >= 0 || compactIndex(text, key) >= 0
    }

    private fun isEndBoundary(text: String, end: Int): Boolean =
        end >= text.length || !text[end].isLetterOrDigit()

    private fun isStartBoundary(text: String, at: Int, key: String): Boolean {
        if (at == 0) return true
        if (!text[at - 1].isLetterOrDigit()) return true
        // Arabic words are often written with a short proclitic ("وفريقي", "ببوكو").
        if (!isArabic(key.first())) return false
        var i = at - 1
        var count = 0
        while (i >= 0 && text[i] in PROCLITICS && count < 2) { i--; count++ }
        return count in 1..2 && (i < 0 || !text[i].isLetterOrDigit())
    }

    private fun isArabic(ch: Char): Boolean = ch in '\u0600'..'\u06FF'

    private const val MIN_COMPACT_KEY = 6

    /** Trims quotes, bullets, colons and dashes that the game/OCR sprinkle around labels. */
    fun cleanLine(raw: String): String =
        raw.trim().trim('·', '•', '-', '–', '|', ':', '.', ',', '"', '\'', '«', '»', '*')
            .replace(Regex("\\s+"), " ")
            .trim()
}
