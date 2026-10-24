package com.myai.assistant.agent

data class ScreenSnapshot(
    val packageName: String?,
    val texts: List<String>,
    val clickableTexts: List<String>
)

data class ScreenActionResult(
    val success: Boolean,
    val message: String,
    /** True when the action was confirmed by the accessibility framework, not only dispatched. */
    val verified: Boolean = success
)

/**
 * Abstraction over the optional AccessibilityService so tools stay testable and
 * the service can be absent without crashing anything.
 */
interface ScreenController {
    val isConnected: Boolean
    val foregroundPackage: String?
    suspend fun snapshot(): ScreenSnapshot
    suspend fun clickText(text: String): ScreenActionResult
    suspend fun setText(text: String, targetText: String?): ScreenActionResult
    suspend fun scroll(direction: String): ScreenActionResult
    suspend fun globalAction(action: String): ScreenActionResult
}
