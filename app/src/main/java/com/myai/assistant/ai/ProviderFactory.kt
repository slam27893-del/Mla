package com.myai.assistant.ai

import com.myai.assistant.data.AppSettings
import com.myai.assistant.data.EngineType
import com.myai.assistant.data.SettingsRepository
import com.myai.assistant.privacy.ScreenContentGuard

/**
 * Maps user settings to an [AiProvider]. Add new providers here only.
 *
 * Every provider is wrapped in [PrivacyFilteringProvider], so no caller can bypass
 * the screen-content rule by holding a raw provider reference.
 */
class ProviderFactory(
    private val settings: SettingsRepository,
    private val transport: HttpTransport = OkHttpTransport(),
    private val privacy: ScreenContentGuard = ScreenContentGuard()
) {

    fun create(snapshot: AppSettings = settings.settings.value): AiProvider = guard(
        when (snapshot.engineType) {
            EngineType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(
                config = ProviderConfig(
                    baseUrl = snapshot.baseUrl,
                    model = snapshot.model,
                    apiKey = settings.apiKey(),
                    jsonMode = snapshot.jsonMode
                ),
                transport = transport
            )
            EngineType.LOCAL_MODEL -> LocalModelProvider()
            EngineType.OFFLINE_RULES -> OpenAiCompatibleProvider(ProviderConfig(), transport)
        }
    )

    fun createWithConfig(config: ProviderConfig): AiProvider =
        guard(OpenAiCompatibleProvider(config, transport))

    private fun guard(provider: AiProvider): AiProvider =
        PrivacyFilteringProvider(provider, privacy)
}
