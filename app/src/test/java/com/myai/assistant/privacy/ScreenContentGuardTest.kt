package com.myai.assistant.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContentGuardTest {

    private val guard = ScreenContentGuard()

    @Test
    fun `registers a screen readout and detects it`() {
        guard.registerScreenRead("رصيد الحساب 4521.00 ريال\nآخر عملية: 120 ريال")

        assertTrue(guard.containsProtected("رصيد الحساب 4521.00 ريال"))
        assertTrue(guard.protectedFragmentCount() > 0)
    }

    @Test
    fun `detects the content embedded in a longer sentence`() {
        guard.registerScreenRead("رصيد الحساب 4521.00 ريال")

        assertTrue(
            guard.containsProtected("تذكر أن رصيد الحساب 4521.00 ريال كان ظاهرًا على الشاشة")
        )
    }

    @Test
    fun `a distinctive token quoted on its own is detected`() {
        guard.registerScreenRead("رقم العملية ABC-9911-XYZ داخل التطبيق")
        assertTrue(guard.containsProtected("تذكر ABC-9911-XYZ"))
    }

    @Test
    fun `detection survives whitespace and diacritics changes`() {
        guard.registerScreenRead("رَصيد الحساب   4521.00 ريال")

        assertTrue(guard.containsProtected("رصيد  الحساب 4521.00 ريال"))
    }

    @Test
    fun `redacts every protected fragment and never returns the original text`() {
        guard.registerScreenRead("رقم البطاقة 4111 1111 1111 1111")

        val scrubbed = guard.redact("الرقم هو رقم البطاقة 4111 1111 1111 1111 وكفى")

        assertFalse(scrubbed.contains("4111"))
        assertTrue(scrubbed.contains(ScreenContentGuard.REDACTED))
    }

    @Test
    fun `clean text passes through unchanged`() {
        guard.registerScreenRead("رصيد الحساب 4521.00 ريال")

        val clean = "افتح الإعدادات ثم اذهب إلى الواي فاي"
        assertEquals(clean, guard.redact(clean))
        assertFalse(guard.containsProtected(clean))
    }

    @Test
    fun `short generic labels are not registered`() {
        // "إرسال" is a button label on many screens; treating it as secret would
        // mangle ordinary follow-up questions.
        guard.registerScreenRead("إرسال\nرجوع\nحفظ")

        assertEquals(0, guard.protectedFragmentCount())
        assertFalse(guard.containsProtected("أرسل رسالة إلى أحمد"))
    }

    @Test
    fun `a distinctive header line is protected`() {
        guard.registerScreenRead("التطبيق الأمامي: com.android.settings")

        assertTrue(guard.containsProtected("التطبيق الأمامي: com.android.settings"))
    }

    @Test
    fun `clear forgets everything`() {
        guard.registerScreenRead("رصيد الحساب 4521.00 ريال")
        guard.clear()

        assertEquals(0, guard.protectedFragmentCount())
        assertFalse(guard.containsProtected("رصيد الحساب 4521.00 ريال"))
    }

    @Test
    fun `guard memory is bounded`() {
        repeat(600) { index ->
            guard.registerScreenRead("سطر طويل خاص بالشاشة رقم 1000$index لا يُنسى")
        }
        assertTrue(guard.protectedFragmentCount() <= 400)
    }
}
