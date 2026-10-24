package com.myai.assistant.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.Locale

/**
 * Works with any server exposing the OpenAI Chat Completions shape
 * (`POST {baseUrl}/chat/completions`), including self-hosted gateways.
 *
 * Security notes:
 * * The API key is supplied by the caller (Keystore-decrypted) and only placed
 *   in an Authorization header. It is never logged or persisted here.
 * * Plain HTTP is accepted only for loopback / emulator hosts, matching the
 *   network security config.
 */
class OpenAiCompatibleProvider(
    private val config: ProviderConfig,
    private val transport: HttpTransport
) : AiProvider {

    override val id: String = "openai-compatible"
    override val displayName: String = "محرك متوافق مع OpenAI"

    private val validationError: String? = validateConfig(config)
    override val isConfigured: Boolean get() = validationError == null
    override val configurationHint: String? get() = validationError

    override suspend fun complete(request: CompletionRequest): CompletionResult {
        validationError?.let { throw AiException(it) }

        val payload = buildPayload(request).toString()
        val headers = buildMap {
            put("Content-Type", "application/json")
            config.apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }

        val response = transport.postJson(
            url = endpoint(config.baseUrl),
            headers = headers,
            body = payload,
            timeoutSeconds = config.timeoutSeconds
        )

        if (response.code !in 200..299) {
            throw AiException(httpErrorMessage(response.code, response.body))
        }

        val content = extractContent(response.body)
            ?: throw AiException("استجابة غير مفهومة من المزود (لا يوجد حقل choices[0].message.content).")
        return CompletionResult(content, id)
    }

    private fun buildPayload(request: CompletionRequest): JsonObject {
        val messages = buildList {
            add(JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(request.systemPrompt))))
            request.turns.forEach { turn ->
                add(JsonObject(mapOf("role" to JsonPrimitive(turn.role), "content" to JsonPrimitive(turn.content))))
            }
        }

        val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "model" to JsonPrimitive(config.model),
            "messages" to JsonArray(messages),
            "temperature" to JsonPrimitive(0.2),
            "max_tokens" to JsonPrimitive(request.maxTokens)
        )
        if (config.jsonMode) {
            fields["response_format"] = JsonObject(mapOf("type" to JsonPrimitive("json_object")))
        }
        return JsonObject(fields)
    }

    companion object {

        fun endpoint(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }

        fun validateConfig(config: ProviderConfig): String? {
            val base = config.baseUrl.trim()
            if (base.isEmpty()) return "أدخل عنوان الخادم (Base URL) أولًا."
            val uri = runCatching { URI(base) }.getOrNull()
                ?: return "عنوان الخادم ليس رابطًا صالحًا."
            val scheme = uri.scheme?.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") {
                return "يجب أن يبدأ العنوان بـ https:// (أو http:// لعنوان محلي)."
            }
            if (scheme == "http") {
                val host = uri.host?.lowercase(Locale.US)
                    ?: return "عنوان الخادم لا يحتوي مضيفًا صالحًا."
                if (host !in LOCAL_HOSTS) {
                    return "يُسمح بـ http فقط للعناوين المحلية. استخدم https لبقية الخوادم."
                }
            }
            if (uri.query != null || uri.fragment != null) {
                return "عنوان الخادم يجب ألا يحتوي معاملات استعلام (?) أو جزءًا (#)."
            }
            if (config.model.isBlank()) return "أدخل اسم النموذج."
            return null
        }

        fun httpErrorMessage(code: Int, body: String): String {
            val detail = providerDetail(body)
            val suffix = if (detail.isNullOrBlank()) "" else " (المزود: ${detail.take(180)})"
            return when (code) {
                401, 403 -> "رفض المزود المفتاح أو الصلاحية (HTTP $code). تحقّق من مفتاح API في الإعدادات.$suffix"
                404 -> "لم يجد المزود المسار المطلوب (HTTP 404). تحقّق من عنوان الخادم واسم النموذج.$suffix"
                429 -> "تجاوزت حد الطلبات لدى المزود (HTTP 429). انتظر قليلًا ثم أعد المحاولة.$suffix"
                in 500..599 -> "خطأ مؤقت في خادم المزود (HTTP $code). أعد المحاولة لاحقًا.$suffix"
                else -> "رفض المزود الطلب (HTTP $code).$suffix"
            }
        }

        fun extractContent(body: String): String? {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val choice = runCatching { root["choices"]!!.jsonArray.firstOrNull()?.jsonObject }.getOrNull()
            val messageContent = runCatching {
                choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (!messageContent.isNullOrBlank()) return messageContent
            return runCatching { choice?.get("text")?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }

        private fun providerDetail(body: String): String? {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            return runCatching {
                root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }

        private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "10.0.2.2", "::1")
    }
}
