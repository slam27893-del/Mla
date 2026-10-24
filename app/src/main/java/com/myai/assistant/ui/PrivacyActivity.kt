package com.myai.assistant.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myai.assistant.MyAiApplication
import com.myai.assistant.R
import com.myai.assistant.databinding.ActivityPrivacyBinding
import com.myai.assistant.service.FloatingAssistantService
import kotlinx.coroutines.launch

class PrivacyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyBinding
    private val container by lazy { (application as MyAiApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnClearAll.setOnClickListener { confirmDeleteAll() }
    }

    private fun confirmDeleteAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.privacy_clear_confirm_title)
            .setMessage(R.string.privacy_clear_confirm_message)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                lifecycleScope.launch {
                    FloatingAssistantService.stop(this@PrivacyActivity)
                    container.chatController.clear()
                    container.settings.reset()
                    Toast.makeText(
                        this@PrivacyActivity,
                        R.string.privacy_cleared,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
