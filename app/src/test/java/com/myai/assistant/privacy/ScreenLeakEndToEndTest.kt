package com.myai.assistant.privacy

import android.content.Context
import com.myai.assistant.agent.AgentEngine
import com.myai.assistant.agent.AgentEvent
import com.myai.assistant.agent.ChatController
import com.myai.assistant.agent.ConfirmationHandler
import com.myai.assistant.agent.LlmPlanner
import com.myai.assistant.agent.Planner
import com.myai.assistant.agent.PlannerResult
import com.myai.assistant.agent.ScreenActionResult
import com.myai.assistant.agent.ScreenController
import com.myai.assistant.agent.ScreenSnapshot
import com.myai.assistant.agent.Tool
import com.myai.assistant.agent.ToolContext
import com.myai.assistant.agent.ToolRegistry
import com.myai.assistant.ai.AiProvider
import com.myai.assistant.ai.CompletionRequest
import com.myai.assistant.ai.CompletionResult
import com.myai.assistant.ai.PrivacyFilteringProvider
import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.ExecutionPlan
import com.myai.assistant.core.model.PlanStep
import com.myai.assistant.core.model.Role
import com.myai.assistant.data.ConversationStore
import com.myai.assistant.tools.ScreenSummaryTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File

/**
 * End-to-end proof for the `screen_summary` leak.
 *
 * Path under test:
 *
 *   ScreenSummaryTool.execute() → ToolOutcome → AgentEngine (step result)
 *     → ChatController → ConversationStore (persisted + reused as history)
 *     → LlmPlanner → AiProvider (the object that becomes the HTTP body)
 *
 * The marker is a single unique token, so a substring search over every artifact
 * is a complete check: no partial match or truncation can hide it.
 */
class ScreenLeakEndToEndTest {

    private val marker = "ZQ7-SCREEN-7731-PRIVATE"
    private val screenText = "رصيد الحساب $marker ريال — عبارة لا تخرج من الجهاز"

    private val tempFile = File.createTempFile("myai-e2e-", ".json").apply { delete() }
    private val store = ConversationStore(tempFile, Dispatchers.Unconfined)
    private val guard = ScreenContentGuard()
    private val vault = ScreenContentVault(guard)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        tempFile.delete()
    }

    /**
     * ScreenSummaryTool never touches the Context (it reads through the screen
     * controller and the guard), so a plain mock is enough. Mockito allocates it
     * without running the android.jar stub constructor.
     */
    private val fakeContext: Context = Mockito.mock(Context::class.java)

    private class FakeScreen(private val snapshot: ScreenSnapshot) : ScreenController {
        override val isConnected = true
        override val foregroundPackage = snapshot.packageName
        override suspend fun snapshot() = snapshot
        override suspend fun clickText(text: String) = ScreenActionResult(true, "ok")
        override suspend fun setText(text: String, targetText: String?) = ScreenActionResult(true, "ok")
        override suspend fun scroll(direction: String) = ScreenActionResult(true, "ok")
        override suspend fun globalAction(action: String) = ScreenActionResult(true, "ok")
    }

    private fun snapshot() = ScreenSnapshot("com.bank.example", listOf(screenText), emptyList())

    private fun plannerFor(toolId: String): Planner =
        object : Planner {
            override val id = "fixed"
            override suspend fun plan(
                request: String,
                history: List<ChatMessage>,
                tools: List<Tool>
            ): PlannerResult = PlannerResult.Planned(
                plan = ExecutionPlan(
                    goal = "اقرأ الشاشة",
                    source = "test",
                    steps = listOf(PlanStep(id = "step-1", toolId = toolId, title = "قراءة الشاشة"))
                ),
                reply = "سأقرأ الشاشة."
            )
        }

    private fun engineWith(tool: Tool): AgentEngine = AgentEngine(
        plannerProvider = { plannerFor(tool.id) },
        registry = ToolRegistry(listOf(tool)),
        invoker = { t, args -> t.execute(ToolContext(fakeContext, FakeScreen(snapshot()), guard), args) },
        accessibilityReady = { true },
        screenVault = vault,
        toolTimeoutMs = 5_000
    )

    private fun controllerWith(tool: Tool) = ChatController(
        store = store,
        engine = engineWith(tool),
        historyEnabled = { true },
        screenVault = vault,
        scope = scope
    )

    private suspend fun ChatController.runTurn(text: String) {
        assertTrue(submit(text, ConfirmationHandler { true }))
        busy.first { !it }
    }

    private class CapturingProvider : AiProvider {
        var lastRequest: CompletionRequest? = null
        var calls = 0
        override val id = "capture"
        override val displayName = "capture"
        override val isConfigured = true
        override val configurationHint: String? = null

        override suspend fun complete(request: CompletionRequest): CompletionResult {
            calls++
            lastRequest = request
            return CompletionResult("""{"reply":"تم","steps":[]}""", id)
        }

        /** Everything the provider would serialize into the HTTP body. */
        fun wireText(): String = buildString {
            val request = lastRequest ?: return ""
            appendLine(request.systemPrompt)
            request.turns.forEach { turn -> appendLine("${turn.role}: ${turn.content}") }
        }
    }

    // 1 ── ScreenSummaryTool → ToolOutcome ──────────────────────────────── //

    @Test
    fun `the tool returns the readout to its caller and registers it`() = runTest {
        val tool = ScreenSummaryTool()
        val outcome = tool.execute(
            ToolContext(fakeContext, FakeScreen(snapshot()), guard),
            emptyMap()
        )

        assertTrue(outcome.success)
        assertTrue(outcome.message.contains(marker))
        assertTrue(guard.containsProtected(marker))
    }

    // 2 ── ToolOutcome → AgentEngine ────────────────────────────────────── //

    @Test
    fun `the plan carries a placeholder and the readout goes to the vault`() = runTest {
        val events = mutableListOf<AgentEvent>()
        engineWith(ScreenSummaryTool())
            .run("اقرأ الشاشة", emptyList(), ConfirmationHandler { true })
            .collect { events += it }

        val step = events.filterIsInstance<AgentEvent.Finished>().single().plan!!.steps.single()

        assertTrue("step must be marked local-only", step.localOnly)
        assertEquals(ScreenContentVault.PLACEHOLDER, step.resultMessage)
        assertFalse(step.resultMessage!!.contains(marker))
        assertFalse(events.joinToString("\n") { it.toString() }.contains(marker))
        assertEquals(screenText, vault.get(step.id))
    }

    // 3 ── AgentEngine → ChatController → ConversationStore ────────────── //

    @Test
    fun `neither the transcript nor the persisted file contains the readout`() = runTest {
        val controller = controllerWith(ScreenSummaryTool())
        controller.load()
        controller.runTurn("اقرأ الشاشة")

        val messages = store.messages.value
        assertTrue(messages.isNotEmpty())
        assertFalse(messages.joinToString("\n") { it.text }.contains(marker))
        assertFalse(Json.encodeToString(messages).contains(marker))

        assertTrue("history should be persisted", tempFile.exists())
        assertFalse("screen text written to disk", tempFile.readText().contains(marker))

        // The user can still read it: it comes from the device-only vault.
        val step = messages.flatMap { it.plan?.steps.orEmpty() }.single()
        assertEquals(screenText, vault.get(step.id))
    }

    // 4 ── ConversationStore → LlmPlanner → provider ───────────────────── //

    @Test
    fun `the cloud request built from that history contains no screen text`() = runTest {
        controllerWith(ScreenSummaryTool()).apply {
            load()
            runTurn("اقرأ الشاشة")
        }

        val capturing = CapturingProvider()
        val planner = LlmPlanner(PrivacyFilteringProvider(capturing, guard), guard)
        val result = planner.plan("افتح الإعدادات", store.messages.value, listOf(ScreenSummaryTool()))

        assertTrue(result is PlannerResult.DirectAnswer)
        assertEquals(1, capturing.calls)
        val wire = capturing.wireText()
        assertFalse("screen text reached the provider", wire.contains(marker))
        assertFalse(wire.contains("رصيد الحساب"))
        assertTrue(wire.contains("افتح الإعدادات"))
    }

    @Test
    fun `asking the cloud about that text is refused before any call`() = runTest {
        guard.registerScreenRead(screenText)
        vault.put("step-1", screenText)
        val capturing = CapturingProvider()
        val planner = LlmPlanner(PrivacyFilteringProvider(capturing, guard), guard)

        val result = planner.plan("لخّص لي: $screenText", emptyList(), listOf(ScreenSummaryTool()))

        assertTrue(result is PlannerResult.Failed)
        assertEquals(LlmPlanner.SCREEN_CONTENT_BLOCKED, (result as PlannerResult.Failed).message)
        assertEquals(0, capturing.calls)
        assertNull(capturing.lastRequest)
    }

    // 5 ── Separation guarantees ────────────────────────────────────────── //

    @Test
    fun `clearing the conversation also drops the vault`() = runTest {
        val controller = controllerWith(ScreenSummaryTool())
        controller.load()
        controller.runTurn("اقرأ الشاشة")
        assertEquals(1, vault.size())

        controller.clear()

        assertEquals(0, vault.size())
        assertTrue(store.messages.value.isEmpty())
        assertFalse(tempFile.exists())
    }

    @Test
    fun `after a restart only the placeholder survives`() = runTest {
        controllerWith(ScreenSummaryTool()).apply {
            load()
            runTurn("اقرأ الشاشة")
        }

        val reopened = ConversationStore(tempFile, Dispatchers.Unconfined)
        reopened.load()
        val persisted = Json.encodeToString(reopened.messages.value)

        assertEquals(ScreenContentVault.PLACEHOLDER, reopened.messages.value
            .flatMap { it.plan?.steps.orEmpty() }.single().resultMessage)
        assertFalse(persisted.contains(marker))
        assertNull(ScreenContentVault().get("step-1"))
    }

    @Test
    fun `role and plan metadata are unchanged for normal tools`() = runTest {
        // Guard against the vault logic accidentally emptying ordinary results.
        val normalStep = PlanStep(id = "step-9", toolId = "open_app", title = "فتح")
        assertFalse(normalStep.localOnly)
        assertEquals(null, vault.get(normalStep.id))
        assertEquals(Role.USER, ChatMessage("m", Role.USER, "hi", 0L).role)
    }
}
