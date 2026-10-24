package com.myai.assistant.data

import com.myai.assistant.core.model.ChatMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local-only transcript. Persisted as JSON in the app's private files directory.
 * When [persistEnabled] is false messages stay in memory for the current session
 * and nothing is written to disk.
 */
class ConversationStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    @Volatile
    var persistEnabled: Boolean = true

    @Volatile
    private var loaded = false

    suspend fun load(): List<ChatMessage> = mutex.withLock {
        if (loaded) return _messages.value
        val loadedMessages = withContext(io) {
            if (!persistEnabled || !file.exists()) {
                emptyList()
            } else {
                runCatching {
                    json.decodeFromString<List<ChatMessage>>(file.readText())
                }.getOrDefault(emptyList())
            }
        }
        _messages.value = loadedMessages.takeLast(MAX_MESSAGES)
        loaded = true
        _messages.value
    }

    suspend fun append(message: ChatMessage) = mutate { it + message }

    suspend fun update(id: String, transform: (ChatMessage) -> ChatMessage) = mutate { list ->
        list.map { if (it.id == id) transform(it) else it }
    }

    suspend fun clear() {
        mutex.withLock {
            _messages.value = emptyList()
            loaded = true
            withContext(io) { runCatching { file.delete() } }
        }
    }

    private suspend fun mutate(block: (List<ChatMessage>) -> List<ChatMessage>) {
        mutex.withLock {
            val updated = block(_messages.value).takeLast(MAX_MESSAGES)
            _messages.value = updated
            if (persistEnabled) {
                val snapshot = updated
                withContext(io) { persist(snapshot) }
            }
        }
    }

    private fun persist(messages: List<ChatMessage>) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(messages))
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        const val MAX_MESSAGES = 300
    }
}
