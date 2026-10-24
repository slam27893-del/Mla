package com.myai.assistant.agent

import android.os.Build
import com.myai.assistant.ai.AiException
import com.myai.assistant.ai.AiProvider
import com.myai.assistant.ai.ChatTurn
import com.myai.assistant.ai.CompletionRequest
import com.myai.assistant.ai.Prompts
import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.privacy.RemoteHistoryFilter
import com.myai.assistant.privacy.ScreenContentGuard
import com.myai.assistant.util.TimeFormatter
import kotlinx.coroutines.CancellationException

/**
 * Remote planner.
 *
 * Privacy contract (enforced here, not only at the provider):
 * 1. History is filtered by [RemoteHistoryFilter], so a turn that executed a
 *    screen-reading tool — or that merely repeats text read from the screen — is
 *    never sent, even though it stays in the local transcript.
 * 2. A request that itself contains screen content is refused instead of being
 *    forwarded; the user gets an explicit Arabic explanation.
 * 3. Whatever remains is passed through [ScreenContentGuard.redact] as a second
 *    layer, and the provider is wrapped again in
 *    [com.myai.assistant.ai.PrivacyFilteringProvider].
 */
class LlmPlanner(
    private val provider: AiProvider,
    private val privacy: ScreenContentGuard = ScreenContentGuard()
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

        if (privacy.containsProtected(request)) {
            return PlannerResult.Failed(SCREEN_CONTENT_BLOCKED)
        }

        val systemPrompt = privacy.redact(
            Prompts.systemPrompt(
                tools = tools,
                deviceModel = Build.MODEL ?: "Android",
                now = TimeFormatter.fullNow()
            )
        )

        val turns = RemoteHistoryFilter
            .forRemote(history, privacy)
            .map {
                ChatTurn(
                    role = if (it.role == com.myai.assistant.core.model.Role.USER) "user" else "assistant",
                    content = privacy.redact(it.text).take(RemoteHistoryFilter.MAX_CHARS)
                )
            }
            .toMutableList()
        turns += ChatTurn("user", privacy.redact(request))

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

    companion object {
        const val SCREEN_CONTENT_BLOCKED =
            "لا أستطيع إرسال محتوى الشاشة أو أي نص قرأته خدمة الوصولية إلى مزوّد سحابي. " +
                "اطلب مني قراءة الشاشة وسأعرض النتيجة هنا على جهازك فقط، أو استخدم المحرك المحلي."
    }
}
