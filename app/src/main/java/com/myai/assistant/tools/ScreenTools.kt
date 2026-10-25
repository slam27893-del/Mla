package com.myai.assistant.tools

import com.myai.assistant.agent.OutputPrivacy
import com.myai.assistant.agent.RiskLevel
import com.myai.assistant.agent.ScreenActionResult
import com.myai.assistant.agent.Tool
import com.myai.assistant.agent.ToolContext
import com.myai.assistant.agent.ToolOutcome
import com.myai.assistant.agent.ToolParameter
import com.myai.assistant.agent.optionalArg
import com.myai.assistant.agent.requireArg

/**
 * Screen tools are all gated behind the optional accessibility service and
 * report exactly what they could verify.
 */

class ScreenSummaryTool : Tool {
    override val id = "screen_summary"
    override val title = "قراءة الشاشة"
    override val description = "يقرأ النصوص الظاهرة حاليًا على الشاشة عبر خدمة الوصولية ويعرضها ملخّصة. لا يرسلها إلى أي خادم."
    override val parameters = emptyList<ToolParameter>()
    override val risk = RiskLevel.SENSITIVE
    override val requiresAccessibility = true

    /**
     * The result is screen text: it stays on the device and is never part of a
     * remote AI request (see RemoteHistoryFilter / ScreenContentGuard).
     */
    override val outputPrivacy = OutputPrivacy.SCREEN_CONTENT

    override fun summary(args: Map<String, String>): String = "قراءة محتوى الشاشة الحالية (يبقى على الجهاز)"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val snapshot = context.requireScreen().snapshot()
        return ToolOutcome.verified(ScreenSummaryFormatter.format(snapshot, context.privacy))
    }
}

class ClickTextTool : Tool {
    override val id = "click_text"
    override val title = "النقر على عنصر"
    override val description = "يبحث عن عنصر يحمل النص المحدد على الشاشة وينقر عليه (أو يرسل إيماءة لمس عند تعذّر النقر المباشر)."
    override val parameters = listOf(ToolParameter("text", "نص العنصر كما يظهر على الشاشة"))
    override val risk = RiskLevel.SENSITIVE
    override val requiresAccessibility = true

    override fun summary(args: Map<String, String>): String = "النقر على \"${args["text"].orEmpty()}\""

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome =
        context.requireScreen().clickText(args.requireArg("text")).toToolOutcome()
}

class SetTextTool : Tool {
    override val id = "set_text"
    override val title = "كتابة نص"
    override val description = "يكتب نصًا في حقل النص النشط، أو في حقل يحدده المستخدم، باستخدام AccessibilityNodeInfo.ACTION_SET_TEXT."
    override val parameters = listOf(
        ToolParameter("text", "النص المراد كتابته"),
        ToolParameter("target", "نص الحقل المستهدف (اختياري)", required = false)
    )
    override val risk = RiskLevel.SENSITIVE
    override val requiresAccessibility = true

    override fun summary(args: Map<String, String>): String {
        val text = args["text"].orEmpty().trim().replace("\n", " ")
        val clipped = if (text.length <= 80) text else text.take(80) + "…"
        val target = args.optionalArg("target")?.let { " في الحقل \"$it\"" }.orEmpty()
        return "كتابة \"$clipped\"$target"
    }

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome =
        context.requireScreen()
            .setText(args.requireArg("text"), args.optionalArg("target"))
            .toToolOutcome()
}

class ScrollScreenTool : Tool {
    override val id = "scroll_screen"
    override val title = "تمرير الشاشة"
    override val description = "يمرر المنطقة القابلة للتمرير في الشاشة الحالية لأعلى أو لأسفل."
    override val parameters = listOf(
        ToolParameter(
            name = "direction",
            description = "اتجاه التمرير",
            required = false,
            allowedValues = listOf("down", "up")
        )
    )
    override val requiresAccessibility = true

    override fun summary(args: Map<String, String>): String =
        if (args["direction"] == "up") "التمرير لأعلى" else "التمرير لأسفل"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val direction = args.optionalArg("direction")?.lowercase() ?: "down"
        return context.requireScreen().scroll(direction).toToolOutcome()
    }
}

class GlobalActionTool : Tool {
    override val id = "global_action"
    override val title = "زر نظام"
    override val description = "ينفّذ إجراء نظام (رجوع، رئيسية، التطبيقات الحديثة، لوحة الإشعارات) عبر خدمة الوصولية."
    override val parameters = listOf(
        ToolParameter(
            name = "action",
            description = "إجراء النظام",
            allowedValues = listOf("back", "home", "recents", "notifications", "quick_settings")
        )
    )
    override val risk = RiskLevel.SENSITIVE
    override val requiresAccessibility = true

    override fun summary(args: Map<String, String>): String {
        val label = when (args["action"]) {
            "home" -> "الرئيسية"
            "recents" -> "التطبيقات الحديثة"
            "notifications" -> "لوحة الإشعارات"
            "quick_settings" -> "الإعدادات السريعة"
            else -> "الرجوع"
        }
        return "إجراء النظام: $label"
    }

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome =
        context.requireScreen().globalAction(args.requireArg("action")).toToolOutcome()
}

private fun ScreenActionResult.toToolOutcome(): ToolOutcome = when {
    !success -> ToolOutcome.failed(message)
    verified -> ToolOutcome.verified(message)
    else -> ToolOutcome.dispatched(message)
}
