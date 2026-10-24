package com.myai.assistant.tools

import com.myai.assistant.agent.ScreenSnapshot
import com.myai.assistant.privacy.ScreenContentGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSummaryFormatterTest {

    private val guard = ScreenContentGuard()

    private val snapshot = ScreenSnapshot(
        packageName = "com.android.settings",
        texts = listOf("البطارية", "مستوى الشحن 82%", "رصيد الحساب 4521.00 ريال"),
        clickableTexts = listOf("مزيد من الإعدادات")
    )

    @Test
    fun `formats a readable local summary`() {
        val body = ScreenSummaryFormatter.format(snapshot, guard)

        assertTrue(body.contains("com.android.settings"))
        assertTrue(body.contains("رصيد الحساب 4521.00 ريال"))
        assertTrue(body.contains("عناصر قابلة للنقر"))
    }

    @Test
    fun `registers everything it prints with the privacy guard`() {
        val body = ScreenSummaryFormatter.format(snapshot, guard)

        assertTrue(guard.containsProtected("رصيد الحساب 4521.00 ريال"))
        assertTrue(guard.containsProtected("مزيد من الإعدادات"))
        // The formatted body itself is recognisable as screen-derived.
        assertTrue(guard.containsProtected(body))
    }

    @Test
    fun `empty snapshot is explained without inventing content`() {
        val body = ScreenSummaryFormatter.format(
            ScreenSnapshot(packageName = "com.example", texts = emptyList(), clickableTexts = emptyList()),
            guard
        )

        assertTrue(body.contains("لم أجد"))
        assertEquals(0, guard.protectedFragmentCount())
    }

    @Test
    fun `long snapshots are clipped`() {
        val many = (1..200).map { "سطر رقم $it في قائمة طويلة جدًا على الشاشة" }
        val body = ScreenSummaryFormatter.format(
            ScreenSnapshot(packageName = "com.example", texts = many, clickableTexts = emptyList()),
            guard
        )

        assertTrue(body.length <= ScreenSummaryFormatter.MAX_CHARS)
        assertTrue(body.contains("تم اختصار"))
    }

    @Test
    fun `screen text is not mistaken for a tool argument`() {
        // Guard against a regression where the snapshot leaks into a tool summary.
        ScreenSummaryFormatter.format(snapshot, guard)
        val setText = SetTextTool().summary(mapOf("text" to "مرحبا"))

        assertFalse(setText.contains("4521"))
    }
}
