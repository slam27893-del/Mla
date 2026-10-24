package com.myai.assistant.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleProviderTest {

    private class FakeTransport(
        private val code: Int = 200,
        private val responseBody: String = """{"choices":[{"message":{"content":"جاهز"}}]}""",
        private val onRequest: (String, Map<String, String>, String) -> Unit = { _, _, _ -> }
    ) : HttpTransport {
        var lastUrl: String = ""
        var lastBody: String = ""
        var lastHeaders: Map<String, String> = emptyMap()

        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutSeconds: Int
        ): HttpResult {
            lastUrl = url
            lastBody = body
            lastHeaders = headers
            onRequest(url, headers, body)
            return HttpResult(code, responseBody)
        }
    }

    private val request = CompletionRequest(
        systemPrompt = "أنت مساعد",
        turns = listOf(ChatTurn("user", "مرحبا")),
        maxTokens = 64
    )

    @Test
    fun `builds the chat completions endpoint`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            OpenAiCompatibleProvider.endpoint("https://api.example.com/v1/")
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            OpenAiCompatibleProvider.endpoint("https://api.example.com/v1/chat/completions")
        )
    }

    @Test
    fun `rejects plain http for remote hosts`() {
        val error = OpenAiCompatibleProvider.validateConfig(
            ProviderConfig(baseUrl = "http://api.example.com/v1", model = "m")
        )
        assertNotNull(error)
        assertTrue(error!!.contains("https"))
    }

    @Test
    fun `accepts http for loopback inference servers`() {
        assertNull(
            OpenAiCompatibleProvider.validateConfig(
                ProviderConfig(baseUrl = "http://127.0.0.1:8080/v1", model = "local")
            )
        )
        assertNull(
            OpenAiCompatibleProvider.validateConfig(
                ProviderConfig(baseUrl = "http://localhost:8080/v1", model = "local")
            )
        )
    }

    @Test
    fun `requires a model and a valid scheme`() {
        assertNotNull(OpenAiCompatibleProvider.validateConfig(ProviderConfig(baseUrl = "https://x.dev/v1")))
        assertNotNull(OpenAiCompatibleProvider.validateConfig(ProviderConfig(baseUrl = "ftp://x.dev", model = "m")))
        assertNotNull(OpenAiCompatibleProvider.validateConfig(ProviderConfig(baseUrl = "", model = "m")))
    }

    @Test
    fun `sends a JSON body with the configured model and auth header`() = runTest {
        val transport = FakeTransport()
        val provider = OpenAiCompatibleProvider(
            ProviderConfig(
                baseUrl = "https://api.example.com/v1",
                model = "test-model",
                apiKey = "secret-key",
                jsonMode = true
            ),
            transport
        )

        val result = provider.complete(request)

        assertEquals("جاهز", result.text)
        assertEquals("https://api.example.com/v1/chat/completions", transport.lastUrl)
        assertEquals("Bearer secret-key", transport.lastHeaders["Authorization"])

        val payload = Json.parseToJsonElement(transport.lastBody).jsonObject
        assertEquals("test-model", payload["model"]?.jsonPrimitive?.content)
        assertEquals("json_object", payload["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        val messages = payload["messages"]?.jsonArray
        assertNotNull(messages)
        assertEquals("system", messages!![0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("user", messages[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertFalse(transport.lastBody.contains("secret-key"))
    }

    @Test
    fun `omits response format when json mode is off`() = runTest {
        val transport = FakeTransport()
        val provider = OpenAiCompatibleProvider(
            ProviderConfig(baseUrl = "https://api.example.com/v1", model = "m", jsonMode = false),
            transport
        )
        provider.complete(request)
        val payload = Json.parseToJsonElement(transport.lastBody).jsonObject
        assertFalse(payload.containsKey("response_format"))
    }

    @Test
    fun `surfaces provider errors with the status code`() = runTest {
        val transport = FakeTransport(
            code = 401,
            responseBody = """{"error":{"message":"invalid api key"}}"""
        )
        val provider = OpenAiCompatibleProvider(
            ProviderConfig(baseUrl = "https://api.example.com/v1", model = "m"),
            transport
        )

        val error = runCatching { provider.complete(request) }.exceptionOrNull()
        assertTrue(error is AiException)
        assertTrue(error!!.message!!.contains("401"))
        assertTrue(error.message!!.contains("invalid api key"))
    }

    @Test
    fun `refuses to call the network when unconfigured`() = runTest {
        val transport = FakeTransport()
        val provider = OpenAiCompatibleProvider(ProviderConfig(), transport)
        assertFalse(provider.isConfigured)
        assertNotNull(provider.configurationHint)
        val error = runCatching { provider.complete(request) }.exceptionOrNull()
        assertTrue(error is AiException)
        assertEquals("", transport.lastBody)
    }

    @Test
    fun `extracts legacy text completions`() {
        assertEquals("legacy", OpenAiCompatibleProvider.extractContent("""{"choices":[{"text":"legacy"}]}"""))
        assertNull(OpenAiCompatibleProvider.extractContent("not json"))
    }
}
