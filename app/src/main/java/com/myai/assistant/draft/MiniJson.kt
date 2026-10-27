package com.myai.assistant.draft

/**
 * Small tolerant JSON reader.
 *
 * The draft parser must survive real model output: code fences, trailing prose,
 * single quotes, a number where a string was promised. Using a hand-rolled
 * reader (instead of a strict serializer) keeps the parsing rules in one file
 * that can be unit-tested, and keeps the draft package free of dependencies so
 * it runs in the local verification harness.
 */
sealed class J {
    data class Obj(val fields: Map<String, J>) : J() {
        operator fun get(key: String): J? = fields[key]

        /** Tolerant lookup: ignores case and underscores ("pick_slot" == "pickSlot"). */
        fun soft(key: String): J? = fields[key] ?: fields.entries.firstOrNull {
            it.key.replace("_", "").equals(key.replace("_", ""), ignoreCase = true)
        }?.value
    }
    data class Arr(val items: List<J>) : J()
    data class Str(val value: String) : J()
    data class Num(val value: Double) : J()
    data class Bool(val value: Boolean) : J()
    object Null : J()
}

fun J?.asString(): String? = when (this) {
    is J.Str -> value.trim().takeIf { it.isNotEmpty() }
    is J.Num -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    is J.Bool -> value.toString()
    else -> null
}

fun J?.asDouble(): Double? = when (this) {
    is J.Num -> value
    is J.Str -> value.trim().toDoubleOrNull()
    else -> null
}

fun J?.asInt(): Int? = asDouble()?.toInt()

/** Array of strings, or a single string split on commas/Arabic comma. */
fun J?.asStringList(): List<String> {
    val element = this ?: return emptyList()
    return when (element) {
        is J.Arr -> element.items.mapNotNull { it.asString() }
        is J.Str -> element.value
            .split(',', '،', '|', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("none", true) && !it.equals("null", true) }
        else -> emptyList()
    }
}

object MiniJson {

    /** Parses the first JSON object/array found in [raw]. Returns null when there is none. */
    fun parseObject(raw: String): J.Obj? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val candidate = when {
            start >= 0 && end > start -> cleaned.substring(start, end + 1)
            else -> cleaned
        }
        val parser = Parser(candidate)
        return runCatching { parser.readObject() }.getOrNull()
    }

    private class Parser(private val text: String) {
        private var i = 0

        fun readObject(): J.Obj = read() as J.Obj

        private fun read(): J {
            skipWs()
            if (i >= text.length) fail("نهاية غير متوقعة")
            return when (val c = text[i]) {
                '{' -> objectBody()
                '[' -> arrayBody()
                '"', '\'' -> J.Str(string(c))
                't', 'f' -> bool()
                'n' -> { expect("null"); J.Null }
                else -> if (c == '-' || c.isDigit()) number() else fail("رمز غير متوقع: $c")
            }
        }

        private fun objectBody(): J.Obj {
            i++ // {
            val fields = LinkedHashMap<String, J>()
            skipWs()
            if (peek() == '}') { i++; return J.Obj(fields) }
            while (true) {
                skipWs()
                val key = when (val q = peek()) {
                    '"', '\'' -> string(q!!)
                    else -> rawKey()
                }
                skipWs()
                if (peek() == ':') i++
                skipWs()
                fields[key] = read()
                skipWs()
                when (peek()) {
                    ',' -> {
                        i++
                        skipWs()
                        if (peek() == '}') { i++; return J.Obj(fields) } // trailing comma
                        continue
                    }
                    '}' -> { i++; return J.Obj(fields) }
                    else -> return J.Obj(fields) // tolerate truncated output
                }
            }
        }

        private fun arrayBody(): J.Arr {
            i++ // [
            val items = mutableListOf<J>()
            skipWs()
            if (peek() == ']') { i++; return J.Arr(items) }
            while (true) {
                items += read()
                skipWs()
                when (peek()) {
                    ',' -> {
                        i++
                        skipWs()
                        if (peek() == ']') { i++; return J.Arr(items) } // trailing comma
                    }
                    ']' -> { i++; return J.Arr(items) }
                    else -> return J.Arr(items)
                }
            }
        }

        private fun string(quote: Char): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < text.length) {
                val c = text[i]
                when {
                    c == quote -> { i++; return sb.toString() }
                    c == '\\' && i + 1 < text.length -> {
                        i++
                        when (val e = text[i]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'u' -> {
                                val hex = text.substring(i + 1, minOf(i + 5, text.length))
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            return sb.toString()
        }

        private fun rawKey(): String {
            val start = i
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-')) i++
            if (start == i) fail("مفتاح غير صالح")
            return text.substring(start, i)
        }

        private fun number(): J.Num {
            val start = i
            if (peek() == '-') i++
            while (i < text.length && (text[i].isDigit() || text[i] == '.' || text[i] == 'e' || text[i] == 'E' || text[i] == '-' || text[i] == '+')) i++
            return J.Num(text.substring(start, i).toDoubleOrNull() ?: 0.0)
        }

        private fun bool(): J.Bool = when {
            text.startsWith("true", i) -> { i += 4; J.Bool(true) }
            text.startsWith("false", i) -> { i += 5; J.Bool(false) }
            else -> fail("قيمة منطقية غير صالحة")
        }

        private fun expect(literal: String) {
            if (!text.startsWith(literal, i)) fail("متوقع $literal")
            i += literal.length
        }

        private fun skipWs() {
            while (i < text.length && text[i].isWhitespace()) i++
        }

        private fun peek(): Char? = if (i < text.length) text[i] else null

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }
}
