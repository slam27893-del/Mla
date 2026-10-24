package com.myai.assistant.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.myai.assistant.accessibility.MyAiAccessibilityService

object PermissionUtils {

    fun isOverlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun anyNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Reads the system setting directly instead of relying only on the live
     * service instance: the service may be enabled but not yet bound.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, MyAiAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { entry ->
            entry.equals(expected.flattenToString(), ignoreCase = true) ||
                entry.equals(expected.flattenToShortString(), ignoreCase = true)
        }
    }
}
