package com.myai.assistant.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.myai.assistant.R
import com.myai.assistant.draft.DraftAnalysis
import com.myai.assistant.data.DraftPrefs
import com.myai.assistant.draft.Recommendation
import com.myai.assistant.databinding.OverlayDraftBinding
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.coroutines.resume

/**
 * The small floating draft card.
 *
 * Layout rules that keep it out of the way while playing:
 * * 300dp wide, wrapped height, default position top-start (the pick grid and the
 *   mode banner are not covered).
 * * `FLAG_NOT_TOUCH_MODAL` + `FLAG_NOT_FOCUSABLE`, so every tap outside the card
 *   reaches the game untouched — the app never blocks input.
 * * Draggable from the header only, collapsible to its title row, and the position
 *   is remembered.
 */
class DraftOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val prefs: DraftPrefs,
    private val onClose: () -> Unit,
    private val onRefresh: () -> Unit,
    private val onEdit: () -> Unit,
    private val onVisionSettings: () -> Unit
) {

    private var binding: OverlayDraftBinding? = null
    private var params: WindowManager.LayoutParams? = null
    private var consentBinding: View? = null
    private var consentContinuation: CancellableContinuation<Boolean>? = null

    val isShowing: Boolean get() = binding != null

    fun show() {
        if (binding != null) return
        val view = OverlayDraftBinding.inflate(LayoutInflater.from(context))
        binding = view

        val metrics = context.resources.displayMetrics
        val width = dp(300)
        val p = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.panelX.takeIf { it != Int.MIN_VALUE } ?: dp(8)
            y = prefs.panelY.takeIf { it != Int.MIN_VALUE } ?: dp(72)
            x = x.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
            y = y.coerceIn(0, (metrics.heightPixels - dp(120)).coerceAtLeast(0))
        }
        params = p

        view.closeButton.setOnClickListener { hide() }
        view.refreshButton.setOnClickListener { onRefresh() }
        view.editButton.setOnClickListener { onEdit() }
        view.visionButton.setOnClickListener { onVisionSettings() }
        view.collapseButton.setOnClickListener { setCollapsed(!collapsed) }
        attachDrag(view, p)

        val added = runCatching { windowManager.addView(view.root, p) }.isSuccess
        if (!added) {
            binding = null
            params = null
            return
        }
        setCollapsed(prefs.panelCollapsed)
        setStatus(context.getString(R.string.draft_status_idle))
    }

    fun hide() {
        consentContinuation?.let { cont ->
            consentContinuation = null
            if (cont.isActive) cont.resume(false)
        }
        consentBinding?.let { runCatching { windowManager.removeView(it) } }
        consentBinding = null
        binding?.let { runCatching { windowManager.removeView(it.root) } }
        binding = null
        params = null
        onClose()
    }

    fun setStatus(text: String) {
        binding?.draftStatus?.text = text
    }

    fun showBusy(text: String) {
        val view = binding ?: return
        view.draftStatus.text = text
        view.draftResults.removeAllViews()
        view.draftNote.visibility = View.GONE
    }

    fun showError(text: String) {
        val view = binding ?: return
        view.draftStatus.text = text
        view.draftResults.removeAllViews()
        view.draftNote.visibility = View.GONE
    }

    /** Renders the three recommendations plus the honesty footer. */
    fun showAnalysis(analysis: DraftAnalysis, sourceLabel: String, extraNote: String? = null) {
        val view = binding ?: return
        view.draftSource.text = sourceLabel
        val confidence = context.getString(R.string.draft_confidence, analysis.confidence)
        view.draftStatus.text = buildString {
            append(context.getString(R.string.draft_known_conf, confidence))
            val missing = analysis.context.missingFields()
            if (missing.isNotEmpty()) {
                append(" • ")
                append(context.getString(R.string.draft_missing, missing.joinToString("، ")))
            }
        }

        view.draftResults.removeAllViews()
        if (analysis.recommendations.isEmpty()) {
            view.draftResults.addView(textView(context.getString(R.string.draft_nothing), 12f, R.color.error))
        } else {
            analysis.recommendations.forEachIndexed { index, rec ->
                view.draftResults.addView(recommendationRow(index + 1, rec))
            }
        }

        val notes = buildList {
            extraNote?.let { add(it) }
            addAll(analysis.notes)
        }
        view.draftNote.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        view.draftNote.text = notes.joinToString("\n")
    }

    fun setCollapsed(value: Boolean) {
        val view = binding ?: return
        prefs.panelCollapsed = value
        view.draftBody.visibility = if (value) View.GONE else View.VISIBLE
        view.collapseButton.rotation = if (value) 180f else 0f
    }

    private val collapsed: Boolean get() = prefs.panelCollapsed

    private fun recommendationRow(rank: Int, rec: Recommendation): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_draft_row)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        val title = TextView(context).apply {
            text = context.getString(
                R.string.draft_row_title,
                rank,
                rec.brawler.name,
                rec.brawler.cls.labelAr,
                rec.score
            )
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
        }
        val reasons = TextView(context).apply {
            text = rec.reasons.joinToString(" • ")
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, R.color.muted))
        }
        row.addView(title)
        row.addView(reasons)
        if (rec.caveats.isNotEmpty()) {
            row.addView(
                TextView(context).apply {
                    text = rec.caveats.joinToString(" • ")
                    textSize = 9f
                    setTextColor(ContextCompat.getColor(context, R.color.draft_accent))
                }
            )
        }
        return row
    }

    private fun textView(text: String, size: Float, colorRes: Int) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(ContextCompat.getColor(context, colorRes))
    }

    private fun attachDrag(view: OverlayDraftBinding, p: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false

        view.draftHeader.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        val metrics = context.resources.displayMetrics
                        p.x = (startX + dx).toInt().coerceIn(0, (metrics.widthPixels - dp(300)).coerceAtLeast(0))
                        p.y = (startY + dy).toInt().coerceIn(0, (metrics.heightPixels - dp(80)).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(view.root, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.panelX = p.x
                        prefs.panelY = p.y
                    } else if (collapsed) {
                        setCollapsed(false)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Asks whether the screenshot may be uploaded. Returns false for "local only".
     * A separate small window so it works above the game and above the card.
     */
    suspend fun askVisionConsent(modelLabel: String, rememberable: Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (consentBinding != null) return@withContext false
            suspendCancellableCoroutine { cont ->
                val view = LayoutInflater.from(context).inflate(R.layout.overlay_draft_consent, null)
                consentBinding = view
                consentContinuation = cont

                view.findViewById<TextView>(R.id.consentBody).text =
                    context.getString(R.string.draft_consent_body_model, modelLabel)

                view.findViewById<View>(R.id.consentSend).setOnClickListener { finishConsent(true) }
                view.findViewById<View>(R.id.consentLocal).setOnClickListener { finishConsent(false) }
                view.findViewById<View>(R.id.consentAlways).apply {
                    visibility = if (rememberable) View.VISIBLE else View.GONE
                    setOnClickListener { finishConsent(true, remember = true) }
                }
                view.findViewById<View>(R.id.consentRoot).setOnClickListener { finishConsent(false) }
                cont.invokeOnCancellation { removeConsentWindow() }

                val p = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.TRANSLUCENT
                ).apply { dimAmount = 0.45f }
                val added = runCatching { windowManager.addView(view, p) }.isSuccess
                if (!added) {
                    consentBinding = null
                    consentContinuation = null
                    if (cont.isActive) cont.resume(false)
                }
            }
        }

    /** Set by the session so "دائمًا اسمح" can persist the choice. */
    var onAlwaysAllow: (() -> Unit)? = null

    private fun finishConsent(approved: Boolean, remember: Boolean = false) {
        if (remember && approved) onAlwaysAllow?.invoke()
        val cont = consentContinuation ?: return
        consentContinuation = null
        removeConsentWindow()
        if (cont.isActive) cont.resume(approved)
    }

    private fun removeConsentWindow() {
        consentBinding?.let { runCatching { windowManager.removeView(it) } }
        consentBinding = null
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
