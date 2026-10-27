package com.myai.assistant.capture

import android.graphics.Bitmap

/**
 * In-process hand-off between [CaptureConsentActivity] / [ScreenCaptureService]
 * and whoever asked for a frame.
 *
 * One capture at a time, one listener. Nothing is stored: the bitmap is delivered
 * once and the listener is cleared immediately afterwards, so a forgotten frame
 * cannot be reused by a later request.
 */
object CaptureBus {

    sealed class Outcome {
        data class Success(val bitmap: Bitmap) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    @Volatile
    private var listener: ((Outcome) -> Unit)? = null

    @Volatile
    var pending: Boolean = false
        private set

    fun register(receiver: (Outcome) -> Unit) {
        listener = receiver
        pending = true
    }

    fun clear() {
        listener = null
        pending = false
    }

    fun deliver(outcome: Outcome) {
        val receiver = listener
        listener = null
        pending = false
        receiver?.invoke(outcome)
    }

    fun fail(message: String) = deliver(Outcome.Failure(message))
}
