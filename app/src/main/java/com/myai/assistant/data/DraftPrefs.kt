package com.myai.assistant.data

import android.content.Context

/** What the user allowed the draft reader to send. */
enum class VisionConsent {
    /** Never send the screenshot; local rules + manual correction only. */
    NEVER,

    /** Ask every time a capture happens (default). */
    ASK,

    /** The user pressed "دائمًا اسمح" explicitly. */
    ALWAYS;

    companion object {
        fun fromName(raw: String?): VisionConsent =
            entries.firstOrNull { it.name == raw } ?: ASK
    }
}

/**
 * Draft-only preferences, in their own file so the privacy switch cannot be
 * confused with the general assistant settings.
 *
 * The default is [VisionConsent.ASK]: nothing is uploaded unless the user asked
 * for it in the dialog, and "محلي فقط" is always one tap away.
 */
class DraftPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var visionConsent: VisionConsent
        get() = VisionConsent.fromName(prefs.getString(KEY_VISION, VisionConsent.ASK.name))
        set(value) {
            prefs.edit().putString(KEY_VISION, value.name).apply()
        }

    var bubbleEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BUBBLE, value).apply()
        }

    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_BUBBLE_X, value).apply()
        }

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()
        }

    var panelX: Int
        get() = prefs.getInt(KEY_PANEL_X, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_PANEL_X, value).apply()
        }

    var panelY: Int
        get() = prefs.getInt(KEY_PANEL_Y, Int.MIN_VALUE)
        set(value) {
            prefs.edit().putInt(KEY_PANEL_Y, value).apply()
        }

    var panelCollapsed: Boolean
        get() = prefs.getBoolean(KEY_COLLAPSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_COLLAPSED, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "myai.draft"
        const val KEY_VISION = "vision_consent"
        const val KEY_BUBBLE = "draft_bubble"
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_Y = "bubble_y"
        const val KEY_PANEL_X = "panel_x"
        const val KEY_PANEL_Y = "panel_y"
        const val KEY_COLLAPSED = "panel_collapsed"
    }
}
