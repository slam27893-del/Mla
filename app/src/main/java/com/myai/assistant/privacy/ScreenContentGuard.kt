package com.myai.assistant.privacy

import java.util.Locale

/**
 * Remembers text that came from an Accessibility screen read and makes sure it
 * can never leave the device inside an AI request.
 *
 * Why a registry instead of a boolean flag: screen results are *also* copied into
 * the local transcript (the user asked to keep them there), and a later turn can
 * quote them in free text — an error message, a summary, a pasted line. Matching
 * the actual fragments catches every path that builds an outbound string, while a
 * boolean only protects the one path that happens to check the flag.
 *
 * Only *distinctive* fragments are registered (long lines, or lines containing
 * digits). Short generic labels such as "إرسال" stay unregistered so ordinary
 * follow-up questions are not mangled by redaction.
 */
class ScreenContentGuard {

    private val fragments = LinkedHashSet<String>()

    /**
     * Registers every distinctive line, every distinctive token inside it, and the
     * whole readout.
     *
     * Tokens matter: a later message may quote *part* of a line ("الرقم
     * 4521.00" out of a longer row), and matching only whole lines would let that
     * shorter quote through. A token is registered only when it is long enough to
     * be identifying (see [isDistinctiveToken]), so ordinary words stay ignored.
     */
    fun registerScreenRead(raw: String) {
        if (raw.isBlank()) return
        val lines = raw.split('\n', '\r')
            .map(::normalize)
            .filter(::isDistinctive)
        val tokens = normalize(raw).split(' ').filter(::isDistinctiveToken)
        val whole = normalize(raw)
        synchronized(lock) {
            lines.forEach { fragments.add(it) }
            tokens.forEach { fragments.add(it) }
            if (whole.length >= MIN_WHOLE_LENGTH && isDistinctive(whole)) fragments.add(whole)
            while (fragments.size > MAX_FRAGMENTS) {
                val eldest = fragments.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
        }
    }

    /** True when [text] repeats anything read from the screen. */
    fun containsProtected(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = normalize(text)
        return synchronized(lock) { fragments.any { normalized.contains(it) } }
    }

    /**
     * Returns [text] unchanged when it is clean (the common case), otherwise a
     * copy where every protected fragment is replaced by [REDACTED].
     *
     * The returned value is normalized: it is only ever produced for content that
     * is already being scrubbed, and a normalized prompt is preferable to leaking
     * the original.
     */
    fun redact(text: String): String {
        if (text.isBlank()) return text
        val normalized = normalize(text)
        val matches = synchronized(lock) {
            fragments.filter { normalized.contains(it) }.sortedByDescending { it.length }
        }
        if (matches.isEmpty()) return text
        var scrubbed = normalized
        matches.forEach { scrubbed = scrubbed.replace(it, REDACTED) }
        return scrubbed
    }

    fun protectedFragmentCount(): Int = synchronized(lock) { fragments.size }

    fun clear() {
        synchronized(lock) { fragments.clear() }
    }

    private fun isDistinctive(fragment: String): Boolean =
        fragment.length >= LONG_FRAGMENT ||
            (fragment.length >= SHORT_FRAGMENT && fragment.any(Char::isDigit))

    /**
     * A single word from the screen: identifying when it carries a digit with some
     * length (ids, amounts, codes) or is simply long (package names, addresses).
     */
    private fun isDistinctiveToken(token: String): Boolean =
        token.length >= TOKEN_WITH_DIGIT && token.any(Char::isDigit) || token.length >= LONG_TOKEN

    companion object {
        const val REDACTED = "«محتوى الشاشة — محجوب عن الخادم»"

        private const val LONG_FRAGMENT = 12
        private const val SHORT_FRAGMENT = 6
        private const val MIN_WHOLE_LENGTH = 24
        private const val TOKEN_WITH_DIGIT = 8
        private const val LONG_TOKEN = 16
        private const val MAX_FRAGMENTS = 400

        private val lock = Any()

        private val DIACRITICS = Regex("[\\u064B-\\u0652\\u0640]")

        /** Lowercases and collapses whitespace so matching survives formatting. */
        fun normalize(text: String): String =
            DIACRITICS.replace(text.lowercase(Locale.ROOT), "")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
