package com.myai.assistant.util

/**
 * Arabic-aware text normalisation used by the offline command engine and by
 * fuzzy app-name matching. It is intentionally lossy (diacritics, hamza forms,
 * ta marbuta) so that user input such as "أفتح الواتساب" matches "افتح واتساب".
 */
object ArabicText {

    private val whitespace = Regex("\\s+")

    fun normalize(input: String): String {
        val builder = StringBuilder(input.length)
        for (ch in input) {
            when {
                ch in '\u064B'..'\u065F' -> Unit          // harakat
                ch == '\u0670' -> Unit                    // superscript alef
                ch in '\u06D6'..'\u06ED' -> Unit          // Quranic marks
                ch == '\u0640' -> Unit                    // tatweel
                ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ' -> builder.append('ا')
                ch == 'ى' -> builder.append('ي')
                ch == 'ة' -> builder.append('ه')
                ch == 'ؤ' -> builder.append('و')
                ch == 'ئ' -> builder.append('ي')
                else -> builder.append(ch)
            }
        }
        return builder.toString().lowercase().replace(whitespace, " ").trim()
    }

    /** Removes a single leading Arabic definite article so "الواتساب" == "واتساب". */
    fun stripDefiniteArticle(normalized: String): String =
        if (normalized.startsWith("ال") && normalized.length > 3) normalized.substring(2) else normalized

    fun looseEquals(a: String, b: String): Boolean =
        stripDefiniteArticle(normalize(a)) == stripDefiniteArticle(normalize(b))

    /** Keeps digits and a leading plus sign; used for phone numbers. */
    fun digitsOnly(raw: String): String {
        val trimmed = raw.trim()
        val builder = StringBuilder()
        trimmed.forEachIndexed { index, ch ->
            when {
                ch.isDigit() -> builder.append(ch)
                ch == '+' && index == 0 -> builder.append(ch)
            }
        }
        return builder.toString()
    }
}
