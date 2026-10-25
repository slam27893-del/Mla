package com.myai.assistant.ai

import com.myai.assistant.privacy.ScreenContentGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFilteringProviderTest {

    private class RecordingProvider : AiProvider {
        var lastRequest: CompletionRequest? = null
        override val id = "recording"
        override val displayName = "recording"
        override val isConfigured = true
        override val configurationHint: String? = null

        override suspend fun complete(request: CompletionRequest): CompletionResult {
            lastRequest = request
            return CompletionResult("ok", id)
        }
    }

    private val secret = "رصيد الحساب 4521.00 ريال"

    @Test
    fun `scrubs system prompt and turns before the delegate sees them`() = runTest {
        val guard = ScreenContentGuard()
        guard.registerScreenRead(secret)
        val delegate = RecordingProvider()
        val provider = PrivacyFilteringProvider(delegate, guard)

        provider.complete(
            CompletionRequest(
                systemPrompt = "أنت مساعد. الشاشة تقول: $secret",
                turns = listOf(
                    ChatTurn("user", "هذا سؤالي"),
                    ChatTurn("assistant", "كان ظاهرًا: $secret")
                )
            )
        )

        val sent = delegate.lastRequest!!
        assertFalse(sent.systemPrompt.contains("4521"))
        assertFalse(sent.turns.any { it.content.contains("4521") })
        assertEquals("هذا سؤالي", sent.turns[0].content)
        assertTrue(sent.turns[1].content.contains(ScreenContentGuard.REDACTED))
    }

    @Test
    fun `passes clean requests through untouched`() = runTest {
        val guard = ScreenContentGuard()
        val delegate = RecordingProvider()
        val provider = PrivacyFilteringProvider(delegate, guard)

        provider.complete(CompletionRequest("system", listOf(ChatTurn("user", "افتح الإعدادات"))))

        assertEquals("system", delegate.lastRequest!!.systemPrompt)
        assertEquals("افتح الإعدادات", delegate.lastRequest!!.turns.single().content)
    }

    @Test
    fun `forwards the delegate configuration state`() {
        val guard = ScreenContentGuard()
        val provider = PrivacyFilteringProvider(RecordingProvider(), guard)

        assertTrue(provider.isConfigured)
        assertEquals("recording", provider.id)
    }
}
