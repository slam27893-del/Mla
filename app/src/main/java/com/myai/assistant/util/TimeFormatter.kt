package com.myai.assistant.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormatter {
    private val arabic = Locale.forLanguageTag("ar")

    fun clock(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(timestamp))

    fun fullNow(): String =
        SimpleDateFormat("EEEE d MMMM yyyy HH:mm", arabic).format(Date())

    fun duration(millis: Long): String =
        if (millis < 1000) "$millis ms" else String.format(Locale.US, "%.1f s", millis / 1000.0)
}
