package com.myai.assistant.agent

import android.os.Build
import com.myai.assistant.ai.AiException
import com.myai.assistant.ai.AiProvider
import com.myai.assistant.ai.ChatTurn
import com.myai.assistant.ai.CompletionRequest
import com.myai.assistant.ai.Prompts
import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.Role
import com.myai.assistant.util.TimeFormatter
import kotlinx.coroutines.CancellationException

class LlmPlanner(
    private val provider: AiProvider
) : Planner {

    override val id: String = "llm"

    override suspend fun plan(
        request: String,
        history: List<ChatMessage>,
        tools: List<Tool>
    ): PlannerResult {
        if (!provider.isConfigured) {
            return PlannerResult.Failed(provider.configurationHint ?: "محرك الذكاء الاصطناعي غير مهيّأ.")
        }

        val systemPrompt = Prompts.systemPrompt(
            tools = tools,
            deviceModel = Build.MODEL ?: "Android",
            now = TimeFormatter.fullNow()
        )

        val turns = history
            .takeLast(6)
            .filter { it.text.isNotBlank() && it.role != Role.SYSTEM }
            .map {
                ChatTurn(
                    role = if (it.role == Role.USER) "user" else "assistant",
                    content = it.text.take(1200)
                )
            }
            .toMutableList()
        turns += ChatTurn("user", request)

        return try {
            val completion = provider.complete(CompletionRequest(systemPrompt, turns))
            PlanParser.parse(completion.text, tools, request, source = completion.providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            PlannerResult.Failed(e.message ?: "فشل الاتصال بمحرك الذكاء الاصطناعي.", e)
        } catch (e: Exception) {
            PlannerResult.Failed("فشل محرك الذكاء الاصطناعي: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}
