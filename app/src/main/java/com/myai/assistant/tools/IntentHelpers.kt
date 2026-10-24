package com.myai.assistant.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

data class LaunchResult(val success: Boolean, val message: String? = null)

object IntentHelpers {

    /**
     * Starts an activity from a non-Activity context. Any failure is turned into
     * an honest Arabic message instead of a silent no-op.
     */
    fun tryLaunch(context: Context, intent: Intent): LaunchResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            LaunchResult(true)
        } catch (e: ActivityNotFoundException) {
            LaunchResult(false, "لا يوجد تطبيق على الهاتف يمكنه تنفيذ هذا الإجراء.")
        } catch (e: SecurityException) {
            LaunchResult(false, "رفض Android تنفيذ هذا الإجراء: ${e.message ?: "بدون تفاصيل"}")
        } catch (e: Exception) {
            LaunchResult(false, "تعذّر فتح الشاشة المطلوبة: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
