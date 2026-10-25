package com.myai.assistant.agent

import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.ExecutionPlan
import com.myai.assistant.core.model.PlanStep
import com.myai.assistant.core.model.StepStatus
import com.myai.assistant.tools.defaultTools
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the consent policy down.
 *
 * Every tool that writes into another app, sends data off the device, reads the
 * screen or changes device state must be SENSITIVE, and [AgentEngine] must ask the
 * user before running it — there is no setting that skips the question.
 */
class ToolConfirmationPolicyTest {

    private val tools = defaultTools()

    /**
     * If you add a tool, decide its risk deliberately and update this list; the
     * test fails otherwise, so a new capability cannot silently skip consent.
     */
    private val expectedSensitive = setOf(
        "dial_number",
        "compose_sms",
        "share_text",
        "copy_to_clipboard",
        "set_alarm",
        "set_timer",
        "create_calendar_event",
        "media_control",
        "flashlight",
        "screen_summary",
        "click_text",
        "set_text",
        "global_action"
    )

    private val expectedSafe = setOf(
        "open_app",
        "open_url",
        "web_search",
        "device_status",
        "open_settings",
        "wait",
        "scroll_screen"
    )

    private fun byId(id: String): Tool = tools.single { it.id == id }

    private fun planFor(toolId: String, args: Map<String, String> = emptyMap()) = ExecutionPlan(
        goal = "test",
        source = "test",
        steps = listOf(PlanStep(id = "step-1", toolId = toolId, title = "خطوة", args = args))
    )

    private class FixedPlanner(private val plan: ExecutionPlan) : Planner {
        override val id = "fixed"
        override suspend fun plan(
            request: String,
            history: List<ChatMessage>,
            tools: List<Tool>
        ): PlannerResult = PlannerResult.Planned(plan, "سأنفّذ")
    }

    private fun engine(tool: Tool, plan: ExecutionPlan, invoker: suspend () -> ToolOutcome) = AgentEngine(
        plannerProvider = { FixedPlanner(plan) },
        registry = ToolRegistry(listOf(tool)),
        invoker = { _, _ -> invoker() },
        accessibilityReady = { true },
        toolTimeoutMs = 5_000
    )

    @Test
    fun `the risk classification matches the reviewed policy`() {
        val sensitive = tools.filter { it.risk == RiskLevel.SENSITIVE }.map { it.id }.toSet()
        val safe = tools.filter { it.risk == RiskLevel.SAFE }.map { it.id }.toSet()

        assertEquals(expectedSensitive, sensitive)
        assertEquals(expectedSafe, safe)
        assertEquals(tools.size, sensitive.size + safe.size)
    }

    @Test
    fun `every sensitive tool asks for confirmation and never runs without it`() = runTest {
        for (id in expectedSensitive) {
            val tool = byId(id)
            var executed = false
            val events = engine(tool, planFor(id)) {
                executed = true
                ToolOutcome.verified("ok")
            }.run("طلب", emptyList(), ConfirmationHandler { false }).toList()

            val confirmation = events.filterIsInstance<AgentEvent.ConfirmationRequired>()
            assertEquals("$id must ask for confirmation", 1, confirmation.size)
            assertFalse("$id ran without consent", executed)

            val step = events.filterIsInstance<AgentEvent.Finished>().single().plan!!.steps.single()
            assertEquals("$id should be skipped", StepStatus.SKIPPED, step.status)
            assertTrue(step.resultMessage!!.contains("أُلغيت"))
        }
    }

    @Test
    fun `a confirmed sensitive step runs exactly once`() = runTest {
        val tool = byId("set_text")
        var calls = 0
        var asked = 0
        val events = engine(tool, planFor("set_text", mapOf("text" to "مرحبا"))) {
            calls++
            ToolOutcome.verified("كُتب النص")
        }.run("اكتب مرحبا", emptyList(), ConfirmationHandler { asked++; true }).toList()

        assertEquals(1, asked)
        assertEquals(1, calls)
        assertEquals(StepStatus.SUCCEEDED, events.filterIsInstance<AgentEvent.Finished>().single().plan!!.steps.single().status)
    }

    @Test
    fun `safe tools never interrupt the user`() = runTest {
        for (id in expectedSafe) {
            var asked = 0
            var executed = false
            engine(byId(id), planFor(id)) {
                executed = true
                ToolOutcome.dispatched("ok")
            }.run("طلب", emptyList(), ConfirmationHandler { asked++; true }).toList()

            assertEquals("$id must not ask", 0, asked)
            assertTrue("$id should run", executed)
        }
    }

    @Test
    fun `confirmation details name the exact action`() {
        assertEquals(
            "كتابة \"مرحبا\"",
            byId("set_text").summary(mapOf("text" to "مرحبا"))
        )
        assertEquals(
            "كتابة \"مرحبا\" في الحقل \"الاسم\"",
            byId("set_text").summary(mapOf("text" to "مرحبا", "target" to "الاسم"))
        )
        assertTrue(byId("global_action").summary(mapOf("action" to "home")).contains("الرئيسية"))
        assertTrue(byId("dial_number").summary(mapOf("number" to "0555")).contains("0555"))
        assertTrue(
            byId("copy_to_clipboard").summary(mapOf("text" to "نص طويل")).contains("الحافظة")
        )
    }

    @Test
    fun `screen reading is marked local-only in the executed plan`() = runTest {
        val tool = byId("screen_summary")
        val events = engine(tool, planFor("screen_summary")) {
            ToolOutcome.verified("رصيد الحساب 4521.00 ريال")
        }.run("اقرأ الشاشة", emptyList(), ConfirmationHandler { true }).toList()

        val planEvents = events.filterIsInstance<AgentEvent.PlanCreated>()
        assertTrue(planEvents.isNotEmpty())
        assertTrue(planEvents.first().plan.steps.single().localOnly)

        val finished = events.filterIsInstance<AgentEvent.Finished>().single().plan!!
        assertTrue(finished.steps.single().localOnly)
    }

    @Test
    fun `only the screen reader is allowed to mark output as screen content`() {
        val screenTools = tools.filter { it.outputPrivacy == OutputPrivacy.SCREEN_CONTENT }.map { it.id }
        assertEquals(listOf("screen_summary"), screenTools)
    }
}
