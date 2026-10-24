package com.myai.assistant.agent

import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.ExecutionPlan
import com.myai.assistant.core.model.PlanStep
import com.myai.assistant.core.model.StepStatus

interface Planner {
    val id: String
    suspend fun plan(request: String, history: List<ChatMessage>, tools: List<Tool>): PlannerResult
}

sealed interface PlannerResult {
    data class Planned(val plan: ExecutionPlan, val reply: String) : PlannerResult
    data class DirectAnswer(val reply: String) : PlannerResult
    data class Failed(val message: String, cause: Throwable? = null) : PlannerResult
}

fun planOf(goal: String, source: String, vararg steps: PlanStep): ExecutionPlan =
    ExecutionPlan(goal = goal, source = source, steps = steps.mapIndexed { index, step ->
        step.copy(id = "step-${index + 1}", status = StepStatus.PENDING)
    })
