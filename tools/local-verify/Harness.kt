import android.content.Context
import com.myai.assistant.agent.AgentEngine
import com.myai.assistant.agent.AgentEvent
import com.myai.assistant.agent.ConfirmationHandler
import com.myai.assistant.agent.ConfirmationRequest
import com.myai.assistant.agent.OutputPrivacy
import com.myai.assistant.agent.Planner
import com.myai.assistant.agent.PlannerResult
import com.myai.assistant.agent.RiskLevel
import com.myai.assistant.agent.ScreenActionResult
import com.myai.assistant.agent.ScreenController
import com.myai.assistant.agent.ScreenSnapshot
import com.myai.assistant.agent.Tool
import com.myai.assistant.agent.ToolContext
import com.myai.assistant.agent.ToolParameter
import com.myai.assistant.agent.ToolRegistry
import com.myai.assistant.ai.AiProvider
import com.myai.assistant.ai.CompletionRequest
import com.myai.assistant.ai.CompletionResult
import com.myai.assistant.ai.PrivacyFilteringProvider
import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.ExecutionPlan
import com.myai.assistant.core.model.PlanStep
import com.myai.assistant.core.model.Role
import com.myai.assistant.privacy.RemoteHistoryFilter
import com.myai.assistant.privacy.ScreenContentGuard
import com.myai.assistant.privacy.ScreenContentVault
import com.myai.assistant.tools.ScreenSummaryTool
import kotlinx.coroutines.flow.FlowCollector
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Executes the REAL MyAI privacy classes (no copies) on a plain JVM.
 * The only non-production code is: android/coroutines/serialization stubs and the
 * fakes below, which the unit tests also use.
 */

const val MARKER = "ZQ7-SCREEN-7731-PRIVATE"

var passed = 0
var failed = 0

fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
        passed++
        println("PASS  $name")
    } else {
        failed++
        println("FAIL  $name${if (detail.isEmpty()) "" else "  → $detail"}")
    }
}

fun <T> runNow(block: suspend () -> T): T {
    var box: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { box = it })
    while (box == null) Thread.sleep(1)
    return box!!.getOrThrow()
}

class FakeContext : Context() {
    override fun getApplicationContext(): Context = this
}

class FakeScreen(private val state: ScreenSnapshot) : ScreenController {
    override val isConnected = true
    override val foregroundPackage = state.packageName
    override suspend fun snapshot(): ScreenSnapshot = state
    override suspend fun clickText(text: String) = ScreenActionResult(true, "ok")
    override suspend fun setText(text: String, targetText: String?) = ScreenActionResult(true, "ok")
    override suspend fun scroll(direction: String) = ScreenActionResult(true, "ok")
    override suspend fun globalAction(action: String) = ScreenActionResult(true, "ok")
}

class CaptureCollector<T> : FlowCollector<T> {
    val items = mutableListOf<T>()
    override suspend fun emit(value: T) { items += value }
}

class CapturingProvider : AiProvider {
    var calls = 0
    var last: CompletionRequest? = null
    override val id = "capture"
    override val displayName = "capture"
    override val isConfigured = true
    override val configurationHint: String? = null
    override suspend fun complete(request: CompletionRequest): CompletionResult {
        calls++
        last = request
        return CompletionResult("""{"reply":"تم","steps":[]}""", id)
    }
    fun wireText(): String = buildString {
        val r = last ?: return ""
        appendLine(r.systemPrompt)
        r.turns.forEach { appendLine("${it.role}: ${it.content}") }
    }
}

fun plannerFor(toolId: String): Planner = object : Planner {
    override val id = "fixed"
    override suspend fun plan(request: String, history: List<ChatMessage>, tools: List<Tool>): PlannerResult =
        PlannerResult.Planned(
            plan = ExecutionPlan(
                goal = "اقرأ الشاشة",
                source = "test",
                steps = listOf(PlanStep(id = "step-1", toolId = toolId, title = "قراءة الشاشة"))
            ),
            reply = "سأقرأ الشاشة."
        )
}

fun main() {
    val screenText = "رصيد الحساب $MARKER ريال — عبارة لا تخرج من الجهاز"
    val guard = ScreenContentGuard()
    val vault = ScreenContentVault(guard)
    val context = FakeContext()
    val screen = FakeScreen(ScreenSnapshot("com.bank.example", listOf(screenText), emptyList()))
    val tool = ScreenSummaryTool()

    println("== production classes under test ==")
    println("  " + tool::class.java.name + "  outputPrivacy=" + tool.outputPrivacy)
    println()

    // ---- 1. ScreenSummaryTool.execute() → ToolOutcome -------------------
    val outcome = runNow { tool.execute(ToolContext(context, screen, guard), emptyMap()) }
    check("tool returns the readout in ToolOutcome", outcome.success && outcome.message.contains(MARKER))
    check("guard registered the readout", guard.containsProtected(MARKER))

    // ---- 2. AgentEngine: plan carries a placeholder, vault holds the text
    val engine = AgentEngine(
        plannerProvider = { plannerFor(tool.id) },
        registry = ToolRegistry(listOf(tool)),
        invoker = { t, args -> t.execute(ToolContext(context, screen, guard), args) },
        accessibilityReady = { true },
        screenVault = vault,
        toolTimeoutMs = 5_000
    )
    val events = CaptureCollector<AgentEvent>()
    runNow { engine.run("اقرأ الشاشة", emptyList(), ConfirmationHandler { true }).collect(events) }

    val plan = events.items.filterIsInstance<AgentEvent.Finished>().single().plan!!
    val step = plan.steps.single()
    check("step is marked localOnly", step.localOnly)
    check("plan carries the placeholder", step.resultMessage == ScreenContentVault.PLACEHOLDER,
        step.resultMessage.orEmpty())
    check("no marker in any emitted event", !events.items.joinToString("\n") { it.toString() }.contains(MARKER))
    check("vault holds the tool output", vault.get(step.id) == outcome.message,
        "vault=" + vault.get(step.id)?.take(60))

    // ---- 3. The message ChatController would store ---------------------
    val message = ChatMessage(
        id = "msg-1",
        role = Role.ASSISTANT,
        text = events.items.filterIsInstance<AgentEvent.Reply>().single().text,
        timestamp = 0L,
        plan = plan
    )
    val messageDump = message.toString()
    check("ChatMessage.toString carries no marker", !messageDump.contains(MARKER))
    check("ChatMessage still names the tool and step", messageDump.contains("screen_summary"))

    // ---- 4. RemoteHistoryFilter ----------------------------------------
    val sent = RemoteHistoryFilter.forRemote(listOf(message), guard)
    check("local-only turn is excluded from remote history", sent.isEmpty(), "size=${sent.size}")

    // ---- 5. PrivacyFilteringProvider -----------------------------------
    val capturing = CapturingProvider()
    val provider = PrivacyFilteringProvider(capturing, guard)
    runNow {
        provider.complete(
            CompletionRequest(
                systemPrompt = "أنت مساعد. الشاشة: $screenText",
                turns = listOf(com.myai.assistant.ai.ChatTurn("user", "افتح الإعدادات"))
            )
        )
    }
    check("provider scrubbed the prompt before sending", !capturing.wireText().contains(MARKER))
    check("provider kept ordinary text", capturing.wireText().contains("افتح الإعدادات"))
    check("scrubbed text is marked", capturing.wireText().contains(ScreenContentGuard.REDACTED))

    // ---- 6. vault hygiene ----------------------------------------------
    vault.clear()
    check("clear() empties the vault", vault.size() == 0 && vault.get(step.id) == null)

    // ---- 7. negative control: the harness CAN see the old behaviour -----
    // A pre-fix message (raw text inside the plan, no localOnly flag) with a FRESH
    // guard: this is what the test suite must catch, so the check must fail here.
    val freshGuard = ScreenContentGuard()
    val plainLeakyMessage = message.copy(
        text = "ظهر على الشاشة: $screenText",
        plan = plan.copy(steps = listOf(step.copy(toolId = "open_app", localOnly = false, resultMessage = "تم")))
    )
    val leaked = RemoteHistoryFilter.forRemote(listOf(plainLeakyMessage), freshGuard)
    check("control: unknown text IS forwarded (harness can detect leaks)",
        leaked.size == 1 && leaked.single().toString().contains(MARKER))
    val afterRegistration = RemoteHistoryFilter.forRemote(listOf(plainLeakyMessage), ScreenContentGuard().also { it.registerScreenRead(screenText) })
    check("control: same text is blocked once the guard knows it", afterRegistration.isEmpty())

    // ---- 8. mirror the assertions of the committed test files -------------
    val g2 = ScreenContentGuard()
    g2.registerScreenRead(screenText)
    check("partial quote of a readout is detected", g2.containsProtected("الرقم هو $MARKER فقط"))
    check("partial quote is redacted", g2.redact(MARKER) == ScreenContentGuard.REDACTED)

    val g3 = ScreenContentGuard()
    g3.registerScreenRead("إرسال\nرجوع\nحفظ")
    check("generic button labels are not registered", g3.protectedFragmentCount() == 0,
        "count=${g3.protectedFragmentCount()}")
    check("generic labels do not block normal text", !g3.containsProtected("أرسل رسالة إلى أحمد"))

    val g4 = ScreenContentGuard()
    val formatted = com.myai.assistant.tools.ScreenSummaryFormatter.format(
        ScreenSnapshot("com.bank.example", listOf(screenText), emptyList()), g4
    )
    check("formatter output is itself protected", g4.containsProtected(formatted))
    check("formatter output carries the marker", formatted.contains(MARKER))

    val g5 = ScreenContentGuard()
    repeat(600) { g5.registerScreenRead("سطر طويل خاص بالشاشة رقم 1000$it لا يُنسى") }
    check("guard memory stays bounded", g5.protectedFragmentCount() <= 400, "count=${g5.protectedFragmentCount()}")

    println()
    println("RESULT  passed=$passed failed=$failed")
    if (failed > 0) System.exit(1)
}
