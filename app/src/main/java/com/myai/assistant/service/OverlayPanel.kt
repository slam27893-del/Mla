package com.myai.assistant.service

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.myai.assistant.R
import com.myai.assistant.agent.ChatController
import com.myai.assistant.agent.ConfirmationHandler
import com.myai.assistant.databinding.OverlayPanelBinding
import com.myai.assistant.ui.chat.ChatAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Floating chat panel. Uses its own overlay window (TYPE_APPLICATION_OVERLAY) and
 * shares the same [ChatController] as the in-app screen, so a request started in
 * one place is visible in the other.
 */
class OverlayPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val controller: ChatController,
    private val confirmer: ConfirmationHandler,
    private val onClosed: () -> Unit
) {

    private var binding: OverlayPanelBinding? = null
    private var panelScope: CoroutineScope? = null
    private val adapter = ChatAdapter()

    val isShowing: Boolean get() = binding != null

    fun show() {
        if (binding != null) return
        val panelBinding = OverlayPanelBinding.inflate(LayoutInflater.from(context))
        binding = panelBinding

        panelBinding.overlayList.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        panelBinding.overlayList.adapter = adapter
        panelBinding.panelRoot.setOnClickListener { hide() }
        panelBinding.panelCard.setOnClickListener { /* consume taps */ }
        panelBinding.closeButton.setOnClickListener { hide() }
        panelBinding.overlayComposer.inputField.setOnEditorActionListener { _, _, _ -> false }
        panelBinding.overlayComposer.sendButton.setOnClickListener {
            if (controller.busy.value) {
                controller.stop()
            } else {
                send(panelBinding)
            }
        }
        panelBinding.chipBattery.setOnClickListener {
            controller.submit("البطارية", confirmer)
        }
        panelBinding.chipWhatsapp.setOnClickListener {
            controller.submit("افتح واتساب", confirmer)
        }
        panelBinding.chipBack.setOnClickListener {
            controller.submit("ارجع", confirmer)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.25f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            gravity = Gravity.BOTTOM
        }

        val added = runCatching { windowManager.addView(panelBinding.root, params) }.isSuccess
        if (!added) {
            binding = null
            return
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        panelScope = scope
        scope.launch {
            launch {
                controller.messages.collect { messages ->
                    adapter.submitList(messages) {
                        if (messages.isNotEmpty()) {
                            panelBinding.overlayList.scrollToPosition(messages.size - 1)
                        }
                    }
                }
            }
            launch {
                controller.busy.collect { busy ->
                    panelBinding.overlayComposer.sendButton.setIconResource(
                        if (busy) R.drawable.ic_stop else R.drawable.ic_send
                    )
                }
            }
        }

        panelBinding.overlayComposer.inputField.requestFocus()
        panelBinding.overlayComposer.inputField.post {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(panelBinding.overlayComposer.inputField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hide() {
        panelScope?.cancel()
        panelScope = null
        binding?.let { runCatching { windowManager.removeView(it.root) } }
        binding = null
        onClosed()
    }

    private fun send(panelBinding: OverlayPanelBinding) {
        val text = panelBinding.overlayComposer.inputField.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        if (controller.submit(text, confirmer)) {
            panelBinding.overlayComposer.inputField.setText("")
        }
    }

}
