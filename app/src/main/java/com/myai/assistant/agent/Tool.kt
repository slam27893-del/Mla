package com.myai.assistant.agent

import android.content.Context
import com.myai.assistant.core.model.Verification

enum class RiskLevel {
    /** Read-only or easily reversible (open app, query battery). */
    SAFE,
    /** Sends data, reads the screen, taps UI or changes device state: needs confirmation. */
    SENSITIVE
}

enum class ParameterType { STRING, INTEGER, BOOLEAN }

data class ToolParameter(
    val name: String,
    val description: String,
    val required: Boolean = true,
    val type: ParameterType = ParameterType.STRING,
    val allowedValues: List<String> = emptyList()
)

data class ToolOutcome(
    val success: Boolean,
    val verification: Verification,
    val message: String
) {
    companion object {
        fun verified(message: String) = ToolOutcome(true, Verification.VERIFIED, message)
        fun dispatched(message: String) = ToolOutcome(true, Verification.DISPATCHED, message)
        fun unverified(message: String) = ToolOutcome(true, Verification.UNVERIFIED, message)
        fun failed(message: String) = ToolOutcome(false, Verification.FAILED, message)
    }
}

class ToolInputException(message: String) : Exception(message)
class ToolUnavailableException(message: String) : Exception(message)

/**
 * A single callable capability. Tools are stateless, synchronous from the
 * planner's point of view and MUST report honestly whether their effect was
 * verified or only dispatched to the OS.
 */
interface Tool {
    val id: String
    val title: String
    val description: String
    val parameters: List<ToolParameter>
    val risk: RiskLevel get() = RiskLevel.SAFE
    val requiresAccessibility: Boolean get() = false
    fun summary(args: Map<String, String>): String
    suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome
}

class ToolContext(
    val appContext: Context,
    val screen: ScreenController? = null
) {
    fun requireScreen(): ScreenController =
        screen?.takeIf { it.isConnected }
            ?: throw ToolUnavailableException(
                "خدمة الوصولية غير مفعّلة أو غير متصلة. فعّلها من مركز الأذونات ثم أعد المحاولة."
            )
}

fun Map<String, String>.requireArg(name: String): String =
    this[name]?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw ToolInputException("المعامل المطلوب مفقود: $name")

fun Map<String, String>.optionalArg(name: String): String? =
    this[name]?.trim()?.takeIf { it.isNotEmpty() }

fun Map<String, String>.intArg(name: String): Int? = optionalArg(name)?.toIntOrNull()

fun Map<String, String>.boolArg(name: String, default: Boolean = false): Boolean =
    when (optionalArg(name)?.lowercase()) {
        null -> default
        "true", "1", "yes", "نعم" -> true
        "false", "0", "no", "لا" -> false
        else -> default
    }
