package com.myai.assistant.accessibility

import com.myai.assistant.agent.ScreenController

/**
 * Process-wide, nullable handle to the accessibility service. Keeping it here
 * (instead of holdings a static context) makes it obvious that every screen tool
 * must handle the "not enabled" case.
 */
object AccessibilityBridge {

    /** True when a one-shot screenshot can be attempted without MediaProjection. */
    val canScreenshot: Boolean
        get() = service?.isConnected == true && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R

    @Volatile
    private var service: MyAiAccessibilityService? = null

    val controller: ScreenController?
        get() = service?.takeIf { it.isConnected }

    val isConnected: Boolean
        get() = service?.isConnected == true

    val foregroundPackage: String?
        get() = service?.foregroundPackage

    /** One-shot screenshot, or null when the service is off or the platform refuses. */
    suspend fun screenshot(): android.graphics.Bitmap? {
        val instance = service ?: return null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return runCatching { instance.captureScreenshot() }.getOrNull()
    }

    fun attach(instance: MyAiAccessibilityService) {
        service = instance
    }

    fun detach(instance: MyAiAccessibilityService) {
        if (service === instance) service = null
    }
}
