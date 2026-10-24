package com.myai.assistant.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.myai.assistant.MyAiApplication
import com.myai.assistant.data.EngineType
import com.myai.assistant.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val container by lazy { (application as MyAiApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOffline.setOnClickListener {
            selectEngine(EngineType.OFFLINE_RULES)
            finishOnboarding()
        }
        binding.btnCloud.setOnClickListener {
            selectEngine(EngineType.OPENAI_COMPATIBLE)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnOpenPermissions.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        binding.btnStart.setOnClickListener { finishOnboarding() }
    }

    private fun selectEngine(engine: EngineType) {
        val current = container.settings.settings.value
        container.settings.save(current.copy(engineType = engine))
    }

    private fun finishOnboarding() {
        container.settings.markOnboardingDone()
        finish()
    }
}
