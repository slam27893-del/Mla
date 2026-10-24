package com.myai.assistant.core.model

import kotlinx.serialization.Serializable

/** Who produced a message. */
@Serializable
enum class Role { USER, ASSISTANT, SYSTEM }

/** Visual/behavioural kind of a message. */
@Serializable
enum class MessageKind { TEXT, PLAN, ERROR, INFO }

/** Lifecycle of a single planned step. */
@Serializable
enum class StepStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }

/**
 * How strongly a step result was verified:
 * - VERIFIED: the app observed the effect (foreground window, clipboard, torch call, screen text...).
 * - DISPATCHED: an official Android intent was handed to the system, no read-back was possible.
 * - UNVERIFIED: executed with no verification path.
 * - FAILED: execution failed.
 */
@Serializable
enum class Verification { VERIFIED, DISPATCHED, UNVERIFIED, FAILED }

@Serializable
data class PlanStep(
    val id: String,
    val toolId: String,
    val title: String,
    val args: Map<String, String> = emptyMap(),
    val status: StepStatus = StepStatus.PENDING,
    val verification: Verification = Verification.UNVERIFIED,
    val resultMessage: String? = null,
    val durationMs: Long? = null,
    /**
     * True when this step's input or output came from the Accessibility screen
     * reader. Local-only steps are kept in the transcript but are never sent to a
     * remote AI provider (see [com.myai.assistant.privacy.RemoteHistoryFilter]).
     */
    val localOnly: Boolean = false
)

@Serializable
data class ExecutionPlan(
    val goal: String,
    val source: String,
    val steps: List<PlanStep> = emptyList()
) {
    fun withStep(index: Int, transform: (PlanStep) -> PlanStep): ExecutionPlan =
        copy(steps = steps.mapIndexed { i, step -> if (i == index) transform(step) else step })
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val timestamp: Long,
    val kind: MessageKind = MessageKind.TEXT,
    val plan: ExecutionPlan? = null,
    val pending: Boolean = false
) {
    val containsLocalOnlyContent: Boolean
        get() = plan?.steps?.any { it.localOnly } == true
}
