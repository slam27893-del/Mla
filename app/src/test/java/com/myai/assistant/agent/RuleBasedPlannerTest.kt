package com.myai.assistant.agent

import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class RuleBasedPlannerTest {

    private val planner = RuleBasedPlanner()

    private fun tools(): List<Tool> = listOf(
        "open_app", "open_url", "web_search", "dial_number", "compose_sms", "set_alarm",
        "set_timer", "create_calendar_event", "share_text", "copy_to_clipboard",
        "media_control", "flashlight", "device_status", "open_settings", "wait",
        "screen_summary", "click_text", "set_text", "scroll_screen", "global_action"
    ).map { fakeTool(it) }

    @Test
    fun `opens an app by its Arabic label`() = runTest {
        val result = planner.plan("افتح واتساب", emptyList(), tools())
        val planned = result as PlannerResult.Planned
        assertEquals("open_app", planned.plan.steps.single().toolId)
        assertEquals("واتساب", planned.plan.steps.single().args["app"])
    }

    @Test
    fun `handles shadda and hamza in verbs`() = runTest {
        val result = planner.plan("شغّل يوتيوب", emptyList(), tools())
        val planned = result as PlannerResult.Planned
        assertEquals("open_app", planned.plan.steps.single().toolId)
        assertEquals("يوتيوب", planned.plan.steps.single().args["app"])
    }

    @Test
    fun `sets an alarm with a clock time`() = runTest {
        val result = planner.plan("اضبط منبه الساعة 6:30", emptyList(), tools())
        val step = (result as PlannerResult.Planned).plan.steps.single()
        assertEquals("set_alarm", step.toolId)
        assertEquals("6", step.args["hour"])
        assertEquals("30", step.args["minute"])
    }

    @Test
    fun `converts pm meridiem to 24 hour time`() = runTest {
        val result = planner.plan("ضع منبه 7 مساءً", emptyList(), tools())
        val step = (result as PlannerResult.Planned).plan.steps.single()
        assertEquals("19", step.args["hour"])
        assertEquals("0", step.args["minute"])
    }

    @Test
    fun `creates a timer from a relative duration`() = runTest {
        val result = planner.plan("نبهني بعد 10 دقائق", emptyList(), tools())
        val step = (result as PlannerResult.Planned).plan.steps.single()
        assertEquals("set_timer", step.toolId)
        assertEquals("600", step.args["seconds"])
    }

    @Test
    fun `opens the wifi settings screen instead of toggling it`() = runTest {
        val result = planner.plan("افتح إعدادات الواي فاي", emptyList(), tools())
        val step = (result as PlannerResult.Planned).plan.steps.single()
        assertEquals("open_settings", step.toolId)
        assertEquals("wifi", step.args["panel"])
    }

    @Test
    fun `prepares a phone call with digits only`() = runTest {
        val result = planner.plan("اتصل على 050 123 4567", emptyList(), tools())
        val step = (result as PlannerResult.Planned).plan.steps.single()
        assertEquals("dial_number", step.toolId)
        assertEquals("0501234567", step.args["number"])
    }

    @Test
    fun `prepares an sms with body`() = runTest {
        val result = planner.plan("أرسل رسالة إلى 0501234567 تقول مرحبا", emptyList(), tools())
        val step = (result as PlannerResult.Planned).plan.steps.single()
        assertEquals("compose_sms", step.toolId)
        assertEquals("0501234567", step.args["number"])
        assertEquals("مرحبا", step.args["message"])
    }

    @Test
    fun `searches the web`() = runTest {
        val result = planner.plan("ابحث عن طقس جدة", emptyList(), tools())
        val step = (result as PlannerResult.Planned).plan.steps.single()
        assertEquals("web_search", step.toolId)
        assertEquals("طقس جدة", step.args["query"])
    }

    @Test
    fun `turns on the flashlight`() = runTest {
        val result = planner.plan("شغل الفلاش", emptyList(), tools())
        val step = (result as PlannerResult.Planned).plan.steps.single()
        assertEquals("flashlight", step.toolId)
        assertEquals("on", step.args["state"])
    }

    @Test
    fun `maps back to a global accessibility action`() = runTest {
        val result = planner.plan("ارجع", emptyList(), tools())
        val step = (result as PlannerResult.Planned).plan.steps.single()
        assertEquals("global_action", step.toolId)
        assertEquals("back", step.args["action"])
    }

    @Test
    fun `splits a compound command into ordered steps`() = runTest {
        val result = planner.plan("افتح واتساب ثم ارجع", emptyList(), tools())
        val steps = (result as PlannerResult.Planned).plan.steps
        assertEquals(2, steps.size)
        assertEquals("open_app", steps[0].toolId)
        assertEquals("global_action", steps[1].toolId)
        assertEquals("step-1", steps[0].id)
        assertEquals("step-2", steps[1].id)
    }

    @Test
    fun `answers a time question without tools`() = runTest {
        val result = planner.plan("كم الساعة؟", emptyList(), tools())
        assertTrue(result is PlannerResult.DirectAnswer)
    }

    @Test
    fun `asks for a missing alarm time`() = runTest {
        val result = planner.plan("اضبط منبه", emptyList(), tools())
        assertTrue(result is PlannerResult.DirectAnswer)
    }

    @Test
    fun `refuses unknown commands honestly`() = runTest {
        val result = planner.plan("اكتب لي قصيدة عن البحر", emptyList(), tools())
        assertTrue(result is PlannerResult.Failed)
    }

    @Test
    fun `fallback planner annotates the offline source`() = runTest {
        val fallback = FallbackPlanner(
            primary = object : Planner {
                override val id = "always-fails"
                override suspend fun plan(
                    request: String,
                    history: List<ChatMessage>,
                    tools: List<Tool>
                ) = PlannerResult.Failed("المزود غير متاح")
            },
            fallback = planner
        )
        val result = fallback.plan("افتح واتساب", emptyList(), tools())
        val planned = result as PlannerResult.Planned
        assertEquals("offline-fallback", planned.plan.source)
        assertEquals(StepStatus.PENDING, planned.plan.steps.single().status)
        assertTrue(planned.reply.contains("المحرك المحلي"))
    }

    private fun fakeTool(id: String) = object : Tool {
        override val id = id
        override val title = id
        override val description = id
        override val parameters: List<ToolParameter> = emptyList()
        override fun summary(args: Map<String, String>) = id
        override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome =
            ToolOutcome.verified("ok")
    }
}
