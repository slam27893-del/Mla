package com.myai.assistant.ai

import com.myai.assistant.draft.DraftJsonParser
import com.myai.assistant.draft.DraftPrompt
import com.myai.assistant.draft.VisionDraft

/**
 * Sends **one** screenshot to the user's own vision-capable endpoint.
 *
 * This class is intentionally outside [com.myai.assistant.ai.PrivacyFilteringProvider]:
 * that decorator exists to block *text* read from the accessibility tree, and its
 * contract (screen text never leaves the device) must stay intact. The draft path
 * is the opposite by design — an image the user explicitly agreed to send — so it
 * lives here, behind its own consent switch and its own tests.
 *
 * Guarantees enforced in code and covered by tests:
 * * [read] throws unless `consentGranted` is true; there is no other way in.
 * * The payload is built only by [DraftPrompt.buildVisionPayload]: fixed system
 *   prompt + fixed instruction + exactly one image. No history, no vault, no text.
 * * The key is only ever an Authorization header, never in the body.
 */
class DraftVisionClient(
    private val config: ProviderConfig,
    private val transport: HttpTransport = OkHttpTransport()
) {

    val model: String get() = config.model.ifBlank { "—" }

    /** Human readable reason why vision is unavailable, or null when it is ready. */
    fun unavailableReason(): String? {
        val error = OpenAiCompatibleProvider.validateConfig(config)
        if (error != null) return error
        return null
    }

    suspend fun read(jpegBase64: String, consentGranted: Boolean): VisionDraft {
        if (!consentGranted) throw AiException("لا يمكن إرسال لقطة الشاشة دون موافقة صريحة.")
        unavailableReason()?.let { throw AiException(it) }

        val headers = buildMap {
            put("Content-Type", "application/json")
            config.apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        val response = transport.postJson(
            url = OpenAiCompatibleProvider.endpoint(config.baseUrl),
            headers = headers,
            body = DraftPrompt.buildVisionPayload(config.model, jpegBase64),
            timeoutSeconds = config.timeoutSeconds
        )
        if (response.code !in 200..299) {
            throw AiException(OpenAiCompatibleProvider.httpErrorMessage(response.code, response.body))
        }
        val content = OpenAiCompatibleProvider.extractContent(response.body)
            ?: throw AiException("استجابة غير مفهومة من نموذج الرؤية.")
        return DraftJsonParser.parse(content)
    }
}
