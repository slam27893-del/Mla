package com.myai.assistant.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import com.myai.assistant.agent.RiskLevel
import com.myai.assistant.agent.Tool
import com.myai.assistant.agent.ToolContext
import com.myai.assistant.agent.ToolOutcome
import com.myai.assistant.agent.ToolParameter
import com.myai.assistant.agent.optionalArg
import com.myai.assistant.agent.requireArg
import com.myai.assistant.util.ArabicText

/**
 * Opens the system dialer with a prefilled number. It NEVER places the call
 * itself, so no CALL_PHONE permission is requested and the user stays in control.
 */
class DialTool : Tool {
    override val id = "dial_number"
    override val title = "فتح لوحة الاتصال"
    override val description = "يفتح تطبيق الهاتف مع الرقم جاهزًا؛ الاتصال النهائي يبقى بيد المستخدم."
    override val parameters = listOf(ToolParameter("number", "رقم الهاتف مع رمز الدولة إن أمكن"))

    override fun summary(args: Map<String, String>): String = "فتح لوحة الاتصال بالرقم ${args["number"].orEmpty()}"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val number = ArabicText.digitsOnly(args.requireArg("number"))
        if (number.length < 6) return ToolOutcome.failed("رقم الهاتف غير مكتمل.")
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        val launched = IntentHelpers.tryLaunch(context.appContext, intent)
        return if (launched.success) {
            ToolOutcome.dispatched("فُتحت لوحة الاتصال بالرقم $number. اضغط زر الاتصال لإتمام المكالمة.")
        } else {
            ToolOutcome.failed(launched.message ?: "تعذّر فتح لوحة الاتصال.")
        }
    }
}

/**
 * Opens the messaging app with number and body prefilled. The user must press
 * send; MyAI never sends an SMS silently and does not request READ_SMS.
 */
class ComposeSmsTool : Tool {
    override val id = "compose_sms"
    override val title = "تجهيز رسالة نصية"
    override val description = "يفتح تطبيق الرسائل مع الرقم والنص جاهزين. الإرسال النهائي يبقى بيد المستخدم."
    override val parameters = listOf(
        ToolParameter("number", "رقم المستلم"),
        ToolParameter("message", "نص الرسالة", required = false)
    )
    override val risk = RiskLevel.SENSITIVE

    override fun summary(args: Map<String, String>): String =
        "تجهيز رسالة إلى ${args["number"].orEmpty()}"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val number = ArabicText.digitsOnly(args.requireArg("number"))
        if (number.length < 6) return ToolOutcome.failed("رقم الهاتف غير مكتمل.")
        val message = args.optionalArg("message").orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
        if (message.isNotEmpty()) intent.putExtra("sms_body", message)
        val launched = IntentHelpers.tryLaunch(context.appContext, intent)
        return if (launched.success) {
            ToolOutcome.dispatched("فُتح تطبيق الرسائل برسالة إلى $number. راجع النص واضغط إرسال بنفسك.")
        } else {
            ToolOutcome.failed(launched.message ?: "تعذّر فتح تطبيق الرسائل.")
        }
    }
}

class ShareTextTool : Tool {
    override val id = "share_text"
    override val title = "مشاركة نص"
    override val description = "يفتح قائمة المشاركة في Android لمشاركة نص عبر تطبيق يختاره المستخدم."
    override val parameters = listOf(ToolParameter("text", "النص المراد مشاركته"))
    override val risk = RiskLevel.SENSITIVE

    override fun summary(args: Map<String, String>): String = "مشاركة نص عبر تطبيق آخر"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val text = args.requireArg("text")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "مشاركة عبر")
        val launched = IntentHelpers.tryLaunch(context.appContext, chooser)
        return if (launched.success) {
            ToolOutcome.dispatched("فُتحت قائمة المشاركة.")
        } else {
            ToolOutcome.failed(launched.message ?: "تعذّر فتح قائمة المشاركة.")
        }
    }
}

class CopyToClipboardTool : Tool {
    override val id = "copy_to_clipboard"
    override val title = "نسخ إلى الحافظة"
    override val description = "ينسخ نصًا إلى حافظة Android ويقرأها للتحقق."
    override val parameters = listOf(ToolParameter("text", "النص المراد نسخه"))

    override fun summary(args: Map<String, String>): String = "نسخ نص إلى الحافظة"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val text = args.requireArg("text")
        val clipboard = context.appContext.getSystemService(ClipboardManager::class.java)
            ?: return ToolOutcome.failed("خدمة الحافظة غير متاحة على هذا الجهاز.")
        clipboard.setPrimaryClip(ClipData.newPlainText("MyAI", text))
        val stored = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()
        return if (stored == text) {
            ToolOutcome.verified("تم نسخ النص إلى الحافظة والتحقق منه.")
        } else {
            ToolOutcome.dispatched("أُرسل النص إلى الحافظة، لكن لم نتحقق من محتواها.")
        }
    }
}
