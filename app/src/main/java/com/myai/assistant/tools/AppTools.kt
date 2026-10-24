package com.myai.assistant.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import com.myai.assistant.agent.Tool
import com.myai.assistant.agent.ToolContext
import com.myai.assistant.agent.ToolOutcome
import com.myai.assistant.agent.ToolParameter
import com.myai.assistant.agent.optionalArg
import com.myai.assistant.agent.requireArg
import com.myai.assistant.util.ArabicText
import kotlinx.coroutines.delay

class OpenAppTool : Tool {
    override val id = "open_app"
    override val title = "فتح تطبيق"
    override val description = "يبحث عن تطبيق مثبّت باسمه الظاهر أو اسم الحزمة ويفتح واجهته الرئيسية."
    override val parameters = listOf(
        ToolParameter("app", "اسم التطبيق كما يظهر في الهاتف، مثل: واتساب، يوتيوب، الإعدادات")
    )

    override fun summary(args: Map<String, String>): String = "فتح تطبيق ${args["app"].orEmpty()}"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val query = args.requireArg("app")
        val entry = findApp(context.appContext, query)
            ?: return ToolOutcome.failed(
                "لم أجد تطبيقًا باسم \"$query\" على هذا الجهاز. جرّب الاسم كما يظهر في قائمة التطبيقات."
            )

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(entry.packageName, entry.activityName))
        val launched = IntentHelpers.tryLaunch(context.appContext, intent)
        if (!launched.success) {
            return ToolOutcome.failed(launched.message ?: "تعذّر فتح ${entry.label}.")
        }

        val screen = context.screen
        if (screen != null && screen.isConnected) {
            repeat(10) {
                delay(150)
                if (screen.foregroundPackage == entry.packageName) {
                    return ToolOutcome.verified("تم فتح ${entry.label} والتحقق من النافذة الأمامية.")
                }
            }
            return ToolOutcome.dispatched(
                "أُرسل أمر فتح ${entry.label}، لكن لم أتمكن من التحقق من النافذة الأمامية."
            )
        }
        return ToolOutcome.dispatched("أُرسل أمر فتح ${entry.label}. فعّل خدمة الوصولية للتحقق التلقائي.")
    }

    private data class AppEntry(
        val packageName: String,
        val activityName: String,
        val label: String,
        val score: Int
    )

    private fun findApp(context: Context, query: String): AppEntry? {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)
        val normalizedQuery = ArabicText.normalize(query)
        val target = ArabicText.stripDefiniteArticle(normalizedQuery)
        if (target.length < 2) return null
        val ownPackage = context.packageName

        val candidates = resolved.mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            val pkg = activityInfo.packageName ?: return@mapNotNull null
            if (pkg == ownPackage) return@mapNotNull null
            val label = runCatching { info.loadLabel(pm)?.toString()?.trim().orEmpty() }.getOrDefault("")
            val normalizedLabel = ArabicText.normalize(label)
            val shortLabel = ArabicText.stripDefiniteArticle(normalizedLabel)
            val normalizedPackage = ArabicText.normalize(pkg)
            val score = when {
                normalizedLabel == normalizedQuery || shortLabel == target -> 100
                normalizedPackage == normalizedQuery -> 95
                shortLabel.startsWith(target) -> 80
                normalizedLabel.startsWith(normalizedQuery) -> 78
                shortLabel.contains(target) -> 60
                normalizedPackage.contains(normalizedQuery) -> 40
                else -> 0
            }
            if (score == 0) null else AppEntry(
                packageName = pkg,
                activityName = activityInfo.name,
                label = label.ifBlank { pkg },
                score = score + label.length.coerceAtMost(20)
            )
        }
        return candidates.maxByOrNull { it.score }
    }
}

class OpenUrlTool : Tool {
    override val id = "open_url"
    override val title = "فتح رابط"
    override val description = "يفتح رابط http أو https في المتصفح الافتراضي."
    override val parameters = listOf(
        ToolParameter("url", "الرابط الكامل، مثل: https://example.com")
    )

    override fun summary(args: Map<String, String>): String = "فتح الرابط ${args["url"].orEmpty()}"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val url = normalizeUrl(args.requireArg("url"))
            ?: return ToolOutcome.failed("الرابط غير صالح. استخدم رابطًا يبدأ بـ https://")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val launched = IntentHelpers.tryLaunch(context.appContext, intent)
        return if (launched.success) {
            ToolOutcome.dispatched("أُرسل الرابط إلى المتصفح: $url")
        } else {
            ToolOutcome.failed(launched.message ?: "تعذّر فتح الرابط.")
        }
    }

    companion object {
        fun normalizeUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val candidate = when {
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> "https://$trimmed"
            }
            val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            return candidate
        }
    }
}

class WebSearchTool : Tool {
    override val id = "web_search"
    override val title = "بحث في الويب"
    override val description = "يفتح نتيجة بحث في المتصفح. محرك البحث الافتراضي DuckDuckGo، ويمكن تحديد google أو bing."
    override val parameters = listOf(
        ToolParameter("query", "نص البحث"),
        ToolParameter(
            name = "engine",
            description = "محرك البحث",
            required = false,
            allowedValues = listOf("duckduckgo", "google", "bing")
        )
    )

    override fun summary(args: Map<String, String>): String = "البحث في الويب عن \"${args["query"].orEmpty()}\""

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val query = args.requireArg("query")
        val engine = args.optionalArg("engine")?.lowercase() ?: "duckduckgo"
        val encoded = Uri.encode(query)
        val url = when (engine) {
            "google" -> "https://www.google.com/search?q=$encoded"
            "bing" -> "https://www.bing.com/search?q=$encoded"
            else -> "https://duckduckgo.com/?q=$encoded"
        }
        val launched = IntentHelpers.tryLaunch(context.appContext, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return if (launched.success) {
            ToolOutcome.dispatched("أُرسل بحث \"$query\" إلى المتصفح عبر $engine.")
        } else {
            ToolOutcome.failed(launched.message ?: "تعذّر فتح نتيجة البحث.")
        }
    }
}
