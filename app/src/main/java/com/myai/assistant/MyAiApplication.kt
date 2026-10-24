package com.myai.assistant

import android.app.Application
import com.myai.assistant.accessibility.AccessibilityBridge
import com.myai.assistant.agent.AgentEngine
import com.myai.assistant.agent.ChatController
import com.myai.assistant.agent.PlannerFactory
import com.myai.assistant.agent.ToolContext
import com.myai.assistant.agent.ToolRegistry
import com.myai.assistant.ai.ProviderFactory
import com.myai.assistant.core.SecureStore
import com.myai.assistant.data.ConversationStore
import com.myai.assistant.data.SettingsRepository
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

    val toolRegistry = ToolRegistry(defaultTools())

    private val providers = ProviderFactory(settings)

    private val plannerFactory = PlannerFactory(settings, providers)

    private val engine = AgentEngine(
        plannerProvider = { plannerFactory.create() },
        registry = toolRegistry,
        invoker = { tool, args ->
            tool.execute(
                ToolContext(appContext, AccessibilityBridge.controller),
                args
            )
        },
        accessibilityReady = { AccessibilityBridge.isConnected },
        requireConfirmation = { settings.settings.value.confirmSensitive }
    )

    val chatController = ChatController(conversation, engine, settings)
}
