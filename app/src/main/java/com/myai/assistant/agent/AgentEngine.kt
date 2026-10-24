package com.myai.assistant.agent

import com.myai.assistant.core.model.ExecutionPlan
import com.myai.assistant.core.model.StepStatus
import com.myai.assistant.core.model.Verification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull

sealed interface AgentEvent {
    data object Thinking : AgentEvent
    data class PlanCreated(val plan: ExecutionPlan, val reply: String) : AgentEvent
    data class StepUpdated(val plan: ExecutionPlan, val index: Int) : AgentEvent
    data class ConfirmationRequired(val plan: ExecutionPlan, val index: Int, val toolTitle: String) : AgentEvent
    data class Reply(val text: String) : AgentEvent
    data class Error(val message: String) : AgentEvent
    data class Finished(val plan: ExecutionPlan?) : AgentEvent
}

fun interface ConfirmationHandler {
    suspend fun confirm(request: ConfirmationRequest): Boolean
}

data class ConfirmationRequest(
    val toolTitle: String,
    val stepTitle: String,
    val details: String,
    val sensitive: Boolean = true
)

/**
 * Executes a plan step by step.
 *
 * Guarantees:
 * * A step is only executed after its required parameters were validated by the planner/parser.
 * * Sensitive steps require explicit user confirmation (configurable, default on).
 * * Every step reports a verification level; a failed step aborts the rest.
 * * Nothing is retried silently, so no duplicate SMS/calls can happen.
 */
class AgentEngine(
    private val plannerProvider: () -> Planner,
    private val registry: ToolRegistry,
    private val invoker: suspend (Tool, Map<String, String>) -> ToolOutcome,
    private val accessibilityReady: () -> Boolean,
    private val requireConfirmation: () -> Boolean,
    private val toolTimeoutMs: Long = 20_000L
) {

    fun run(
        request: String,
        history: List<com.myai.assistant.core.model.ChatMessage>,
        confirmer: ConfirmationHandler
    ): Flow<AgentEvent> = flow {
        emit(AgentEvent.Thinking)

        val tools = registry.all()
        when (val planned = plannerProvider().plan(request, history, tools)) {
            is PlannerResult.DirectAnswer -> {
                emit(AgentEvent.Reply(planned.reply))
                emit(AgentEvent.Finished(null))
            }

            is PlannerResult.Failed -> {
                emit(AgentEvent.Error(planned.message))
                emit(AgentEvent.Finished(null))
            }

            is PlannerResult.Planned -> {
                var plan = planned.plan
                emit(AgentEvent.PlanCreated(plan, planned.reply))
                var aborted = false

                for (index in plan.steps.indices) {
                    if (aborted) {
                        plan = plan.withStep(index) {
                            it.copy(
                                status = StepStatus.SKIPPED,
                                resultMessage = "لم تُنفَّذ بسبب فشل أو إلغاء خطوة سابقة."
                            )
                        }
                        emit(AgentEvent.StepUpdated(plan, index))
                        continue
                    }

                    val step = plan.steps[index]
                    val tool = registry.get(step.toolId)
                    if (tool == null) {
                        plan = plan.withStep(index) {
                            it.copy(
                                status = StepStatus.FAILED,
                                verification = Verification.FAILED,
                                resultMessage = "أداة غير معروفة: ${step.toolId}"
                            )
                        }
                        emit(AgentEvent.StepUpdated(plan, index))
                        aborted = true
                        continue
                    }

                    if (tool.requiresAccessibility && !accessibilityReady()) {
                        plan = plan.withStep(index) {
                            it.copy(
                                status = StepStatus.FAILED,
                                verification = Verification.FAILED,
                                resultMessage = "هذه الخطوة تحتاج خدمة الوصولية. فعّلها من مركز الأذونات ثم أعد المحاولة."
                            )
                        }
                        emit(AgentEvent.StepUpdated(plan, index))
                        aborted = true
                        continue
                    }

                    if (tool.risk == RiskLevel.SENSITIVE && requireConfirmation()) {
                        emit(AgentEvent.ConfirmationRequired(plan, index, tool.title))
                        val approved = try {
                            confirmer.confirm(
                                ConfirmationRequest(
                                    toolTitle = tool.title,
                                    stepTitle = step.title,
                                    details = tool.summary(step.args)
                                )
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                        if (!approved) {
                            plan = plan.withStep(index) {
                                it.copy(
                                    status = StepStatus.SKIPPED,
                                    resultMessage = "أُلغيت الخطوة من المستخدم."
                                )
                            }
                            emit(AgentEvent.StepUpdated(plan, index))
                            aborted = true
                            continue
                        }
                    }

                    plan = plan.withStep(index) { it.copy(status = StepStatus.RUNNING) }
                    emit(AgentEvent.StepUpdated(plan, index))

                    val startedAt = System.currentTimeMillis()
                    val outcome = execute(tool, step.args)
                    val duration = System.currentTimeMillis() - startedAt

                    plan = plan.withStep(index) {
                        it.copy(
                            status = if (outcome.success) StepStatus.SUCCEEDED else StepStatus.FAILED,
                            verification = outcome.verification,
                            resultMessage = outcome.message,
                            durationMs = duration
                        )
                    }
                    emit(AgentEvent.StepUpdated(plan, index))

                    if (!outcome.success) aborted = true
                }

                emit(AgentEvent.Finished(plan))
                emit(AgentEvent.Reply(buildSummary(planned.reply, plan)))
            }
        }
    }
        .catch { error ->
            val message = when (error) {
                is CancellationException -> throw error
                is com.myai.assistant.ai.AiException -> error.message ?: "فشل محرك الذكاء الاصطناعي."
                else -> "تعذّر إكمال الطلب: ${error.message ?: error.javaClass.simpleName}"
            }
            emit(AgentEvent.Error(message))
            emit(AgentEvent.Finished(null))
        }
        .flowOn(Dispatchers.IO)

    private suspend fun execute(tool: Tool, args: Map<String, String>): ToolOutcome {
        return try {
            withTimeoutOrNull(toolTimeoutMs) { invoker(tool, args) }
                ?: ToolOutcome.failed("انتهت مهلة تنفيذ الأداة بعد ${toolTimeoutMs / 1000} ثانية.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolInputException) {
            ToolOutcome.failed(e.message ?: "معاملات غير صالحة.")
        } catch (e: ToolUnavailableException) {
            ToolOutcome.failed(e.message ?: "الأداة غير متاحة.")
        } catch (e: SecurityException) {
            ToolOutcome.failed("رفض Android هذا الإجراء لأسباب أمنية: ${e.message ?: "بدون تفاصيل"}")
        } catch (e: Exception) {
            ToolOutcome.failed("فشل تنفيذ الأداة: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun buildSummary(plannerReply: String, plan: ExecutionPlan): String {
        val total = plan.steps.size
        val succeeded = plan.steps.count { it.status == StepStatus.SUCCEEDED }
        val skipped = plan.steps.count { it.status == StepStatus.SKIPPED }
        val verified = plan.steps.count {
            it.status == StepStatus.SUCCEEDED && it.verification == Verification.VERIFIED
        }
        val dispatched = plan.steps.count {
            it.status == StepStatus.SUCCEEDED && it.verification == Verification.DISPATCHED
        }
        val failed = plan.steps.firstOrNull { it.status == StepStatus.FAILED }

        val summary = buildString {
            if (failed == null && skipped == 0) {
                append("تم تنفيذ الخطة بالكامل ($succeeded/$total).")
            } else if (failed == null) {
                append("نُفّذت $succeeded من $total خطوات، وتُخطّيت $skipped.")
            } else {
                append("توقّفت الخطة عند خطوة فاشلة ($succeeded/$total ناجحة).")
                failed.resultMessage?.let { append(" السبب: $it") }
            }
            if (verified > 0) append(" تحقّقنا من $verified خطوة.")
            if (dispatched > 0) append(" $dispatched خطوة أُرسلت إلى النظام دون إمكانية تحقّق.")
        }

        return if (plannerReply.isBlank()) summary else "${plannerReply.trim()}\n\n$summary"
    }
}
