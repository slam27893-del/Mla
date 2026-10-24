package com.myai.assistant.agent

import com.myai.assistant.core.model.ChatMessage

/**
 * Tries the AI planner first, then the deterministic offline planner. The
 * fallback is explicit in the reply so the user always knows which engine
 * produced the plan.
 */
class FallbackPlanner(
    private val primary: Planner,
    private val fallback: Planner
) : Planner {

    override val id: String = "fallback(${primary.id}, ${fallback.id})"

    override suspend fun plan(
        request: String,
        history: List<ChatMessage>,
        tools: List<Tool>
    ): PlannerResult {
        val primaryResult = primary.plan(request, history, tools)
        if (primaryResult !is PlannerResult.Failed) return primaryResult

        return when (val offline = fallback.plan(request, history, tools)) {
            is PlannerResult.Planned -> offline.copy(
                plan = offline.plan.copy(source = "offline-fallback"),
                reply = "تعذّر المحرك الذكي (${primaryResult.message})؛ استخدمت المحرك المحلي. ${offline.reply}"
            )
            is PlannerResult.DirectAnswer -> offline
            is PlannerResult.Failed -> primaryResult
        }
    }
}
