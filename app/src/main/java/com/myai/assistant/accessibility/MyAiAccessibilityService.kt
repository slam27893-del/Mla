package com.myai.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.myai.assistant.agent.ScreenActionResult
import com.myai.assistant.agent.ScreenController
import com.myai.assistant.agent.ScreenSnapshot
import com.myai.assistant.util.ArabicText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * OPTIONAL service. It is only connected after the user explicitly enables it in
 * Android settings; until then every screen tool fails with a clear message.
 *
 * The service never uploads anything. It exposes a narrow [ScreenController]
 * API; reads happen only when a tool asks for a snapshot.
 */
class MyAiAccessibilityService : AccessibilityService(), ScreenController {

    @Volatile
    override var isConnected: Boolean = false
        private set

    @Volatile
    override var foregroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        AccessibilityBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { foregroundPackage = it }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        isConnected = false
        AccessibilityBridge.detach(this)
    }

    // ------------------------------------------------------------- snapshot

    override suspend fun snapshot(): ScreenSnapshot = withContext(Dispatchers.Main.immediate) {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val texts = LinkedHashSet<String>()
        val clickable = LinkedHashSet<String>()
        if (root != null) {
            runCatching { collect(root, texts, clickable, 0) }
        }
        ScreenSnapshot(
            packageName = foregroundPackage ?: root?.packageName?.toString(),
            texts = texts.toList(),
            clickableTexts = clickable.toList()
        )
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        texts: MutableSet<String>,
        clickable: MutableSet<String>,
        depth: Int
    ) {
        if (depth > 25 || texts.size > 150) return
        val label = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (label != null) {
            texts += label.take(140)
            if (node.isClickable) clickable += label.take(140)
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collect(child, texts, clickable, depth + 1)
        }
    }

    // ------------------------------------------------------------- actions

    override suspend fun clickText(text: String): ScreenActionResult =
        withContext(Dispatchers.Main.immediate) {
            val target = ArabicText.normalize(text)
            if (target.isEmpty()) return@withContext failure("لم يُحدَّد نص للنقر.")
            val root = runCatching { rootInActiveWindow }.getOrNull()
                ?: return@withContext failure("لا توجد نافذة نشطة الآن.")
            val found = runCatching { findNode(root, target, 0) }.getOrNull()
                ?: return@withContext failure("لم أجد عنصرًا مطابقًا لـ \"$text\" على الشاشة.")

            var clickable: AccessibilityNodeInfo? = found
            var hops = 0
            while (clickable != null && !clickable.isClickable && hops < 6) {
                clickable = runCatching { clickable?.parent }.getOrNull()
                hops++
            }
            val actionTarget = clickable ?: found
            val clicked = runCatching {
                actionTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)

            if (clicked) {
                return@withContext ScreenActionResult(true, "تم النقر على \"$text\".", verified = true)
            }

            val bounds = Rect().also { found.getBoundsInScreen(it) }
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return@withContext failure("العنصر \"$text\" بلا حدود قابلة للنقر.")
            }
            val tapped = tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            if (tapped) {
                // The gesture was delivered, but we cannot prove the app reacted.
                ScreenActionResult(true, "أُرسلت إيماءة نقر على \"$text\" (دون تحقق من الاستجابة).", verified = false)
            } else {
                failure("لم يتمكن Android من النقر على \"$text\".")
            }
        }

    override suspend fun setText(text: String, targetText: String?): ScreenActionResult =
        withContext(Dispatchers.Main.immediate) {
            val root = runCatching { rootInActiveWindow }.getOrNull()
                ?: return@withContext failure("لا توجد نافذة نشطة الآن.")
            val node = (targetText?.let { findNode(root, ArabicText.normalize(it), 0) }?.let { climbToEditable(it) })
                ?: runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                ?: return@withContext failure("لم أجد حقلًا نشطًا للكتابة. اضغط على الحقل أولًا.")

            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val set = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }.getOrDefault(false)
            if (!set) {
                return@withContext failure("رفض التطبيق الحالي تعديل النص في هذا الحقل.")
            }
            runCatching { node.refresh() }
            val actual = node.text?.toString().orEmpty()
            if (actual == text) {
                ScreenActionResult(true, "تمت كتابة النص والتحقق منه.", verified = true)
            } else {
                ScreenActionResult(true, "أُرسل النص إلى الحقل، لكن لم نتحقق من قيمته النهائية.", verified = false)
            }
        }

    override suspend fun scroll(direction: String): ScreenActionResult =
        withContext(Dispatchers.Main.immediate) {
            val root = runCatching { rootInActiveWindow }.getOrNull()
                ?: return@withContext failure("لا توجد نافذة نشطة الآن.")
            val scrollable = runCatching { findScrollable(root, 0) }.getOrNull() ?: root
            val action = if (direction == "up") {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val scrolled = runCatching { scrollable.performAction(action) }.getOrDefault(false)
            if (scrolled) {
                ScreenActionResult(true, if (direction == "up") "تم التمرير لأعلى." else "تم التمرير لأسفل.")
            } else {
                failure("لا توجد منطقة قابلة للتمرير في الشاشة الحالية.")
            }
        }

    override suspend fun globalAction(action: String): ScreenActionResult =
        withContext(Dispatchers.Main.immediate) {
            val code = when (action.lowercase()) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                else -> return@withContext failure("إجراء نظام غير معروف: $action")
            }
            val done = runCatching { performGlobalAction(code) }.getOrDefault(false)
            if (done) {
                ScreenActionResult(true, "تم إرسال إجراء النظام ($action).", verified = true)
            } else {
                failure("رفض Android تنفيذ إجراء النظام ($action).")
            }
        }

    // ------------------------------------------------------------- helpers

    private fun findNode(node: AccessibilityNodeInfo, target: String, depth: Int): AccessibilityNodeInfo? {
        if (depth > 25) return null
        val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        val matched = labels.any { label ->
            val normalized = ArabicText.normalize(label)
            normalized == target ||
                normalized.contains(target) ||
                (normalized.length >= 3 && target.contains(normalized))
        }
        if (matched) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            findNode(child, target, depth + 1)?.let { return it }
        }
        return null
    }

    private fun climbToEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && !current.isEditable && hops < 6) {
            current = runCatching { current?.parent }.getOrNull()
            hops++
        }
        if (current?.isEditable == true) return current
        return findEditableDescendant(node, 0)
    }

    private fun findEditableDescendant(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 8) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            findEditableDescendant(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 25) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            findScrollable(child, depth + 1)?.let { return it }
        }
        return null
    }

    private suspend fun tap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { continuation ->
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (continuation.isActive) continuation.resume(false)
            }
        }
        val dispatched = runCatching { dispatchGesture(gesture, callback, null) }.getOrDefault(false)
        if (!dispatched && continuation.isActive) continuation.resume(false)
    }

    private fun failure(message: String) = ScreenActionResult(false, message, verified = false)
}
