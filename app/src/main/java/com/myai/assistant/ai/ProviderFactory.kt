package com.myai.assistant.ai

import com.myai.assistant.data.AppSettings
import com.myai.assistant.data.EngineType
import com.myai.assistant.data.SettingsRepository

/**
 * Maps user settings to an [AiProvider]. Add new providers here only.
 */
class ProviderFactory(
    private val settings: SettingsRepository,
    private val transport: HttpTransport = OkHttpTransport()
) {

    fun create(snapshot: AppSettings = settings.settings.value): AiProvider = when (snapshot.engineType) {
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

    fun createWithConfig(config: ProviderConfig): AiProvider =
        OpenAiCompatibleProvider(config, transport)
}
