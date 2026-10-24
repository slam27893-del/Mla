package com.myai.assistant.tools

import com.myai.assistant.agent.ScreenSnapshot
import com.myai.assistant.privacy.ScreenContentGuard

/**
 * Turns an Accessibility snapshot into the text shown in the local transcript and
 * registers everything it contains with the [ScreenContentGuard] in the same step,
 * so the two can never drift apart.
 *
 * Pure Kotlin on purpose: this is the one place that decides what counts as
 * "screen content", so it is covered by plain unit tests.
 */
object ScreenSummaryFormatter {

    const val MAX_TEXTS = 60
    const val MAX_CLICKABLE = 15
    const val MAX_CHARS = 3500

    fun format(snapshot: ScreenSnapshot, guard: ScreenContentGuard): String {
        guard.registerScreenRead(snapshot.texts.joinToString("\n"))
        guard.registerScreenRead(snapshot.clickableTexts.joinToString("\n"))

        if (snapshot.texts.isEmpty()) {
            return "لم أجد عناصر نصية ظاهرة على الشاشة (قد تكون شاشة رسومية فقط)."
        }

        val body = buildString {
            appendLine("التطبيق الأمامي: ${snapshot.packageName ?: "غير معروف"}")
            snapshot.texts.take(MAX_TEXTS).forEachIndexed { index, text ->
                appendLine("${index + 1}. $text")
            }
            if (snapshot.texts.size > MAX_TEXTS) appendLine("… (تم اختصار بقية العناصر)")
            if (snapshot.clickableTexts.isNotEmpty()) {
                appendLine()
                appendLine("عناصر قابلة للنقر: " + snapshot.clickableTexts.take(MAX_CLICKABLE).joinToString("، "))
            }
        }.trim().take(MAX_CHARS)

        // The formatted body itself (headers included) is screen-derived.
        guard.registerScreenRead(body)
        return body
    }
}
