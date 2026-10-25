package com.myai.assistant.privacy

import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.ExecutionPlan
import com.myai.assistant.core.model.PlanStep
import com.myai.assistant.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHistoryFilterTest {

    private val guard = ScreenContentGuard()

    private fun message(
        id: String,
        text: String,
        role: Role = Role.ASSISTANT,
        plan: ExecutionPlan? = null
    ) = ChatMessage(id = id, role = role, text = text, timestamp = 0L, plan = plan)

    private fun screenPlan(): ExecutionPlan = ExecutionPlan(
        goal = "اقرأ الشاشة",
        source = "llm",
        steps = listOf(
            PlanStep(
                id = "step-1",
                toolId = "screen_summary",
                title = "قراءة الشاشة",
                localOnly = true,
                resultMessage = "رصيد الحساب 4521.00 ريال"
            )
        )
    )

    @Test
    fun `a turn that read the screen is excluded from remote history`() {
        val messages = listOf(
            message("1", "افتح التطبيق", role = Role.USER),
            message("2", "تم فتح التطبيق", plan = screenPlan())
        )

        val allowed = RemoteHistoryFilter.forRemote(messages, guard)

        assertEquals(listOf("1"), allowed.map { it.id })
    }

    @Test
    fun `screen text in plain text is excluded even without a plan`() {
        guard.registerScreenRead("رصيد الحساب 4521.00 ريال")
        val messages = listOf(
            message("1", "كم رصيدي؟", role = Role.USER),
            message("2", "رصيد الحساب 4521.00 ريال")
        )

        assertEquals(listOf("1"), RemoteHistoryFilter.forRemote(messages, guard).map { it.id })
    }

    @Test
    fun `legacy transcripts without the flag are still excluded`() {
        // A transcript persisted before PlanStep.localOnly existed.
        val legacyPlan = ExecutionPlan(
            goal = "اقرأ الشاشة",
            source = "llm",
            steps = listOf(PlanStep(id = "step-1", toolId = "screen_summary", title = "قراءة الشاشة"))
        )
        val messages = listOf(message("1", "تم", plan = legacyPlan))

        assertEquals(emptyList<String>(), RemoteHistoryFilter.forRemote(messages, guard).map { it.id })
    }

    @Test
    fun `ordinary conversation is kept`() {
        val messages = listOf(
            message("1", "شغّل الفلاش", role = Role.USER),
            message("2", "تم تنفيذ الخطة بالكامل")
        )

        assertEquals(2, RemoteHistoryFilter.forRemote(messages, guard).size)
        assertTrue(RemoteHistoryFilter.allowsRemote(messages[0]))
    }

    @Test
    fun `system messages and blank messages are never sent`() {
        assertFalse(RemoteHistoryFilter.allowsRemote(message("1", "نص", role = Role.SYSTEM)))
        assertFalse(RemoteHistoryFilter.allowsRemote(message("2", "   ", role = Role.ASSISTANT)))
    }

    @Test
    fun `only the newest turns are kept`() {
        val messages = (1..12).map { message("$it", "رسالة رقم $it", role = Role.USER) }

        val allowed = RemoteHistoryFilter.forRemote(messages, guard, limit = 4)

        assertEquals(listOf("9", "10", "11", "12"), allowed.map { it.id })
    }

    @Test
    fun `local-only content is detected from the plan alone`() {
        val withScreen = message("1", "تم", plan = screenPlan())
        val normal = message("2", "تم")

        assertTrue(withScreen.containsLocalOnlyContent)
        assertFalse(normal.containsLocalOnlyContent)
    }
}
