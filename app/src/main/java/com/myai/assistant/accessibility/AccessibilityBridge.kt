package com.myai.assistant.accessibility

import com.myai.assistant.agent.ScreenController

/**
 * Process-wide, nullable handle to the accessibility service. Keeping it here
 * (instead of holdings a static context) makes it obvious that every screen tool
 * must handle the "not enabled" case.
 */
object AccessibilityBridge {

    @Volatile
    private var service: MyAiAccessibilityService? = null

    val controller: ScreenController?
        get() = service?.takeIf { it.isConnected }

    val isConnected: Boolean
        get() = service?.isConnected == true

    val foregroundPackage: String?
        get() = service?.foregroundPackage

    fun attach(instance: MyAiAccessibilityService) {
        service = instance
    }

    fun detach(instance: MyAiAccessibilityService) {
        if (service === instance) service = null
    }
}
