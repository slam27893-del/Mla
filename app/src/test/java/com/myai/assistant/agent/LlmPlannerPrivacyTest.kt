package com.myai.assistant.agent

import com.myai.assistant.ai.AiProvider
import com.myai.assistant.ai.CompletionRequest
import com.myai.assistant.ai.CompletionResult
import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.ExecutionPlan
import com.myai.assistant.core.model.PlanStep
import com.myai.assistant.core.model.Role
import com.myai.assistant.privacy.ScreenContentGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the remote planner never forwards anything read from the screen.
 *
 * The fake provider captures the exact `CompletionRequest`, which is the last
 * object before the HTTP payload, so an assertion on it is an assertion on what
 * would have been sent over the network.
 */
class LlmPlannerPrivacyTest {

    private class CapturingProvider(
        private val reply: String = """{"reply":"حسنًا","steps":[]}"""
    ) : AiProvider {
        var lastRequest: CompletionRequest? = null

        override val id = "fake"
        override val displayName = "fake"
        override val isConfigured = true
        override val configurationHint: String? = null

        override suspend fun complete(request: CompletionRequest): CompletionResult {
            lastRequest = request
            return CompletionResult(reply, id)
        }

        fun outboundText(): String = buildString {
            val request = lastRequest ?: return ""
            appendLine(request.systemPrompt)
            request.turns.forEach { appendLine(it.content) }
        }.trim()
    }

    private class NoTools : Tool {
        override val id = "open_app"
        override val title = "فتح تطبيق"
        override val description = "يفتح تطبيقًا"
        override val parameters = emptyList<ToolParameter>()
        override fun summary(args: Map<String, String>) = title
        override suspend fun execute(context: ToolContext, args: Map<String, String>) =
            ToolOutcome.verified("تم")
    }

    private val secret = "رصيد الحساب 4521.00 ريال"

    private fun message(
        id: String,
        text: String,
        role: Role = Role.ASSISTANT,
        localOnly: Boolean = false
    ) = ChatMessage(
        id = id,
        role = role,
        text = text,
        timestamp = 0L,
        plan = if (localOnly) {
            ExecutionPlan(
                goal = "قراءة الشاشة",
                source = "llm",
                steps = listOf(
                    PlanStep(
                        id = "step-1",
                        toolId = "screen_summary",
                        title = "قراءة الشاشة",
                        localOnly = true,
                        resultMessage = secret
                    )
                )
            )
        } else {
            null
        }
    )

    @Test
    fun `screen step results never reach the provider`() = runTest {
        val guard = ScreenContentGuard()
        guard.registerScreenRead(secret)
        val provider = CapturingProvider()
        val planner = LlmPlanner(provider, guard)

        val history = listOf(
            message("1", "كم رصيدي؟", role = Role.USER),
            message("2", secret, localOnly = true)
        )
        planner.plan("افتح الإعدادات", history, listOf(NoTools()))

        val outbound = provider.outboundText()
        assertFalse("screen result leaked to the provider", outbound.contains(secret))
        assertFalse(outbound.contains("4521"))
        assertTrue(outbound.contains("افتح الإعدادات"))
    }

    @Test
    fun `screen text in an ordinary message is dropped from the prompt`() = runTest {
        val guard = ScreenContentGuard()
        guard.registerScreenRead(secret)
        val provider = CapturingProvider()
        val planner = LlmPlanner(provider, guard)

        // No plan attached: this is the path a stale summary or pasted line takes.
        val history = listOf(message("1", "قبل قليل ظهر $secret على الشاشة"))
        planner.plan("افتح الإعدادات", history, listOf(NoTools()))

        assertFalse(provider.outboundText().contains("4521"))
    }

    @Test
    fun `a request that repeats screen content is refused before any network call`() = runTest {
        val guard = ScreenContentGuard()
        guard.registerScreenRead(secret)
        val provider = CapturingProvider()
        val planner = LlmPlanner(provider, guard)

        val result = planner.plan("لخّص هذا: $secret", emptyList(), listOf(NoTools()))

        assertTrue(result is PlannerResult.Failed)
        assertEquals(LlmPlanner.SCREEN_CONTENT_BLOCKED, (result as PlannerResult.Failed).message)
        assertNull(provider.lastRequest)
    }

    @Test
    fun `the system prompt never embeds screen text`() = runTest {
        val guard = ScreenContentGuard()
        guard.registerScreenRead(secret)
        val provider = CapturingProvider()
        val planner = LlmPlanner(provider, guard)

        planner.plan("افتح الإعدادات", emptyList(), listOf(NoTools()))

        val systemPrompt = provider.lastRequest!!.systemPrompt
        assertFalse(systemPrompt.contains(secret))
        assertTrue(systemPrompt.contains("open_app"))
    }

    @Test
    fun `without any screen read the conversation is forwarded normally`() = runTest {
        val guard = ScreenContentGuard()
        val provider = CapturingProvider()
        val planner = LlmPlanner(provider, guard)

        planner.plan("افتح الإعدادات", listOf(message("1", "سؤالي السابق", role = Role.USER)), listOf(NoTools()))

        val outbound = provider.outboundText()
        assertTrue(outbound.contains("سؤالي السابق"))
        assertTrue(outbound.contains("افتح الإعدادات"))
    }
}
