package com.myai.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.myai.assistant.BuildConfig
import com.myai.assistant.MyAiApplication
import com.myai.assistant.R
import com.myai.assistant.ai.ChatTurn
import com.myai.assistant.ai.CompletionRequest
import com.myai.assistant.ai.OpenAiCompatibleProvider
import com.myai.assistant.ai.ProviderConfig
import com.myai.assistant.data.AppSettings
import com.myai.assistant.data.EngineType
import com.myai.assistant.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val container by lazy { (application as MyAiApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.versionText.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)

        bindCurrentSettings()

        binding.engineGroup.setOnCheckedChangeListener { _, _ -> renderEngineState() }
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnClearKey.setOnClickListener { clearApiKey() }
        binding.btnPrivacy.setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }
        binding.btnTest.setOnClickListener { testConnection() }
    }

    private fun bindCurrentSettings() {
        val settings = container.settings.settings.value
        when (settings.engineType) {
            EngineType.OFFLINE_RULES -> binding.rbOffline.isChecked = true
            EngineType.OPENAI_COMPATIBLE -> binding.rbCloud.isChecked = true
            EngineType.LOCAL_MODEL -> binding.rbLocal.isChecked = true
        }
        binding.etBaseUrl.setText(settings.baseUrl)
        binding.etModel.setText(settings.model)
        binding.etApiKey.setText("")
        binding.switchJsonMode.isChecked = settings.jsonMode
        binding.switchHistory.isChecked = settings.historyEnabled
        refreshKeyStatus()
        renderEngineState()
    }

    private fun renderEngineState() {
        val cloud = binding.rbCloud.isChecked
        binding.etBaseUrl.isEnabled = cloud
        binding.etModel.isEnabled = cloud
        binding.etApiKey.isEnabled = cloud
        binding.btnClearKey.isEnabled = cloud
        binding.switchJsonMode.isEnabled = cloud
        binding.btnTest.isEnabled = cloud
        binding.localNote.isVisible = binding.rbLocal.isChecked
        binding.tilBaseUrl.alpha = if (cloud) 1f else 0.5f
    }

    private fun refreshKeyStatus() {
        binding.apiKeyStatus.text = getString(
            if (container.settings.settings.value.hasApiKey) {
                R.string.settings_api_key_saved
            } else {
                R.string.settings_api_key_none
            }
        )
    }

    private fun saveSettings() {
        val engine = when (binding.engineGroup.checkedRadioButtonId) {
            R.id.rbCloud -> EngineType.OPENAI_COMPATIBLE
            R.id.rbLocal -> EngineType.LOCAL_MODEL
            else -> EngineType.OFFLINE_RULES
        }
        val baseUrl = binding.etBaseUrl.text?.toString()?.trim().orEmpty()
        val model = binding.etModel.text?.toString()?.trim().orEmpty()
        val jsonMode = binding.switchJsonMode.isChecked

        if (engine == EngineType.OPENAI_COMPATIBLE) {
            val error = OpenAiCompatibleProvider.validateConfig(
                ProviderConfig(baseUrl = baseUrl, model = model, jsonMode = jsonMode)
            )
            if (error != null) {
                binding.tilBaseUrl.error = error
                showTestResult(error, isError = true)
                return
            }
        }
        binding.tilBaseUrl.error = null

        val updated = AppSettings(
            engineType = engine,
            baseUrl = baseUrl,
            model = model,
            jsonMode = jsonMode,
            historyEnabled = binding.switchHistory.isChecked,
            hasApiKey = container.settings.settings.value.hasApiKey
        )
        val typedKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        val keyStored = container.settings.save(updated, newApiKey = typedKey.takeIf { it.isNotEmpty() })
        binding.etApiKey.setText("")
        refreshKeyStatus()

        if (typedKey.isNotEmpty() && !keyStored) {
            showTestResult(
                "تعذّر تخزين المفتاح مشفّرًا على هذا الجهاز، لذلك لم يُحفظ. جرّب إعادة تشغيل الهاتف أو حذف بيانات التطبيق.",
                isError = true
            )
        } else {
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }

        if (!binding.switchHistory.isChecked) {
            lifecycleScope.launch { container.chatController.clear() }
        }
    }

    private fun clearApiKey() {
        container.settings.clearApiKey()
        refreshKeyStatus()
        Toast.makeText(this, R.string.settings_key_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        if (!binding.rbCloud.isChecked) {
            showTestResult(getString(R.string.settings_test_only_cloud), isError = false)
            return
        }
        val config = ProviderConfig(
            baseUrl = binding.etBaseUrl.text?.toString()?.trim().orEmpty(),
            model = binding.etModel.text?.toString()?.trim().orEmpty(),
            apiKey = binding.etApiKey.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: container.settings.apiKey(),
            jsonMode = binding.switchJsonMode.isChecked,
            maxTokens = 32,
            timeoutSeconds = 30
        )
        val validation = OpenAiCompatibleProvider.validateConfig(config)
        if (validation != null) {
            showTestResult(validation, isError = true)
            return
        }

        binding.btnTest.isEnabled = false
        showTestResult(getString(R.string.settings_testing), isError = false)
        lifecycleScope.launch {
            val startedAt = System.currentTimeMillis()
            val outcome = runCatching {
                val provider = container.providerForTest(config)
                provider.complete(
                    CompletionRequest(
                        systemPrompt = "أنت اختبار اتصال. أجب بكلمة واحدة فقط.",
                        turns = listOf(ChatTurn("user", "قل: جاهز")),
                        maxTokens = 16
                    )
                )
            }
            val elapsed = System.currentTimeMillis() - startedAt
            binding.btnTest.isEnabled = true
            outcome.fold(
                onSuccess = { result ->
                    showTestResult(
                        getString(R.string.settings_test_ok, result.text.trim().take(80)) +
                            " (${elapsed} ms)",
                        isError = false
                    )
                },
                onFailure = { error ->
                    showTestResult(
                        getString(R.string.settings_test_fail, error.message ?: "خطأ غير معروف"),
                        isError = true
                    )
                }
            )
        }
    }

    private fun showTestResult(text: String, isError: Boolean) {
        binding.testResult.isVisible = true
        binding.testResult.text = text
        binding.testResult.setTextColor(
            ContextCompat.getColor(this, if (isError) R.color.error else R.color.success)
        )
    }
}
