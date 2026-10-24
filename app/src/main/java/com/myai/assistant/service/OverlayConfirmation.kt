package com.myai.assistant.service

import android.content.Context
import android.graphics.PixelFormat
import android.view.LayoutInflater
import android.view.WindowManager
import com.myai.assistant.R
import com.myai.assistant.agent.ConfirmationRequest
import com.myai.assistant.databinding.OverlayConfirmBinding
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Confirmation UI that works on top of other apps while the floating assistant is
 * active. The plan engine suspends until the user answers.
 */
class OverlayConfirmation(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private var binding: OverlayConfirmBinding? = null
    private var continuation: CancellableContinuation<Boolean>? = null

    suspend fun show(request: ConfirmationRequest): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (binding != null) return@withContext false

            suspendCancellableCoroutine { cont ->
                val dialogBinding = OverlayConfirmBinding.inflate(LayoutInflater.from(context))
                binding = dialogBinding
                continuation = cont

                dialogBinding.confirmTool.text =
                    context.getString(R.string.confirm_tool, request.toolTitle)
                dialogBinding.confirmStep.text =
                    context.getString(R.string.confirm_step, request.stepTitle)
                dialogBinding.confirmDetails.text =
                    context.getString(R.string.confirm_details, request.details)

                dialogBinding.btnCancel.setOnClickListener { finish(false) }
                dialogBinding.btnExecute.setOnClickListener { finish(true) }
                dialogBinding.confirmRoot.setOnClickListener { finish(false) }
                dialogBinding.confirmCard.setOnClickListener { /* consume */ }

                cont.invokeOnCancellation { removeWindow() }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.TRANSLUCENT
                ).apply { dimAmount = 0.5f }

                val added = runCatching {
                    windowManager.addView(dialogBinding.root, params)
                }.isSuccess

                if (!added) {
                    binding = null
                    continuation = null
                    if (cont.isActive) cont.resume(false)
                }
            }
        }

    private fun finish(approved: Boolean) {
        val cont = continuation ?: return
        continuation = null
        removeWindow()
        if (cont.isActive) cont.resume(approved)
    }

    private fun removeWindow() {
        binding?.let { runCatching { windowManager.removeView(it.root) } }
        binding = null
    }
}
