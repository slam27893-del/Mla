package com.myai.assistant.tools

import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.myai.assistant.agent.RiskLevel
import com.myai.assistant.agent.Tool
import com.myai.assistant.agent.ToolContext
import com.myai.assistant.agent.ToolOutcome
import com.myai.assistant.agent.ToolParameter
import com.myai.assistant.agent.boolArg
import com.myai.assistant.agent.intArg
import com.myai.assistant.agent.optionalArg
import com.myai.assistant.agent.requireArg
import java.util.Locale

class SetAlarmTool : Tool {
    override val id = "set_alarm"
    override val title = "ضبط منبّه"
    override val description = "يفتح واجهة المنبّه في الساعة مع الوقت والنص جاهزين (AlarmClock API الرسمي)."
    /** Creates a real system alarm; the user approves it first. */
    override val risk = RiskLevel.SENSITIVE
    override val parameters = listOf(
        ToolParameter("hour", "الساعة بنظام 24 ساعة (0-23)", type = com.myai.assistant.agent.ParameterType.INTEGER),
        ToolParameter("minute", "الدقيقة (0-59)", type = com.myai.assistant.agent.ParameterType.INTEGER),
        ToolParameter("label", "نص يظهر مع المنبّه", required = false),
        ToolParameter("skip_ui", "true لضبط المنبّه دون فتح الواجهة", required = false, type = com.myai.assistant.agent.ParameterType.BOOLEAN)
    )

    override fun summary(args: Map<String, String>): String =
        "ضبط منبّه الساعة ${args["hour"].orEmpty()}:${args["minute"].orEmpty().padStart(2, '0')}"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val hour = args.intArg("hour") ?: return ToolOutcome.failed("معامل hour مطلوب ويجب أن يكون رقمًا.")
        val minute = args.intArg("minute") ?: 0
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolOutcome.failed("الوقت غير صالح: hour يجب أن يكون 0-23 و minute 0-59.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, args.optionalArg("label") ?: "MyAI")
            putExtra(AlarmClock.EXTRA_SKIP_UI, args.boolArg("skip_ui", false))
        }
        val launched = IntentHelpers.tryLaunch(context.appContext, intent)
        val formatted = String.format(Locale.US, "%02d:%02d", hour, minute)
        return if (launched.success) {
            if (args.boolArg("skip_ui", false)) {
                ToolOutcome.dispatched("أُرسل طلب ضبط منبّه $formatted إلى تطبيق الساعة.")
            } else {
                ToolOutcome.dispatched("فُتحت واجهة المنبّه على $formatted. اضغط حفظ لتأكيده.")
            }
        } else {
            ToolOutcome.failed(launched.message ?: "تعذّر فتح تطبيق المنبّه.")
        }
    }
}

class SetTimerTool : Tool {
    override val id = "set_timer"
    override val title = "ضبط مؤقت"
    override val description = "يفتح مؤقت الساعة لمدة محددة بالثواني."
    /** Creates a real system timer; the user approves it first. */
    override val risk = RiskLevel.SENSITIVE
    override val parameters = listOf(
        ToolParameter("seconds", "المدة بالثواني", type = com.myai.assistant.agent.ParameterType.INTEGER),
        ToolParameter("label", "نص يظهر مع المؤقت", required = false)
    )

    override fun summary(args: Map<String, String>): String =
        "ضبط مؤقت لمدة ${args["seconds"].orEmpty()} ثانية"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val seconds = args.intArg("seconds") ?: return ToolOutcome.failed("معامل seconds مطلوب ويجب أن يكون رقمًا.")
        if (seconds !in 1..86_400) return ToolOutcome.failed("المدة يجب أن تكون بين ثانية و24 ساعة.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, args.optionalArg("label") ?: "MyAI")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val launched = IntentHelpers.tryLaunch(context.appContext, intent)
        return if (launched.success) {
            ToolOutcome.dispatched("أُرسل طلب ضبط مؤقت لمدة $seconds ثانية إلى تطبيق الساعة.")
        } else {
            ToolOutcome.failed(launched.message ?: "تعذّر فتح تطبيق المؤقت.")
        }
    }
}

class CalendarEventTool : Tool {
    override val id = "create_calendar_event"
    override val title = "إنشاء موعد في التقويم"
    override val description = "يفتح شاشة إضافة موعد في تقويم المستخدم بالنص والتاريخ. لا يُنشئ الحدث دون حفظ المستخدم."
    override val parameters = listOf(
        ToolParameter("title", "عنوان الموعد"),
        ToolParameter("begin_time", "وقت البداية بالمللي ثانية Unix (اختياري)", required = false, type = com.myai.assistant.agent.ParameterType.INTEGER),
        ToolParameter("description", "تفاصيل الموعد", required = false)
    )
    override val risk = RiskLevel.SENSITIVE

    override fun summary(args: Map<String, String>): String = "إضافة موعد: ${args["title"].orEmpty()}"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val title = args.requireArg("title")
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            args.optionalArg("description")?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            args.optionalArg("begin_time")?.toLongOrNull()?.let {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it)
            }
        }
        val launched = IntentHelpers.tryLaunch(context.appContext, intent)
        return if (launched.success) {
            ToolOutcome.dispatched("فُتحت شاشة إضافة الموعد \"$title\". راجع التفاصيل واضغط حفظ.")
        } else {
            ToolOutcome.failed(launched.message ?: "تعذّر فتح تطبيق التقويم.")
        }
    }
}
