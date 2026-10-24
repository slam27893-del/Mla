package com.myai.assistant.data

import android.content.Context
import com.myai.assistant.core.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EngineType { OFFLINE_RULES, OPENAI_COMPATIBLE, LOCAL_MODEL }

data class AppSettings(
    val engineType: EngineType = EngineType.OFFLINE_RULES,
    val baseUrl: String = "",
    val model: String = "",
    val jsonMode: Boolean = true,
    val confirmSensitive: Boolean = true,
    val historyEnabled: Boolean = true,
    val hasApiKey: Boolean = false
)

/**
 * Single source of truth for user preferences. Non-secret values live in
 * SharedPreferences; the API key lives in [SecureStore] encrypted by Keystore.
 * This is also where "history disabled" is enforced.
 */
class SettingsRepository(
    context: Context,
    private val secureStore: SecureStore
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)

    fun markOnboardingDone() {
        prefs.edit().putBoolean(KEY_ONBOARDING, true).commit()
    }

    /**
     * Persists settings. [newApiKey] semantics: null = keep current key,
     * empty string = delete key, otherwise encrypt and store.
     * Returns false only when the key could not be stored securely.
     */
    fun save(updated: AppSettings, newApiKey: String? = null): Boolean {
        prefs.edit()
            .putString(KEY_ENGINE, updated.engineType.name)
            .putString(KEY_BASE_URL, updated.baseUrl.trim())
            .putString(KEY_MODEL, updated.model.trim())
            .putBoolean(KEY_JSON_MODE, updated.jsonMode)
            .putBoolean(KEY_CONFIRM_SENSITIVE, updated.confirmSensitive)
            .putBoolean(KEY_HISTORY, updated.historyEnabled)
            .commit()

        var keyStored = true
        when {
            newApiKey == null -> Unit
            newApiKey.isBlank() -> secureStore.remove(KEY_API)
            else -> keyStored = secureStore.put(KEY_API, newApiKey.trim())
        }
        _settings.value = read()
        return keyStored
    }

    fun apiKey(): String? = secureStore.get(KEY_API)

    fun clearApiKey() {
        secureStore.remove(KEY_API)
        _settings.value = read()
    }

    fun reset() {
        prefs.edit().clear().commit()
        secureStore.clearAll()
        _settings.value = read()
    }

    private fun read(): AppSettings {
        val engine = runCatching {
            EngineType.valueOf(prefs.getString(KEY_ENGINE, EngineType.OFFLINE_RULES.name)!!)
        }.getOrDefault(EngineType.OFFLINE_RULES)
        return AppSettings(
            engineType = engine,
            baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
            model = prefs.getString(KEY_MODEL, "") ?: "",
            jsonMode = prefs.getBoolean(KEY_JSON_MODE, true),
            confirmSensitive = prefs.getBoolean(KEY_CONFIRM_SENSITIVE, true),
            historyEnabled = prefs.getBoolean(KEY_HISTORY, true),
            hasApiKey = secureStore.get(KEY_API) != null
        )
    }

    private companion object {
        const val PREFS_NAME = "myai.settings"
        const val KEY_ENGINE = "engine"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API = "api_key"
        const val KEY_JSON_MODE = "json_mode"
        const val KEY_CONFIRM_SENSITIVE = "confirm_sensitive"
        const val KEY_HISTORY = "history_enabled"
        const val KEY_ONBOARDING = "onboarding_done"
    }
}
