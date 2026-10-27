package com.myai.assistant.draft

import com.myai.assistant.ai.AiException
import com.myai.assistant.ai.DraftVisionClient
import com.myai.assistant.ai.HttpResult
import com.myai.assistant.ai.HttpTransport
import com.myai.assistant.ai.ProviderConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The draft feature is the only path in the app that is allowed to send an image
 * off the device, so its guarantees are tested directly instead of being assumed.
 */
class DraftPrivacyTest {

    private class RecordingTransport : HttpTransport {
        var lastBody: String? = null
        var calls = 0
        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutSeconds: Int
        ): HttpResult {
            calls++
            lastBody = body
            return HttpResult(200, """{"choices":[{"message":{"content":"{\"enemies\":[\"Crow\"]}"}}]}""")
        }
    }

    private val config = ProviderConfig(
        baseUrl = "https://example.invalid/v1",
        model = "vision-model",
        apiKey = "test-key"
    )

    @Test
    fun `no consent means no network call`() = runTest {
        val transport = RecordingTransport()
        val client = DraftVisionClient(config, transport)
        try {
            client.read("QUJD", consentGranted = false)
            fail("expected the client to refuse without consent")
        } catch (expected: AiException) {
            assertEquals(0, transport.calls)
            assertTrue(expected.message!!.contains("موافقة"))
        }
    }

    @Test
    fun `consent sends exactly one image and no history`() = runTest {
        val transport = RecordingTransport()
        val client = DraftVisionClient(config, transport)
        val result = client.read("QUJD", consentGranted = true)

        assertEquals(1, transport.calls)
        val body = transport.lastBody!!
        assertEquals(1, body.split("\"type\":\"image_url\"").size - 1)
        assertEquals(1, body.split("data:image/jpeg;base64,QUJD").size - 1)
        assertFalse(body.contains("history"))
        assertFalse(body.contains("conversation"))
        assertFalse("the api key must never be in the body", body.contains("test-key"))
        assertEquals(listOf("Crow"), result.context.enemies)
    }

    @Test
    fun `prompt forbids guessing and names every required field`() {
        val prompt = DraftPrompt.SYSTEM_PROMPT
        listOf("map", "mode", "bans", "allies", "enemies", "pick_slot", "confidence", "unreadable")
            .forEach { assertTrue("missing $it", prompt.contains(it)) }
        assertTrue(prompt.contains("لا تخمّن"))
    }

    @Test
    fun `payload is valid json with exactly two messages`() {
        val payload = DraftPrompt.buildVisionPayload("m", "QUJD")
        val root = MiniJson.parseObject(payload)!!
        val messages = (root["messages"] as J.Arr).items
        assertEquals(2, messages.size)
        assertEquals("m", root["model"]?.asString())
    }

    @Test
    fun `an unconfigured provider is reported before any request`() = runTest {
        val transport = RecordingTransport()
        val client = DraftVisionClient(ProviderConfig(), transport)
        assertTrue(client.unavailableReason() != null)
        try {
            client.read("QUJD", consentGranted = true)
            fail("expected validation failure")
        } catch (expected: AiException) {
            assertEquals(0, transport.calls)
        }
    }
}
