package com.myai.assistant.privacy

import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.Role

/**
 * Decides which transcript messages may be sent to a *remote* AI provider.
 *
 * Invariant: a message whose plan executed a local-only (screen) tool is never
 * part of a remote request — not the step arguments, not the step output, not a
 * summary built from them. Messages that merely contain a registered screen
 * fragment in their text are dropped too, which covers paths that are not a
 * [com.myai.assistant.core.model.PlanStep] at all (errors, summaries, pasted text).
 *
 * Local storage is untouched: the transcript keeps everything, only the outbound
 * history is filtered.
 */
object RemoteHistoryFilter {

    const val MAX_TURNS = 6
    const val MAX_CHARS = 1200

    /**
     * Transcripts written before [com.myai.assistant.core.model.PlanStep.localOnly]
     * existed are treated the same way: the tool id is the legacy marker.
     */
    private val LEGACY_SCREEN_TOOL_IDS = setOf("screen_summary")

    fun allowsRemote(message: ChatMessage): Boolean = when {
        message.role == Role.SYSTEM -> false
        message.text.isBlank() -> false
        message.containsLocalOnlyContent -> false
        message.plan?.steps?.any { it.toolId in LEGACY_SCREEN_TOOL_IDS } == true -> false
        else -> true
    }

    /**
     * Newest [limit] messages that are safe to send. A message that repeats screen
     * content is dropped rather than redacted: a half-scrubbed prompt is worse for
     * both privacy and plan quality than one less turn of context.
     */
    fun forRemote(
        messages: List<ChatMessage>,
        guard: ScreenContentGuard,
        limit: Int = MAX_TURNS
    ): List<ChatMessage> = messages
        .filter { allowsRemote(it) && !guard.containsProtected(it.text) }
        .takeLast(limit)
}
