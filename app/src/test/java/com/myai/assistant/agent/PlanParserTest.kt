package com.myai.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanParserTest {

    private val tools = listOf(
        fakeTool("open_app", listOf(ToolParameter("app", "app name"))),
        fakeTool(
            "set_alarm",
            listOf(
                ToolParameter("hour", "hour", type = ParameterType.INTEGER),
                ToolParameter("minute", "minute", type = ParameterType.INTEGER)
            )
        ),
        fakeTool("screen_summary")
    )

    @Test
    fun `parses a valid plan wrapped in code fences`() {
        val raw = """
            ```json
            {"reply":"سأفتح واتساب","steps":[{"tool":"open_app","args":{"app":"واتساب"},"title":"فتح واتساب"}]}
            ```
        """.trimIndent()

        val result = PlanParser.parse(raw, tools, "افتح واتساب")

        assertTrue(result is PlannerResult.Planned)
        val planned = result as PlannerResult.Planned
        assertEquals(1, planned.plan.steps.size)
        assertEquals("open_app", planned.plan.steps[0].toolId)
        assertEquals("واتساب", planned.plan.steps[0].args["app"])
        assertEquals("سأفتح واتساب", planned.reply)
    }

    @Test
    fun `converts numeric arguments to strings`() {
        val raw = """{"reply":"منبه","steps":[{"tool":"set_alarm","args":{"hour":6,"minute":30},"title":"منبه"}]}"""
        val result = PlanParser.parse(raw, tools, "منبه")
        assertTrue(result is PlannerResult.Planned)
        val step = (result as PlannerResult.Planned).plan.steps.single()
        assertEquals("6", step.args["hour"])
        assertEquals("30", step.args["minute"])
    }

    @Test
    fun `rejects an unknown tool before execution`() {
        val raw = """{"reply":"x","steps":[{"tool":"delete_everything","args":{}}]}"""
        val result = PlanParser.parse(raw, tools, "x")
        assertTrue(result is PlannerResult.Failed)
        assertTrue((result as PlannerResult.Failed).message.contains("غير معروفة"))
    }

    @Test
    fun `rejects a step with missing required parameters`() {
        val raw = """{"reply":"x","steps":[{"tool":"open_app","args":{}}]}"""
        val result = PlanParser.parse(raw, tools, "x")
        assertTrue(result is PlannerResult.Failed)
        assertTrue((result as PlannerResult.Failed).message.contains("app"))
    }

    @Test
    fun `empty steps with a reply becomes a direct answer`() {
        val result = PlanParser.parse("""{"reply":"الساعة الآن 09:00","steps":[]}""", tools, "كم الساعة")
        assertTrue(result is PlannerResult.DirectAnswer)
        assertEquals("الساعة الآن 09:00", (result as PlannerResult.DirectAnswer).reply)
    }

    @Test
    fun `extracts balanced json with braces inside strings`() {
        val raw = """{ "reply": "هذا {اختبار} داخلي", "steps": [] }"""
        assertEquals(raw, PlanParser.extractJsonObject(raw))
    }

    @Test
    fun `rejects garbage that is not json`() {
        val result = PlanParser.parse("أهلًا بك", tools, "x")
        assertTrue(result is PlannerResult.Failed)
    }

    private fun fakeTool(id: String, parameters: List<ToolParameter> = emptyList()) = object : Tool {
        override val id = id
        override val title = id
        override val description = id
        override val parameters = parameters
        override fun summary(args: Map<String, String>) = id
        override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome =
            ToolOutcome.verified("ok")
    }
}
