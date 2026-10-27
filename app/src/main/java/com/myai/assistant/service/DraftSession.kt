package com.myai.assistant.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import android.view.WindowManager
import com.myai.assistant.R
import com.myai.assistant.accessibility.AccessibilityBridge
import com.myai.assistant.ai.ProviderConfig
import com.myai.assistant.capture.CaptureBus
import com.myai.assistant.capture.CaptureConsentActivity
import com.myai.assistant.ai.DraftVisionClient
import com.myai.assistant.data.DraftPrefs
import com.myai.assistant.data.EngineType
import com.myai.assistant.data.SettingsRepository
import com.myai.assistant.data.VisionConsent
import com.myai.assistant.draft.DraftAnalysis
import com.myai.assistant.draft.DraftContext
import com.myai.assistant.draft.DraftEngine
import com.myai.assistant.draft.DraftJsonParser
import com.myai.assistant.draft.DraftTextParser
import com.myai.assistant.draft.RecognitionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Orchestrates one draft read: capture → recognise → rank → render.
 *
 * Rules this class enforces (each is also covered by a unit test):
 * 1. **Capture only on tap.** Nothing here schedules captures; a frame is taken
 *    only from [togglePanel] or the refresh button.
 * 2. **One frame in memory.** The bitmap is recycled in `finally`, never written
 *    to disk, never kept between analyses.
 * 3. **No silent upload.** The image leaves the device only when the user's own
 *    setting says so ([VisionConsent]); the default is "never".
 * 4. **No guessing.** Whatever the reader cannot see remains `null`/`UNKNOWN`, is
 *    listed in the panel, and can be corrected in [DraftEditDialog].
 * 5. **No automation.** Recommendations are text; this class never taps, selects
 *    or sends any input to the game.
 */
class DraftSession(
    private val serviceContext: Context,
    private val themedContext: Context,
    windowManager: WindowManager,
    private val prefs: DraftPrefs,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope
) {

    private var job: Job? = null
    private var editDialog: DraftEditDialog? = null
    private var lastContext: DraftContext = DraftContext()

    private val overlay = DraftOverlay(
        context = themedContext,
        windowManager = windowManager,
        prefs = prefs,
        onClose = { /* bubble stays available */ },
        onRefresh = { analyzeFromCapture() },
        onEdit = { openEditor() },
        onVisionSettings = { openSettings() }
    )

    init {
        overlay.onAlwaysAllow = { prefs.visionConsent = VisionConsent.ALWAYS }
    }

    val isPanelShowing: Boolean get() = overlay.isShowing

    fun togglePanel() {
        if (overlay.isShowing) {
            overlay.hide()
            return
        }
        overlay.show()
        analyzeFromCapture()
    }

    fun close() {
        job?.cancel()
        editDialog?.dismiss()
        overlay.hide()
    }

    /**
     * Entry point of the draft bubble.
     *
     * Consent is decided **before** any pixel is read: if the user keeps vision off
     * (or answers "local only" here), no screenshot is taken at all and the analysis
     * runs on the accessibility text they already allowed. This is why the local
     * path never triggers Android's screen-capture dialog.
     */
    fun analyzeFromCapture() {
        if (job?.isActive == true) return
        job = scope.launch {
            val client = visionClient()
            val ready = client != null && client.unavailableReason() == null
            val useVision = when {
                !ready -> false
                prefs.visionConsent == VisionConsent.ALWAYS -> true
                prefs.visionConsent == VisionConsent.ASK -> askConsent(client!!)
                else -> false
            }

            if (!useVision) {
                analyzeFromText(
                    note = when {
                        !ready && prefs.visionConsent != VisionConsent.NEVER ->
                            serviceContext.getString(R.string.draft_vision_not_configured)
                        else -> serviceContext.getString(R.string.draft_vision_off_note)
                    }
                )
                return@launch
            }

            overlay.showBusy(serviceContext.getString(R.string.draft_reading_screen))
            val bitmap = runCatching { captureScreen() }.getOrNull()
            if (bitmap == null) {
                overlay.showError(serviceContext.getString(R.string.draft_no_screenshot))
                return@launch
            }
            try {
                readWithVision(bitmap, client!!)
            } finally {
                runCatching { bitmap.recycle() }
            }
        }
    }

    // ----------------------------------------------------------- capture

    private suspend fun captureScreen(): Bitmap? {
        // 1) Accessibility screenshot: no system dialog, but only when the user
        //    already enabled the optional service.
        if (AccessibilityBridge.canScreenshot) {
            AccessibilityBridge.screenshot()?.let { return it }
        }
        // 2) MediaProjection one-shot with Android's own consent dialog.
        return captureWithConsent()
    }

    private suspend fun captureWithConsent(): Bitmap? = withTimeoutOrNull(CONSENT_TIMEOUT_MS) {
        suspendCancellableCoroutine<Bitmap?> { cont ->
            CaptureBus.register { outcome ->
                if (!cont.isActive) return@register
                when (outcome) {
                    is CaptureBus.Outcome.Success -> cont.resume(outcome.bitmap)
                    is CaptureBus.Outcome.Failure -> cont.resume(null)
                }
            }
            val intent = Intent(serviceContext, CaptureConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val started = runCatching { serviceContext.startActivity(intent) }.isSuccess
            if (!started) {
                CaptureBus.fail("تعذّر فتح نافذة صلاحية الالتقاط.")
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { CaptureBus.clear() }
        }
    }

    // ----------------------------------------------------------- analysis

    /**
     * Shows the per-capture consent window unless the user already pressed
     * "دائمًا اسمح" (which flips the stored setting to ALWAYS through the overlay).
     */
    private suspend fun askConsent(client: DraftVisionClient): Boolean {
        if (prefs.visionConsent == VisionConsent.ALWAYS) return true
        return overlay.askVisionConsent(client.model, rememberable = true)
    }

    private suspend fun readWithVision(bitmap: Bitmap, client: DraftVisionClient) {
        overlay.showBusy(serviceContext.getString(R.string.draft_reading_image, client.model))
        val jpeg = encodeJpeg(bitmap)
        val outcome = runCatching { client.read(jpeg, consentGranted = true) }
        outcome.fold(
            onSuccess = { vision ->
                val context = vision.context
                if (context.knownFieldCount == 0) {
                    analyzeFromText(
                        note = vision.parseError ?: serviceContext.getString(R.string.draft_vision_empty)
                    )
                    return
                }
                lastContext = context
                render(
                    analysis = DraftEngine.analyze(context),
                    sourceLabel = serviceContext.getString(R.string.draft_source_vision, client.model),
                    extraNote = serviceContext.getString(R.string.draft_vision_sent_note)
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                analyzeFromText(
                    note = serviceContext.getString(
                        R.string.draft_vision_failed,
                        error.message ?: "خطأ غير معروف"
                    )
                )
            }
        )
    }

    /** Local path: screen text (if accessibility is on) + whatever the user corrected. */
    private suspend fun analyzeFromText(note: String) {
        val text = runCatching {
            AccessibilityBridge.controller?.snapshot()?.texts?.joinToString("\n").orEmpty()
        }.getOrDefault("")

        val parsed = if (text.isNotBlank()) DraftTextParser.parse(text) else null
        val context = when {
            parsed?.isUsable == true -> parsed.context
            lastContext.knownFieldCount > 0 -> lastContext
            else -> parsed?.context ?: lastContext
        }
        lastContext = context

        if (context.knownFieldCount == 0) {
            overlay.showError(note + " " + serviceContext.getString(R.string.draft_edit_instead))
            openEditor()
            return
        }
        render(
            analysis = DraftEngine.analyze(context),
            sourceLabel = if (context.source == RecognitionSource.MANUAL) {
                serviceContext.getString(R.string.draft_source_manual)
            } else {
                serviceContext.getString(R.string.draft_source_text)
            },
            extraNote = note
        )
    }

    private fun render(analysis: DraftAnalysis, sourceLabel: String, extraNote: String?) {
        overlay.showAnalysis(analysis, sourceLabel, extraNote)
    }

    // ----------------------------------------------------------- editor

    private fun openEditor() {
        editDialog?.dismiss()
        val dialog = DraftEditDialog(themedContext, lastContext) { corrected ->
            lastContext = corrected.copy(source = RecognitionSource.MANUAL)
            render(
                analysis = DraftEngine.analyze(lastContext),
                sourceLabel = serviceContext.getString(R.string.draft_source_manual),
                extraNote = serviceContext.getString(R.string.draft_manual_note)
            )
        }
        editDialog = dialog
        dialog.show()
    }

    private fun openSettings() {
        val intent = Intent(serviceContext, com.myai.assistant.ui.SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { serviceContext.startActivity(intent) }
            .onFailure { overlay.showError(serviceContext.getString(R.string.draft_settings_failed)) }
    }

    // ----------------------------------------------------------- helpers

    private fun visionClient(): DraftVisionClient? {
        val snapshot = settings.settings.value
        if (snapshot.engineType != EngineType.OPENAI_COMPATIBLE) return null
        if (snapshot.baseUrl.isBlank() || snapshot.model.isBlank()) return null
        return DraftVisionClient(
            config = ProviderConfig(
                baseUrl = snapshot.baseUrl,
                model = snapshot.model,
                apiKey = settings.apiKey(),
                jsonMode = true,
                maxTokens = 700
            )
        )
    }

    /** Downscale + JPEG + base64, all in memory. Nothing is written to disk. */
    private fun encodeJpeg(bitmap: Bitmap): String {
        val maxWidth = 1280
        val scaled = if (bitmap.width > maxWidth) {
            val ratio = maxWidth.toFloat() / bitmap.width
            Bitmap.createScaledBitmap(
                bitmap,
                maxWidth,
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val bytes = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    /** Parsing helper kept public for tests that replay a model answer. */
    fun parseModelAnswer(raw: String) = DraftJsonParser.parse(raw)

    private companion object {
        const val CONSENT_TIMEOUT_MS = 60_000L
        const val JPEG_QUALITY = 80
    }
}
