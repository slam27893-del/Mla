package com.myai.assistant.agent

import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.MessageKind
import com.myai.assistant.core.model.Role
import com.myai.assistant.data.ConversationStore
import com.myai.assistant.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Single entry point for both the in-app chat and the floating panel. Only one
 * turn can run at a time; the busy flag is observable by both UIs.
 */
class ChatController(
    private val store: ConversationStore,
    private val engine: AgentEngine,
    private val settingsRepo: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {

    val messages: StateFlow<List<ChatMessage>> = store.messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var job: Job? = null

    suspend fun load() {
        store.persistEnabled = settingsRepo.settings.value.historyEnabled
        store.load()
    }

    /** Returns false when a request is already running or the text is blank. */
    fun submit(text: String, confirmer: ConfirmationHandler): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return false
        _busy.value = true
        job = scope.launch { runTurn(trimmed, confirmer) }
        return true
    }

    fun stop() {
        job?.cancel()
    }

    suspend fun clear() {
        job?.cancel()
        store.clear()
    }

    private suspend fun runTurn(text: String, confirmer: ConfirmationHandler) {
        val assistantId = UUID.randomUUID().toString()
        try {
            store.persistEnabled = settingsRepo.settings.value.historyEnabled
            val history = store.messages.value.takeLast(12)

            store.append(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = Role.USER,
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
            )
            store.append(
                ChatMessage(
                    id = assistantId,
                    role = Role.ASSISTANT,
                    text = "",
                    timestamp = System.currentTimeMillis(),
                    pending = true
                )
            )

            engine.run(text, history, confirmer).collect { event ->
                when (event) {
                    is AgentEvent.Thinking -> Unit
                    is AgentEvent.PlanCreated -> store.update(assistantId) {
                        it.copy(text = event.reply, plan = event.plan, pending = true)
                    }
                    is AgentEvent.StepUpdated -> store.update(assistantId) {
                        it.copy(plan = event.plan)
                    }
                    is AgentEvent.ConfirmationRequired -> Unit
                    is AgentEvent.Reply -> store.update(assistantId) {
                        it.copy(text = event.text, pending = false)
                    }
                    is AgentEvent.Error -> store.update(assistantId) {
                        it.copy(text = event.message, kind = MessageKind.ERROR, pending = false)
                    }
                    is AgentEvent.Finished -> store.update(assistantId) { it.copy(pending = false) }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                store.update(assistantId) {
                    it.copy(pending = false, text = it.text.ifBlank { "أُوقف الطلب بناءً على طلبك." })
                }
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                store.update(assistantId) {
                    it.copy(
                        pending = false,
                        kind = MessageKind.ERROR,
                        text = "تعذّر إكمال الطلب: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
        } finally {
            _busy.value = false
            job = null
        }
    }
}
