package com.myai.assistant.draft

/**
 * Every byte that leaves the device in the draft path is produced here, so the
 * privacy review has a single place to look.
 *
 * The payload contains: a fixed system prompt, a fixed instruction, and exactly
 * one image. No chat history, no saved screen text, no API key (the key is added
 * as a header by the transport, never in the body).
 */
object DraftPrompt {

    val SYSTEM_PROMPT: String = """
        أنت قارئ شاشة لمساعد Draft في Brawl Stars. تُعطى صورة واحدة لشاشة اختيار الأبطال
        وتعيد JSON فقط، دون أي شرح خارجه.

        أعد المفاتيح التالية:
        - map: اسم الخريطة كما يظهر حرفيًا، أو null إذا لم يكن مقروءًا.
        - mode: واحد من GEM_GRAB, BRAWL_BALL, HEIST, BOUNTY, KNOCKOUT, HOT_ZONE, DUELS، أو null.
        - bans: أسماء الأبطال المحظورة (مصفوفة، فارغة إن لم يوجد).
        - allies: أبطال فريقك المختارون.
        - enemies: أبطال فريق الخصم.
        - pick_slot: 1 أو 2 أو 3 لموقع اختيارك بين اختيارات فريقك، أو null.
        - my_brawler: بطلك المقفل إن كان معروفًا، وإلا null.
        - confidence: رقم بين 0 و 1 لثقتك في القراءة.
        - unreadable: مصفوفة بأسماء الحقول التي لم تستطع قراءتها.
        - notes: ملاحظة قصيرة جدًا عند الحاجة.

        قواعد صارمة:
        1. لا تخمّن أي اسم. الحقل غير الواضح يوضع في unreadable ويُترك null.
        2. لا تكتب أسماء أبطال غير موجودة في اللعبة.
        3. اكتب أسماء الأبطال بالإنجليزية كما في اللعبة (مثال: El Primo).
        4. لا تضف أي مفاتيح أخرى، ولا نصًا قبل JSON أو بعده.
    """.trimIndent()

    /** Fixed instruction that accompanies the image. Intentionally carries no user data. */
    fun instruction(): String =
        "اقرأ هذه اللقطة لشاشة الـ Draft وأعد JSON بالمخطط المتفق عليه فقط."

    /** True when [payload] is shaped exactly like the draft vision request. */
    fun buildVisionPayload(model: String, base64Jpeg: String, maxTokens: Int = 700): String {
        val image = "data:image/jpeg;base64,$base64Jpeg"
        return buildString {
            append("{\"model\":").append(quote(model))
            append(",\"temperature\":0")
            append(",\"max_tokens\":").append(maxTokens)
            append(",\"messages\":[")
            append("{\"role\":\"system\",\"content\":").append(quote(SYSTEM_PROMPT)).append("},")
            append("{\"role\":\"user\",\"content\":[")
            append("{\"type\":\"text\",\"text\":").append(quote(instruction())).append("},")
            append("{\"type\":\"image_url\",\"image_url\":{\"url\":").append(quote(image)).append("}}")
            append("]}]}")
        }
    }

    private fun quote(raw: String): String = buildString(raw.length + 2) {
        append('"')
        raw.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
