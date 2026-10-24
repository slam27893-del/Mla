package com.myai.assistant.ai

import com.myai.assistant.data.AppSettings
import com.myai.assistant.data.EngineType

data class ChatTurn(val role: String, val content: String)

data class CompletionRequest(
    val systemPrompt: String,
    val turns: List<ChatTurn>,
    val maxTokens: Int = 800
)

data class CompletionResult(val text: String, val providerId: String)

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Extension point for any AI backend. Implement this interface to add a new
 * remote provider or an on-device model; the planner and the UI need no change.
 * See docs/AI_PROVIDERS.md.
 */
interface AiProvider {
    val id: String
    val displayName: String
    val isConfigured: Boolean
    val configurationHint: String?
    suspend fun complete(request: CompletionRequest): CompletionResult
}

data class ProviderConfig(
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String? = null,
    val jsonMode: Boolean = true,
    val maxTokens: Int = 800,
    val timeoutSeconds: Int = 60
)

/**
 * On-device inference contract. No engine is bundled in v1 (the app does not
 * ship a multi-gigabyte model), but this interface is the documented place to
 * plug MediaPipe LLM Inference or llama.cpp without touching the UI.
 */
interface LocalInferenceEngine {
    val isAvailable: Boolean
    val unavailableReason: String
    suspend fun generate(request: CompletionRequest): String
}

class LocalModelProvider(
    private val engine: LocalInferenceEngine = UnavailableLocalEngine
) : AiProvider {
    override val id: String = "local-model"
    override val displayName: String = "نموذج محلي على الجهاز"
    override val isConfigured: Boolean get() = engine.isAvailable
    override val configurationHint: String? get() = if (engine.isAvailable) null else engine.unavailableReason

    override suspend fun complete(request: CompletionRequest): CompletionResult {
        if (!engine.isAvailable) throw AiException(engine.unavailableReason)
        return CompletionResult(engine.generate(request), id)
    }
}

object UnavailableLocalEngine : LocalInferenceEngine {
    override val isAvailable: Boolean = false
    override val unavailableReason: String =
        "لم يُضمَّن محرك استنتاج محلي في هذا الإصدار. راجع docs/AI_PROVIDERS.md لإضافة MediaPipe LLM أو llama.cpp عبر واجهة LocalInferenceEngine."
}
