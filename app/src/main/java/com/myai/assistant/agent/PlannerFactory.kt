package com.myai.assistant.agent

import com.myai.assistant.ai.ProviderFactory
import com.myai.assistant.data.EngineType
import com.myai.assistant.data.SettingsRepository
import com.myai.assistant.privacy.ScreenContentGuard

class PlannerFactory(
    private val settings: SettingsRepository,
    private val providers: ProviderFactory,
    private val privacy: ScreenContentGuard = ScreenContentGuard()
) {
    fun create(): Planner {
        val snapshot = settings.settings.value
        val offline = RuleBasedPlanner()
        return when (snapshot.engineType) {
            EngineType.OFFLINE_RULES -> offline
            EngineType.OPENAI_COMPATIBLE -> FallbackPlanner(LlmPlanner(providers.create(snapshot), privacy), offline)
            EngineType.LOCAL_MODEL -> FallbackPlanner(LlmPlanner(providers.create(snapshot), privacy), offline)
        }
    }
}
