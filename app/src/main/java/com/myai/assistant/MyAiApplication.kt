package com.myai.assistant

import android.app.Application
import com.myai.assistant.accessibility.AccessibilityBridge
import com.myai.assistant.agent.AgentEngine
import com.myai.assistant.agent.ChatController
import com.myai.assistant.agent.PlannerFactory
import com.myai.assistant.agent.ToolContext
import com.myai.assistant.agent.ToolRegistry
import com.myai.assistant.ai.ProviderConfig
import com.myai.assistant.ai.ProviderFactory
import com.myai.assistant.core.SecureStore
import com.myai.assistant.data.ConversationStore
import com.myai.assistant.data.SettingsRepository
import com.myai.assistant.privacy.ScreenContentGuard
import com.myai.assistant.privacy.ScreenContentVault
import com.myai.assistant.tools.defaultTools
import java.io.File

class MyAiApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Manual dependency graph. Small enough to avoid a DI framework, explicit enough
 * that every dependency direction is visible.
 */
class AppContainer(application: Application) {

    private val appContext = application.applicationContext

    val settings = SettingsRepository(application, SecureStore(application))

    val conversation = ConversationStore(File(application.filesDir, "conversation.json"))

    /**
     * Single instance for the whole process: the same object registers screen
     * reads from the tools and filters outbound AI requests.
     */
    val screenContentGuard = ScreenContentGuard()

    /** Screen readouts live here, never inside a ChatMessage. */
    val screenVault = ScreenContentVault(screenContentGuard)

    val toolRegistry = ToolRegistry(defaultTools())

    private val providers = ProviderFactory(settings, privacy = screenContentGuard)

    /**
     * Provider for the "test connection" button. Routed through the factory so the
     * request is wrapped in the same [com.myai.assistant.ai.PrivacyFilteringProvider]
     * as real traffic instead of talking to the network directly.
     */
    fun providerForTest(config: ProviderConfig) = providers.createWithConfig(config)

    private val plannerFactory = PlannerFactory(settings, providers, screenContentGuard)

    private val engine = AgentEngine(
        plannerProvider = { plannerFactory.create() },
        registry = toolRegistry,
        invoker = { tool, args ->
            tool.execute(
                ToolContext(appContext, AccessibilityBridge.controller, screenContentGuard),
                args
            )
        },
        accessibilityReady = { AccessibilityBridge.isConnected },
        screenVault = screenVault
    )

    val chatController = ChatController(
        store = conversation,
        engine = engine,
        historyEnabled = { settings.settings.value.historyEnabled },
        screenVault = screenVault
    )
}
