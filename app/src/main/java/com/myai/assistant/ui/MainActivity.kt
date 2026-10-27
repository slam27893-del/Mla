package com.myai.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myai.assistant.MyAiApplication
import com.myai.assistant.R
import com.myai.assistant.agent.ConfirmationHandler
import com.myai.assistant.agent.ConfirmationRequest
import com.myai.assistant.data.EngineType
import com.myai.assistant.data.DraftPrefs
import com.myai.assistant.databinding.ActivityMainBinding
import com.myai.assistant.service.FloatingAssistantService
import com.myai.assistant.ui.chat.ChatAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val container by lazy { (application as MyAiApplication).container }
    private val adapter = ChatAdapter { stepId -> container.screenVault.get(stepId) }
    private var confirmationDialog: AlertDialog? = null
    private val draftPrefs by lazy { DraftPrefs(this) }

    private val confirmer = ConfirmationHandler { request ->
        withContext(Dispatchers.Main.immediate) { awaitConfirmation(request) }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startFloatingAssistant() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatList.adapter = adapter
        binding.chatList.itemAnimator = null

        binding.toolbar.setOnMenuItemClickListener { onMenuItemClicked(it) }
        binding.composer.sendButton.setOnClickListener {
            if (container.chatController.busy.value) {
                container.chatController.stop()
            } else {
                sendCurrentInput()
            }
        }
        binding.composer.inputField.setOnEditorActionListener { _, _, _ -> false }
        binding.setupButton.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        lifecycleScope.launch { container.chatController.load() }
        collectState()

        if (!container.settings.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        confirmationDialog?.dismiss()
        confirmationDialog = null
        super.onDestroy()
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.chatController.messages.collect { messages ->
                        adapter.submitList(messages) {
                            binding.emptyState.isVisible = messages.isEmpty()
                            if (messages.isNotEmpty()) {
                                binding.chatList.scrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }
                launch {
                    container.chatController.busy.collect { busy ->
                        binding.composer.sendButton.setIconResource(
                            if (busy) R.drawable.ic_stop else R.drawable.ic_send
                        )
                        binding.composer.sendButton.contentDescription =
                            getString(if (busy) R.string.chat_stop else R.string.chat_send)
                        binding.composer.inputField.isEnabled = !busy
                    }
                }
            }
        }
    }

    private fun sendCurrentInput() {
        val input = binding.composer.inputField
        val text = input.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        if (!container.chatController.submit(text, confirmer)) {
            Toast.makeText(this, R.string.busy_hint, Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
    }

    private fun onMenuItemClicked(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_permissions -> {
            startActivity(Intent(this, PermissionsActivity::class.java))
            true
        }
        R.id.action_clear -> {
            confirmClearConversation()
            true
        }
        R.id.action_floating -> {
            toggleFloatingAssistant()
            true
        }
        R.id.action_draft -> {
            toggleDraftAssistant()
            true
        }
        else -> false
    }

    private fun updateStatus() {
        val settings = container.settings.settings.value
        val engineText = when (settings.engineType) {
            EngineType.OFFLINE_RULES -> getString(R.string.engine_offline)
            EngineType.OPENAI_COMPATIBLE ->
                if (settings.baseUrl.isNotBlank() && settings.model.isNotBlank()) {
                    getString(R.string.engine_cloud_ready, settings.model)
                } else {
                    getString(R.string.engine_cloud_not_ready)
                }
            EngineType.LOCAL_MODEL -> getString(R.string.engine_local)
        }
        binding.engineStatus.text = if (FloatingAssistantService.isRunning) {
            val draft = if (draftPrefs.bubbleEnabled) " • " + getString(R.string.draft_title) else ""
            getString(R.string.status_overlay_on) + " • " + engineText + draft
        } else {
            engineText
        }

        val overlayGranted = PermissionUtils.isOverlayGranted(this)
        val accessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(this)
        binding.permissionStatus.text = if (overlayGranted && accessibilityEnabled) {
            getString(R.string.status_permissions_ok)
        } else {
            getString(R.string.status_permissions_missing)
        }
        binding.permissionStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (overlayGranted && accessibilityEnabled) R.color.success else R.color.warning
            )
        )
        binding.setupButton.isVisible = !overlayGranted || !accessibilityEnabled
    }

    private fun confirmClearConversation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_chat_title)
            .setMessage(R.string.clear_chat_message)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                lifecycleScope.launch {
                    container.chatController.clear()
                    Toast.makeText(this@MainActivity, R.string.clear_chat_done, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun toggleFloatingAssistant() {
        if (FloatingAssistantService.isRunning) {
            FloatingAssistantService.stop(this)
            Toast.makeText(this, R.string.overlay_stopped, Toast.LENGTH_SHORT).show()
            binding.root.postDelayed({ updateStatus() }, 300)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startFloatingAssistant()
    }

    /**
     * Turns the small Draft bubble on or off. It reuses the floating service rather
     * than starting a second overlay: one foreground service, two bubbles.
     */
    private fun toggleDraftAssistant() {
        val enable = !draftPrefs.bubbleEnabled
        draftPrefs.bubbleEnabled = enable
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (FloatingAssistantService.isRunning) {
            FloatingAssistantService.setDraftBubble(this, enable)
        } else {
            startFloatingAssistant()
        }
        Toast.makeText(
            this,
            if (enable) R.string.draft_bubble_on else R.string.draft_bubble_off,
            Toast.LENGTH_SHORT
        ).show()
        binding.root.postDelayed({ updateStatus() }, 400)
    }

    private fun startFloatingAssistant() {
        if (!Settings.canDrawOverlays(this)) return
        FloatingAssistantService.start(this)
        Toast.makeText(this, R.string.overlay_started, Toast.LENGTH_SHORT).show()
        binding.root.postDelayed({ updateStatus() }, 500)
    }

    private suspend fun awaitConfirmation(request: ConfirmationRequest): Boolean =
        suspendCancellableCoroutine { continuation ->
            if (isFinishing || isDestroyed) {
                if (continuation.isActive) continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val message = buildString {
                appendLine(getString(R.string.confirm_tool, request.toolTitle))
                appendLine(getString(R.string.confirm_step, request.stepTitle))
                appendLine(getString(R.string.confirm_details, request.details))
                appendLine()
                append(getString(R.string.confirm_warning))
            }
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_title)
                .setMessage(message)
                .setPositiveButton(R.string.common_execute) { _, _ ->
                    if (continuation.isActive) continuation.resume(true)
                }
                .setNegativeButton(R.string.common_cancel) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .create()
            dialog.setOnDismissListener {
                if (continuation.isActive) continuation.resume(false)
                if (confirmationDialog === dialog) confirmationDialog = null
            }
            confirmationDialog = dialog
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
}
