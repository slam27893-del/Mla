package com.myai.assistant.agent

import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.ExecutionPlan
import com.myai.assistant.core.model.PlanStep
import com.myai.assistant.util.ArabicText
import com.myai.assistant.util.TimeFormatter
import java.util.Locale

/**
 * Deterministic, fully offline Arabic command parser.
 *
 * This is NOT a language model and it does not pretend to be one: it recognises
 * a documented set of Arabic phrasings and maps them to the *same* tool schema
 * the AI planner uses. That keeps the app useful without a network or an API key
 * and gives the AI planner a safe fallback.
 */
class RuleBasedPlanner : Planner {

    override val id: String = "offline-rules"

    override suspend fun plan(
        request: String,
        history: List<ChatMessage>,
        tools: List<Tool>
    ): PlannerResult {
        val clauses = splitClauses(request)
        if (clauses.isEmpty()) return PlannerResult.Failed(UNKNOWN_MESSAGE)

        val available = tools.map { it.id }.toSet()
        val steps = mutableListOf<PlanStep>()

        for ((index, clause) in clauses.withIndex()) {
            val normalized = normalizeCommand(clause)
            when (val resolution = resolve(normalized, clause)) {
                null -> return PlannerResult.Failed(UNKNOWN_MESSAGE)
                is Resolution.Action -> {
                    if (resolution.toolId !in available) {
                        return PlannerResult.Failed("الأداة ${resolution.toolId} غير متاحة في هذا الإصدار.")
                    }
                    steps += PlanStep(
                        id = "step-${index + 1}",
                        toolId = resolution.toolId,
                        title = resolution.title,
                        args = resolution.args
                    )
                }
                is Resolution.Answer -> {
                    if (steps.isEmpty()) return PlannerResult.DirectAnswer(resolution.text)
                }
            }
        }

        if (steps.isEmpty()) return PlannerResult.Failed(UNKNOWN_MESSAGE)
        return PlannerResult.Planned(
            plan = ExecutionPlan(goal = request.trim(), source = "offline", steps = steps),
            reply = "سأستخدم المحرك المحلي لتنفيذ ${steps.size} من الخطوات."
        )
    }

    // ------------------------------------------------------------------ parsing

    private fun resolve(normalized: String, raw: String): Resolution? {
        answerFor(normalized)?.let { return Resolution.Answer(it) }

        urlRegex.find(raw)?.let { match ->
            return action("open_url", mapOf("url" to match.value), "فتح الرابط ${match.value}")
        }

        flashlight(normalized)?.let { return it }
        timer(normalized)?.let { return it }
        alarm(normalized)?.let { return it }
        call(normalized)?.let { return it }
        sms(normalized, raw)?.let { return it }
        media(normalized)?.let { return it }
        accessibilityAction(normalized, raw)?.let { return it }
        deviceStatus(normalized)?.let { return it }
        settings(normalized)?.let { return it }
        clipboardOrShare(normalized, raw)?.let { return it }
        search(normalized, raw)?.let { return it }
        openApp(normalized)?.let { return it }
        return null
    }

    private fun answerFor(n: String): String? {
        if (n in GREETINGS) {
            return "مرحبًا! أنا MyAI. اكتب أمرًا مثل «افتح واتساب» أو «اضبط منبه 7:00»."
        }
        if (n.contains("ماذا تستطيع") || n.contains("ماذا يمكنك") || n.contains("شنو تقدر") ||
            n == "مساعدة" || n == "help"
        ) {
            return CAPABILITIES
        }
        if (n == "كم الساعه" || n == "كم الوقت" || n == "الوقت" || n == "الساعه") {
            return "الساعة الآن ${TimeFormatter.clock(System.currentTimeMillis())}."
        }
        if (n.contains("كم التاريخ") || n == "التاريخ" || n.contains("ايش التاريخ")) {
            return "الوقت الحالي على الجهاز: ${TimeFormatter.fullNow()}."
        }
        if (n == "من انت" || n == "من انا" || n == "who are you") {
            return "أنا MyAI، مساعد يعمل على هذا الهاتف ويحوّل أوامرك إلى خطوات عبر أدوات Android الرسمية."
        }
        return null
    }

    private fun flashlight(n: String): Resolution? {
        val mentions = listOf("فلاش", "كشاف", "مصباح", "ضوء", "light").any { n.contains(it) }
        if (!mentions) return null
        val off = listOf("اطفي", "اطفئ", "اوقف", "اقفل", "اغلق", "off").any { n.contains(it) }
        val on = listOf("شغل", "اشعل", "افتح", "on", "اضوي", "اشعلي").any { n.contains(it) }
        return when {
            off -> action("flashlight", mapOf("state" to "off"), "إطفاء الكشاف")
            on -> action("flashlight", mapOf("state" to "on"), "تشغيل الكشاف")
            else -> null
        }
    }

    private fun timer(n: String): Resolution? {
        val phrase = DURATION_AFTER.find(n) ?: TIMER_WITH_DURATION.find(n)
        if (phrase != null) {
            val value = phrase.groupValues[1].toIntOrNull() ?: return null
            val unit = phrase.groupValues[2]
            val seconds = value * unitSeconds(unit)
            if (seconds <= 0) return null
            return action(
                "set_timer",
                mapOf("seconds" to seconds.toString(), "label" to "MyAI"),
                "ضبط مؤقت لمدة $value ${unitLabel(unit)}"
            )
        }
        if (n.contains("موقت") || n.contains("تايمر") || n.contains("timer")) {
            return Resolution.Answer("حدّد مدة المؤقت، مثل: «مؤقت 10 دقائق».")
        }
        return null
    }

    private fun alarm(n: String): Resolution? {
        val wantsAlarm = n.contains("منبه") || n.contains("نبهني") || n.contains("انبهني") || n.contains("alarm")
        if (!wantsAlarm) return null

        val clock = CLOCK_TIME.find(n)
        var hour: Int
        var minute: Int
        if (clock != null) {
            hour = clock.groupValues[1].toIntOrNull() ?: return null
            minute = clock.groupValues[2].toIntOrNull() ?: return null
        } else {
            val bare = MERIDIEM_TIME.find(n) ?: BARE_HOUR.find(n) ?: return Resolution.Answer(
                "حدّد وقت المنبّه، مثل: «اضبط منبه الساعة 7:30» أو «اضبط منبه 7 صباحًا»."
            )
            hour = bare.groupValues[1].toIntOrNull() ?: return null
            minute = 0
            val meridiem = bare.groupValues.getOrNull(2)?.lowercase(Locale.US).orEmpty()
            hour = adjustMeridiem(hour, meridiem)
        }

        if (hour !in 0..23 || minute !in 0..59) {
            return Resolution.Answer("الوقت غير صالح. استخدم ساعة بين 0 و23 ودقيقة بين 0 و59.")
        }
        val formatted = String.format(Locale.US, "%02d:%02d", hour, minute)
        return action(
            "set_alarm",
            mapOf("hour" to hour.toString(), "minute" to minute.toString(), "label" to "MyAI"),
            "ضبط منبّه الساعة $formatted"
        )
    }

    private fun adjustMeridiem(hour: Int, meridiem: String): Int = when {
        meridiem.startsWith("م") && hour in 1..11 -> hour + 12
        meridiem == "pm" && hour in 1..11 -> hour + 12
        meridiem.startsWith("ص") && hour == 12 -> 0
        meridiem == "am" && hour == 12 -> 0
        else -> hour
    }

    private fun call(n: String): Resolution? {
        val match = CALL_REGEX.find(n) ?: return null
        val number = ArabicText.digitsOnly(match.groupValues[1])
        if (number.length < 6) {
            return Resolution.Answer("رقم الهاتف غير مكتمل. أعد كتابته مع رمز الدولة إن أمكن.")
        }
        return action("dial_number", mapOf("number" to number), "فتح لوحة الاتصال بالرقم $number")
    }

    private fun sms(n: String, raw: String): Resolution? {
        val match = SMS_RAW_REGEX.find(raw) ?: SMS_REGEX.find(n) ?: return null
        val number = ArabicText.digitsOnly(match.groupValues[1])
        if (number.length < 6) {
            return Resolution.Answer("رقم الهاتف غير مكتمل. أعد كتابته مع رمز الدولة إن أمكن.")
        }
        val body = match.groupValues[2].trim()
        val args = mutableMapOf("number" to number)
        if (body.isNotEmpty()) args["message"] = body
        return action("compose_sms", args, "تجهيز رسالة إلى $number")
    }

    private fun media(n: String): Resolution? {
        val hasMediaWord = MEDIA_WORDS.any { n.contains(it) }
        val command = when {
            n.contains("التالي") || n.contains("next") -> "next"
            n.contains("السابق") || n.contains("previous") -> "previous"
            n.contains("ايقاف مؤقت") || n.contains("وقف مؤقت") || n.contains("pause") -> "pause"
            n.contains("اوقف") || n.contains("وقف ") || n.endsWith("وقف") || n.contains("اقفل") -> "pause"
            n.contains("شغل") || n.contains("كمل") || n.contains("play") -> "play"
            else -> null
        } ?: return null
        if (!hasMediaWord && command !in setOf("next", "previous")) return null
        val title = when (command) {
            "next" -> "الانتقال إلى المقطع التالي"
            "previous" -> "الرجوع إلى المقطع السابق"
            "pause" -> "إيقاف التشغيل مؤقتًا"
            else -> "تشغيل الوسائط"
        }
        return action("media_control", mapOf("action" to command), title)
    }

    private fun accessibilityAction(n: String, raw: String): Resolution? {
        when (n) {
            "ارجع", "رجوع", "زر الرجوع", "رجوع للخلف", "back" ->
                return action("global_action", mapOf("action" to "back"), "الرجوع للخلف")
            "الرئيسيه", "الشاشه الرئيسيه", "home", "زر الرئيسيه" ->
                return action("global_action", mapOf("action" to "home"), "العودة إلى الشاشة الرئيسية")
        }
        if (n.contains("لوحه الاشعارات") || n == "الاشعارات" || n.contains("اسحب الاشعارات")) {
            return action("global_action", mapOf("action" to "notifications"), "فتح لوحة الإشعارات")
        }
        if (n.contains("اقرا الشاشه") || n.contains("اقر الشاشه") || n.contains("ايش في الشاشه") ||
            n.contains("ماذا في الشاشه") || n.contains("لخص الشاشه") || n.contains("وصف الشاشه")
        ) {
            return action("screen_summary", emptyMap(), "قراءة محتوى الشاشة الحالية")
        }

        val click = CLICK_REGEX.find(raw) ?: CLICK_REGEX.find(n)
        if (click != null) {
            val target = click.groupValues[1].trim()
            if (target.isNotEmpty()) {
                return action("click_text", mapOf("text" to target), "النقر على \"$target\"")
            }
        }

        val writeWithTarget = WRITE_WITH_TARGET.find(raw)
        if (writeWithTarget != null) {
            val target = writeWithTarget.groupValues[1].trim()
            val text = writeWithTarget.groupValues[2].trim()
            if (text.isNotEmpty()) {
                return action(
                    "set_text",
                    mapOf("text" to text, "target" to target),
                    "كتابة نص في الحقل \"$target\""
                )
            }
        }
        val writeInField = WRITE_IN_FIELD.find(raw)
        if (writeInField != null) {
            val text = writeInField.groupValues[1].trim()
            if (text.isNotEmpty()) return action("set_text", mapOf("text" to text), "كتابة نص في الحقل النشط")
        }
        val write = WRITE_SIMPLE.find(raw)
        if (write != null) {
            val text = write.groupValues[1].trim()
            if (text.isNotEmpty()) return action("set_text", mapOf("text" to text), "كتابة نص في الحقل المحدد")
        }

        val scroll = SCROLL_REGEX.find(n)
        if (scroll != null) {
            val direction = when (scroll.groupValues[1]) {
                "لاعلي", "لفوق", "فوق", "up" -> "up"
                else -> "down"
            }
            if (n.contains("انزل")) {
                return action("scroll_screen", mapOf("direction" to "down"), "التمرير لأسفل")
            }
            if (n.contains("اصعد")) {
                return action("scroll_screen", mapOf("direction" to "up"), "التمرير لأعلى")
            }
            return action(
                "scroll_screen",
                mapOf("direction" to direction),
                if (direction == "down") "التمرير لأسفل" else "التمرير لأعلى"
            )
        }
        if (n == "انزل") return action("scroll_screen", mapOf("direction" to "down"), "التمرير لأسفل")
        if (n == "اصعد") return action("scroll_screen", mapOf("direction" to "up"), "التمرير لأعلى")

        return null
    }

    private fun deviceStatus(n: String): Resolution? {
        if (n == "البطاريه" || n == "battery" || n.contains("حاله الجهاز") ||
            n.contains("حاله البطاريه") || n.contains("كم البطاريه") || n.contains("مستوي البطاريه")
        ) {
            return action("device_status", emptyMap(), "قراءة حالة البطارية والشبكة والمساحة")
        }
        return null
    }

    private fun settings(n: String): Resolution? {
        val match = SETTINGS_REGEX.find(n) ?: return null
        val rest = match.groupValues[1].trim()
        val panel = when {
            rest.contains("واي فاي") || rest.contains("wifi") -> "wifi"
            rest.contains("بلوتوث") || rest.contains("bluetooth") -> "bluetooth"
            rest.contains("صوت") || rest.contains("نغمه") || rest.contains("رنين") -> "sound"
            rest.contains("شاشه") || rest.contains("عرض") || rest.contains("سطوع") -> "display"
            rest.contains("بطاريه") || rest.contains("طاقه") -> "battery"
            rest.contains("موقع") || rest.contains("gps") -> "location"
            rest.contains("وصوليه") || rest.contains("اكسسيبيلتي") || rest.contains("accessibility") -> "accessibility"
            rest.contains("تطبيقات") || rest.contains("برامج") || rest.contains("apps") -> "apps"
            rest.contains("اشعارات") || rest.contains("notifications") -> "notifications"
            rest.contains("بيانات") || rest.contains("موبايل") || rest.contains("شبكه الجوال") -> "data"
            rest.contains("افتراضيه") || rest.contains("default") -> "default_apps"
            rest.contains("العرض فوق") || rest.contains("overlay") -> "overlay"
            rest.contains("myai") || rest.contains("التطبيق") -> "app_details"
            else -> "settings"
        }
        return action("open_settings", mapOf("panel" to panel), "فتح إعدادات ${panelLabel(panel)}")
    }

    private fun clipboardOrShare(n: String, raw: String): Resolution? {
        (COPY_RAW_REGEX.find(raw) ?: COPY_REGEX.find(n))?.let { match ->
            val text = match.groupValues[1].trim()
            if (text.isNotEmpty()) return action("copy_to_clipboard", mapOf("text" to text), "نسخ النص إلى الحافظة")
        }
        (SHARE_RAW_REGEX.find(raw) ?: SHARE_REGEX.find(n))?.let { match ->
            val text = match.groupValues[1].trim()
            if (text.isNotEmpty()) return action("share_text", mapOf("text" to text), "مشاركة النص عبر تطبيق آخر")
        }
        return null
    }

    private fun search(n: String, raw: String): Resolution? {
        val match = SEARCH_RAW_REGEX.find(raw) ?: SEARCH_REGEX.find(n) ?: return null
        val query = match.groupValues[1]
            .removeSuffix(" في جوجل")
            .removeSuffix(" على جوجل")
            .trim()
        if (query.isEmpty()) return null
        return action("web_search", mapOf("query" to query), "البحث في الويب عن \"$query\"")
    }

    private fun openApp(n: String): Resolution? {
        val match = OPEN_APP_REGEX.find(n) ?: return null
        var app = match.groupValues[1].trim()
        app = app.removePrefix("لي ").removePrefix("تطبيق ").removePrefix("برنامج ").trim()
        if (app.isEmpty()) return null
        return action("open_app", mapOf("app" to app), "فتح تطبيق $app")
    }

    // ------------------------------------------------------------------ helpers

    /** Normalises and drops sentence punctuation so "كم الساعة؟" == "كم الساعة". */
    private fun normalizeCommand(text: String): String =
        ArabicText.normalize(text).trim { it in COMMAND_PADDING }

    private fun splitClauses(text: String): List<String> =
        text.split(Regex("\\s+(?:ثم|وبعدها|بعدها|بعد ذلك)\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun action(toolId: String, args: Map<String, String>, title: String) =
        Resolution.Action(toolId, args, title)

    private fun unitSeconds(unit: String): Int = when {
        unit.startsWith("ث") -> 1
        unit.startsWith("د") -> 60
        unit.startsWith("س") -> 3600
        else -> 1
    }

    private fun unitLabel(unit: String): String = when {
        unit.startsWith("ث") -> "ثانية"
        unit.startsWith("د") -> "دقيقة"
        else -> "ساعة"
    }

    private fun panelLabel(panel: String): String = when (panel) {
        "wifi" -> "الواي فاي"
        "bluetooth" -> "البلوتوث"
        "sound" -> "الصوت"
        "display" -> "الشاشة"
        "battery" -> "البطارية"
        "location" -> "الموقع"
        "accessibility" -> "إمكانية الوصول"
        "apps" -> "التطبيقات"
        "notifications" -> "الإشعارات"
        "data" -> "بيانات الجوال"
        "default_apps" -> "التطبيقات الافتراضية"
        "overlay" -> "العرض فوق التطبيقات"
        "app_details" -> "صفحة التطبيق"
        else -> "النظام"
    }

    private sealed interface Resolution {
        data class Action(val toolId: String, val args: Map<String, String>, val title: String) : Resolution
        data class Answer(val text: String) : Resolution
    }

    private companion object {
        const val UNKNOWN_MESSAGE =
            "لم أفهم الأمر في المحرك المحلي. جرّب صيغة مثل: «افتح واتساب»، «اضبط منبه 7:30»، " +
                "«ابحث عن طقس جدة»، أو فعّل محرك AI من الإعدادات لفهم أوسع."

        const val CAPABILITIES =
            "أستطيع: فتح التطبيقات، البحث في الويب، فتح الروابط، تجهيز اتصال أو رسالة، ضبط منبّه أو مؤقت، " +
                "فتح شاشات إعدادات النظام، النسخ والمشاركة، تشغيل الكشاف، قراءة حالة الجهاز، " +
                "والتفاعل مع الشاشة (نقر/كتابة/تمرير/رجوع) عند تفعيل خدمة الوصولية."

        val GREETINGS = setOf("مرحبا", "السلام عليكم", "هاي", "اهلا", "هلا", "صباح الخير", "مساء الخير")

        val urlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        const val COMMAND_PADDING = " \t\n؟?!.،,؛;:\"'" 

        val DURATION_AFTER = Regex("بعد\\s+(\\d{1,4})\\s*(ثانيه|ثواني|ثوان|دقيقه|دقايق|دقائق|ساعه|ساعات)")
        val TIMER_WITH_DURATION =
            Regex("(?:موقت|تايمر|timer)\\s*(?:لمده\\s*)?(\\d{1,4})\\s*(ثانيه|ثواني|ثوان|دقيقه|دقايق|دقائق|ساعه|ساعات)")
        val CLOCK_TIME = Regex("(\\d{1,2})\\s*[:.\\u066B\\u066C]\\s*(\\d{1,2})")
        val MERIDIEM_TIME = Regex("(\\d{1,2})\\s*(ص|صباحا|صباح|am|م|مساء|مسائا|ليلا|pm)")
        val BARE_HOUR = Regex("(?:الساعه|منبه)\\s*(\\d{1,2})")

        val CALL_REGEX = Regex("^(?:اتصل|اتصلي|كلم|كلمي|رن|هاتف)\\s*(?:علي|على|ب|برقم|لي)?\\s*([+\\d][\\d\\s\\-]{5,})$")
        val SMS_RAW_REGEX = Regex(
            "^(?:أرسل|ارسل|أرسلي|ارسللي|ابعث|أبعث|ابعثلي)\\s*" +
                "(?:رسالة|رساله|رسالة نصية|sms|رسالة قصيرة)?\\s*" +
                "(?:إلى|الى|الي|ل|على|علي|برقم)?\\s*([+\\d][\\d\\s\\-]{5,})" +
                "(?:\\s+(?:تقول|فيها|نصها|مضمونها)\\s+(.+))?$"
        )
        val SMS_REGEX = Regex(
            "^(?:ارسل|ارسللي|ابعث|ابعثلي)\\s*(?:رساله|رساله نصيه|sms|رساله قصيره)?\\s*" +
                "(?:الي|ل|علي|على|برقم)?\\s*([+\\d][\\d\\s\\-]{5,})(?:\\s+(?:تقول|فيها|نصها|مضمونها)\\s+(.+))?$"
        )
        val MEDIA_WORDS = listOf("موسيقي", "اغنيه", "اغاني", "فيديو", "مقطع", "وسائط", "music", "media")

        val CLICK_REGEX = Regex(
            "^(?:اضغط|أنقر|انقر|المس|دوس|tap|click)\\s+(?:(?:على|علي)\\s+)?(?:زر\\s+)?(.+)$"
        )
        val WRITE_WITH_TARGET = Regex(
            "^(?:اكتب|اكتبي|ادخل|املأ)\\s+في\\s+(?:حقل|الحقل|خانه|الخانه)\\s+(.+?)\\s+(?:النص\\s+)?(.+)$"
        )
        val WRITE_IN_FIELD = Regex(
            "^(?:اكتب|اكتبي|ادخل|املأ)\\s+في\\s+(?:حقل|الحقل|خانه|الخانه)\\s+(.+)$"
        )
        val WRITE_SIMPLE = Regex("^(?:اكتب|اكتبي|ادخل|املأ)\\s+النص\\s+(.+)$")
        val SCROLL_REGEX = Regex("^(?:مرر|اسحب|scroll)\\s*(?:الشاشه\\s*)?(لاسفل|لتحت|للاسفل|لاعلي|لفوق|فوق|down|up)$")

        val SETTINGS_REGEX = Regex(
            "^(?:افتح|ادخل|اذهب الي|روح الي|خذني الي)?\\s*(?:ال)?(?:اعدادات|ضبط|setting|settings)\\s*(.*)$"
        )
        val COPY_REGEX = Regex("^(?:انسخ|انسخي|نسخ)\\s+(?:النص\\s+)?(?:لي\\s+)?(.+)$")
        val COPY_RAW_REGEX = Regex("^(?:انسخ|انسخي|نسخ)\\s+(?:النص\\s+)?(?:لي\\s+)?(.+)$")
        val SHARE_REGEX = Regex("^(?:شارك|شاركي)\\s+(?:النص\\s+)?(.+)$")
        val SHARE_RAW_REGEX = Regex("^(?:شارك|شاركي)\\s+(?:النص\\s+)?(.+)$")
        val SEARCH_RAW_REGEX = Regex(
            "^(?:ابحث|أبحث|ابحثي|بحث)\\s+(?:لي\\s+)?(?:في\\s+(?:النت|الإنترنت|الانترنت|الويب|جوجل)\\s+)?" +
                "(?:عن\\s+)?(.+)$"
        )
        val SEARCH_REGEX = Regex(
            "^(?:ابحث|ابحثي|بحث|بحثي)\\s+(?:لي\\s+)?(?:في\\s+(?:النت|الانترنت|الويب|جوجل)\\s+)?" +
                "(?:عن\\s+)?(.+)$"
        )
        val OPEN_APP_REGEX = Regex(
            "^(?:افتح|افتحي|شغل|شغلي|ابدا|ابداي|فتح|اذهب الي|روح الي)\\s+" +
                "(?:تطبيق\\s+|برنامج\\s+|app\\s+)?(.+)$"
        )
    }
}
