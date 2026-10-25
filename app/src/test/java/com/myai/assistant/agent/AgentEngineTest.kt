package com.myai.assistant.agent

import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.ExecutionPlan
import com.myai.assistant.core.model.PlanStep
import com.myai.assistant.core.model.StepStatus
import com.myai.assistant.core.model.Verification
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEngineTest {

    private class FakePlanner(private val result: PlannerResult) : Planner {
        override val id = "fake"
        override suspend fun plan(
            request: String,
            history: List<ChatMessage>,
            tools: List<Tool>
        ): PlannerResult = result
    }

    private class FakeTool(
        override val id: String,
        override val risk: RiskLevel = RiskLevel.SAFE,
        override val requiresAccessibility: Boolean = false
    ) : Tool {
        override val title = "أداة $id"
        override val description = id
        override val parameters: List<ToolParameter> = emptyList()
        override fun summary(args: Map<String, String>) = title
        override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome =
            ToolOutcome.verified("ok")
    }

    private fun planned(vararg toolIds: String) = PlannerResult.Planned(
        plan = ExecutionPlan(
            goal = "test",
            source = "test",
            steps = toolIds.mapIndexed { index, id ->
                PlanStep(id = "step-${index + 1}", toolId = id, title = "خطوة ${index + 1}")
            }
        ),
        reply = "سأنفّذ"
    )

    private fun engine(
        plannerResult: PlannerResult,
        tools: List<Tool>,
        invoker: suspend (Tool, Map<String, String>) -> ToolOutcome = { _, _ -> ToolOutcome.verified("تم") },
        accessibilityReady: Boolean = true
    ) = AgentEngine(
        plannerProvider = { FakePlanner(plannerResult) },
        registry = ToolRegistry(tools),
        invoker = invoker,
        accessibilityReady = { accessibilityReady },
        toolTimeoutMs = 5_000
    )

    @Test
    fun `executes all steps and reports verified results`() = runTest {
        val tools = listOf(FakeTool("open_app"), FakeTool("global_action"))
        val events = engine(planned("open_app", "global_action"), tools)
            .run("افتح واتساب ثم ارجع", emptyList(), ConfirmationHandler { true })
            .toList()

        val finished = events.filterIsInstance<AgentEvent.Finished>().single()
        val plan = finished.plan!!
        assertEquals(2, plan.steps.size)
        assertTrue(plan.steps.all { it.status == StepStatus.SUCCEEDED })
        assertTrue(plan.steps.all { it.verification == Verification.VERIFIED })

        val reply = events.filterIsInstance<AgentEvent.Reply>().single().text
        assertTrue(reply.contains("تم تنفيذ الخطة بالكامل"))
        assertTrue(reply.contains("تحقّقنا من 2"))
    }

    @Test
    fun `denied confirmation skips the remaining steps`() = runTest {
        val tools = listOf(FakeTool("compose_sms", risk = RiskLevel.SENSITIVE), FakeTool("open_app"))
        var invocationCount = 0
        val events = engine(
            planned("compose_sms", "open_app"),
            tools,
            invoker = { _, _ -> invocationCount++; ToolOutcome.verified("تم") }
        ).run("أرسل رسالة", emptyList(), ConfirmationHandler { false }).toList()

        val plan = events.filterIsInstance<AgentEvent.Finished>().single().plan!!
        assertEquals(StepStatus.SKIPPED, plan.steps[0].status)
        assertEquals(StepStatus.SKIPPED, plan.steps[1].status)
        assertEquals(0, invocationCount)
        assertTrue(events.filterIsInstance<AgentEvent.ConfirmationRequired>().isNotEmpty())
    }

    @Test
    fun `a failing step aborts the plan`() = runTest {
        val tools = listOf(FakeTool("first"), FakeTool("second"))
        var calls = 0
        val events = engine(
            planned("first", "second"),
            tools,
            invoker = { _, _ ->
                calls++
                if (calls == 1) ToolOutcome.failed("تعذّر التنفيذ") else ToolOutcome.verified("تم")
            }
        ).run("طلب", emptyList(), ConfirmationHandler { true }).toList()

        val plan = events.filterIsInstance<AgentEvent.Finished>().single().plan!!
        assertEquals(StepStatus.FAILED, plan.steps[0].status)
        assertEquals(Verification.FAILED, plan.steps[0].verification)
        assertEquals(StepStatus.SKIPPED, plan.steps[1].status)
        assertEquals(1, calls)

        val reply = events.filterIsInstance<AgentEvent.Reply>().single().text
        assertTrue(reply.contains("توقّفت الخطة"))
        assertTrue(reply.contains("تعذّر التنفيذ"))
    }

    @Test
    fun `accessibility steps fail fast when the service is off`() = runTest {
        val tools = listOf(FakeTool("screen_summary", requiresAccessibility = true))
        var called = false
        val events = engine(
            planned("screen_summary"),
            tools,
            invoker = { _, _ -> called = true; ToolOutcome.verified("تم") },
            accessibilityReady = false
        ).run("اقرأ الشاشة", emptyList(), ConfirmationHandler { true }).toList()

        val step = events.filterIsInstance<AgentEvent.Finished>().single().plan!!.steps.single()
        assertEquals(StepStatus.FAILED, step.status)
        assertTrue(step.resultMessage!!.contains("الوصولية"))
        assertFalse(called)
    }

    @Test
    fun `exceptions from tools become failed steps`() = runTest {
        val tools = listOf(FakeTool("boom"))
        val events = engine(
            planned("boom"),
            tools,
            invoker = { _, _ -> throw IllegalStateException("انفجار") }
        ).run("طلب", emptyList(), ConfirmationHandler { true }).toList()

        val step = events.filterIsInstance<AgentEvent.Finished>().single().plan!!.steps.single()
        assertEquals(StepStatus.FAILED, step.status)
        assertTrue(step.resultMessage!!.contains("انفجار"))
    }

    @Test
    fun `safe steps never ask for confirmation`() = runTest {
        val tools = listOf(FakeTool("open_app"))
        var confirmations = 0
        engine(
            planned("open_app"),
            tools
        ).run("افتح واتساب", emptyList(), ConfirmationHandler { confirmations++; true }).toList()

        assertEquals(0, confirmations)
    }

    @Test
    fun `planner failures surface as an error event`() = runTest {
        val events = engine(PlannerResult.Failed("لا أفهم"), emptyList())
            .run("؟؟", emptyList(), ConfirmationHandler { true })
            .toList()

        val error = events.filterIsInstance<AgentEvent.Error>().single()
        assertEquals("لا أفهم", error.message)
        assertTrue(events.filterIsInstance<AgentEvent.Finished>().single().plan == null)
    }

    @Test
    fun `direct answers finish without a plan`() = runTest {
        val events = engine(PlannerResult.DirectAnswer("الساعة 9"), emptyList())
            .run("كم الساعة", emptyList(), ConfirmationHandler { true })
            .toList()

        assertEquals("الساعة 9", events.filterIsInstance<AgentEvent.Reply>().single().text)
        assertTrue(events.filterIsInstance<AgentEvent.Finished>().single().plan == null)
    }
}
