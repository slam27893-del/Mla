package com.myai.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.KeyEvent
import com.myai.assistant.agent.RiskLevel
import com.myai.assistant.agent.Tool
import com.myai.assistant.agent.ToolContext
import com.myai.assistant.agent.ToolOutcome
import com.myai.assistant.agent.ToolParameter
import com.myai.assistant.agent.optionalArg
import com.myai.assistant.agent.requireArg
import java.util.Locale

class MediaControlTool : Tool {
    override val id = "media_control"
    override val title = "التحكم في الوسائط"
    override val description = "يرسل مفاتيح وسائط نظامية (تشغيل/إيقاف/التالي/السابق) إلى التطبيق الذي يشغّل الصوت. قد لا يستجيب تطبيق لا يدعم مفاتيح الوسائط."
    override val parameters = listOf(
        ToolParameter(
            name = "action",
            description = "إجراء الوسائط",
            allowedValues = listOf("play", "pause", "toggle", "next", "previous")
        )
    )
    override val risk = RiskLevel.SENSITIVE

    override fun summary(args: Map<String, String>): String {
        val label = when (args["action"]) {
            "play" -> "تشغيل"
            "pause" -> "إيقاف مؤقت"
            "next" -> "التالي"
            "previous" -> "السابق"
            else -> "تبديل"
        }
        return "$label الوسائط"
    }

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val action = args.requireArg("action").lowercase(Locale.US)
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return ToolOutcome.failed("إجراء وسائط غير معروف: $action")
        }
        val audio = context.appContext.getSystemService(AudioManager::class.java)
            ?: return ToolOutcome.failed("خدمة الصوت غير متاحة على هذا الجهاز.")
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return ToolOutcome.dispatched(
            "أُرسل مفتاح الوسائط ($action). لا يمكن للتطبيق التأكد من استجابة مشغّل الصوت."
        )
    }
}

class FlashlightTool : Tool {
    override val id = "flashlight"
    override val title = "الكشاف"
    override val description = "يشغّل أو يطفئ كشاف الكاميرا الخلفية عبر CameraManager. لا يُلتقط أي صورة."
    override val parameters = listOf(
        ToolParameter(
            name = "state",
            description = "حالة الكشاف",
            allowedValues = listOf("on", "off", "toggle")
        )
    )
    override val risk = RiskLevel.SENSITIVE

    override fun summary(args: Map<String, String>): String = when (args["state"]) {
        "off" -> "إطفاء الكشاف"
        "toggle" -> "تبديل حالة الكشاف"
        else -> "تشغيل الكشاف"
    }

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val state = args.optionalArg("state")?.lowercase(Locale.US) ?: "on"
        val cameraManager = context.appContext.getSystemService(CameraManager::class.java)
            ?: return ToolOutcome.failed("خدمة الكاميرا غير متاحة على هذا الجهاز.")
        val cameraId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull() ?: return ToolOutcome.failed("لم أجد كشافًا خلفيًا على هذا الهاتف.")

        val desired = when (state) {
            "off" -> false
            "on" -> true
            "toggle" -> !(lastKnownState ?: false)
            else -> return ToolOutcome.failed("حالة الكشاف غير معروفة: $state")
        }
        return try {
            cameraManager.setTorchMode(cameraId, desired)
            lastKnownState = desired
            ToolOutcome.verified(if (desired) "تم تشغيل الكشاف." else "تم إطفاء الكشاف.")
        } catch (e: CameraAccessException) {
            ToolOutcome.failed("تعذّر تغيير حالة الكشاف (قد تكون الكاميرا مستخدمة من تطبيق آخر): ${e.reason}")
        } catch (e: SecurityException) {
            ToolOutcome.failed("إذن الكاميرا مطلوب لتشغيل الكشاف. امنحه من مركز الأذونات.")
        }
    }

    private companion object {
        @Volatile
        var lastKnownState: Boolean? = null
    }
}

class DeviceStatusTool : Tool {
    override val id = "device_status"
    override val title = "حالة الجهاز"
    override val description = "يقرأ نسبة البطارية وحالة الشحن والشبكة والمساحة المتاحة وإصدار النظام. قراءة محلية فقط."
    override val parameters = emptyList<ToolParameter>()

    /** Read-only local query: nothing leaves the device, nothing changes. */
    override fun summary(args: Map<String, String>): String = "قراءة حالة البطارية والشبكة والمساحة"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val app = context.appContext
        val batteryIntent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.let { it.getNetworkCapabilities(it.activeNetwork) }
        val network = when {
            capabilities == null -> "غير متصل"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "واي فاي"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "بيانات الجوال"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "إيثرنت"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "شبكة أخرى"
        }

        val stat = StatFs(app.filesDir.absolutePath)
        val freeGb = stat.availableBytes / 1_073_741_824.0
        val totalGb = stat.totalBytes / 1_073_741_824.0

        val powerManager = app.getSystemService(PowerManager::class.java)
        val powerSave = powerManager?.isPowerSaveMode == true

        val text = buildString {
            appendLine("الهاتف: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("البطارية: ${if (percent >= 0) "$percent%" else "غير معروفة"}${if (charging) " — تشحن" else ""}")
            appendLine("توفير الطاقة: ${if (powerSave) "مفعّل" else "غير مفعّل"}")
            appendLine("الشبكة: $network")
            appendLine(
                "مساحة التخزين: ${String.format(Locale.US, "%.1f", freeGb)} GB متاحة من " +
                    "${String.format(Locale.US, "%.1f", totalGb)} GB"
            )
        }.trim()

        return ToolOutcome.verified(text)
    }
}

/**
 * Honest by design: Android does not let third-party apps flip protected toggles
 * such as Wi-Fi, mobile data or airplane mode. This tool only opens the correct
 * system screen and tells the user to change it. See docs/PERMISSIONS.md.
 */
class OpenSettingsTool : Tool {
    override val id = "open_settings"
    override val title = "فتح إعدادات النظام"
    override val description = "يفتح شاشة إعدادات محددة (واي فاي، بلوتوث، صوت، شاشة، موقع، إمكانية الوصول، التطبيقات...). لا يغيّر الإعدادات بنفسه لأن Android يمنع ذلك برمجيًا."
    override val parameters = listOf(
        ToolParameter(
            name = "panel",
            description = "الشاشة المطلوبة",
            required = false,
            allowedValues = listOf(
                "settings", "wifi", "bluetooth", "sound", "display", "battery", "location",
                "accessibility", "apps", "notifications", "data", "default_apps", "overlay", "app_details"
            )
        )
    )

    override fun summary(args: Map<String, String>): String =
        "فتح شاشة إعدادات (${args["panel"] ?: "settings"})"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val panel = args.optionalArg("panel")?.lowercase(Locale.US) ?: "settings"
        val intent = intentFor(context.appContext, panel)
        val launched = IntentHelpers.tryLaunch(context.appContext, intent)
        if (launched.success) {
            return ToolOutcome.dispatched("فُتحت شاشة الإعدادات المطلوبة. التغيير نفسه يتم بيدك.")
        }
        if (panel != "settings") {
            val fallback = IntentHelpers.tryLaunch(
                context.appContext,
                Intent(Settings.ACTION_SETTINGS)
            )
            if (fallback.success) {
                return ToolOutcome.dispatched(
                    "شاشة الإعدادات المطلوبة غير متاحة على هذا الإصدار؛ فُتحت إعدادات النظام العامة."
                )
            }
        }
        return ToolOutcome.failed(launched.message ?: "تعذّر فتح إعدادات النظام.")
    }

    private fun intentFor(context: Context, panel: String): Intent = when (panel) {
        "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
        "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
        "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        "data" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        "default_apps" -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        "overlay" -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        "app_details" -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        else -> Intent(Settings.ACTION_SETTINGS)
    }
}
