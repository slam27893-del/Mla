package com.myai.assistant.agent

import com.myai.assistant.ai.ProviderFactory
import com.myai.assistant.data.EngineType
import com.myai.assistant.data.SettingsRepository

class PlannerFactory(
    private val settings: SettingsRepository,
    private val providers: ProviderFactory
) {
    fun create(): Planner {
        val snapshot = settings.settings.value
        val offline = RuleBasedPlanner()
        return when (snapshot.engineType) {
            EngineType.OFFLINE_RULES -> offline
            EngineType.OPENAI_COMPATIBLE -> FallbackPlanner(LlmPlanner(providers.create(snapshot)), offline)
            EngineType.LOCAL_MODEL -> FallbackPlanner(LlmPlanner(providers.create(snapshot)), offline)
        }
    }
}
