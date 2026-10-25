package com.myai.assistant.ai

import com.myai.assistant.privacy.ScreenContentGuard

/**
 * Last line of defence around any [AiProvider].
 *
 * Even if a future caller forgets the planner-side filter, screen text that the
 * guard has seen cannot reach the network: the system prompt and every turn are
 * scrubbed before the delegate builds its payload, and if anything still matches
 * after scrubbing the call is refused instead of being sent.
 *
 * Kept as a decorator (rather than logic inside the HTTP provider) so every
 * provider — present or future — inherits the same guarantee, and so it can be
 * unit-tested with a fake delegate and no network.
 */
class PrivacyFilteringProvider(
    private val delegate: AiProvider,
    private val guard: ScreenContentGuard
) : AiProvider {

    override val id: String get() = delegate.id
    override val displayName: String get() = delegate.displayName
    override val isConfigured: Boolean get() = delegate.isConfigured
    override val configurationHint: String? get() = delegate.configurationHint

    override suspend fun complete(request: CompletionRequest): CompletionResult {
        val scrubbed = CompletionRequest(
            systemPrompt = guard.redact(request.systemPrompt),
            turns = request.turns.map { turn -> turn.copy(content = guard.redact(turn.content)) },
            maxTokens = request.maxTokens
        )
        val leftover = (sequenceOf(scrubbed.systemPrompt) + scrubbed.turns.asSequence().map { it.content })
            .any { guard.containsProtected(it) }
        if (leftover) throw AiException(BLOCKED_MESSAGE)
        return delegate.complete(scrubbed)
    }

    companion object {
        const val BLOCKED_MESSAGE =
            "حُجب الطلب: يحتوي نصًا مقروءًا من الشاشة، ولا يُسمح بإرساله إلى مزوّد خارجي."
    }
}
