package com.myai.assistant.service

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.myai.assistant.R
import com.myai.assistant.draft.BrawlerCatalog
import com.myai.assistant.draft.DraftContext
import com.myai.assistant.draft.GameMode
import com.myai.assistant.draft.RecognitionSource

/**
 * Manual correction sheet, shown above the game.
 *
 * Requirement #6: anything the reader could not see must be fixable by hand, and
 * an unrecognised name must be reported instead of guessed. The dialog therefore
 * keeps unknown names and hands them back as `unresolved` for the analysis notes.
 */
class DraftEditDialog(
    private val context: Context,
    private val initial: DraftContext,
    private val onResult: (DraftContext) -> Unit
) {

    private var dialog: Dialog? = null

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_draft_edit, null)
        val dialog = Dialog(context, R.style.Theme_MyAi)
        dialog.setContentView(view)
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setCanceledOnTouchOutside(true)
        this.dialog = dialog

        val mapField = view.findViewById<EditText>(R.id.fieldMap)
        val alliesField = view.findViewById<EditText>(R.id.fieldAllies)
        val enemiesField = view.findViewById<EditText>(R.id.fieldEnemies)
        val bansField = view.findViewById<EditText>(R.id.fieldBans)
        val errorText = view.findViewById<TextView>(R.id.fieldError)
        val modeField = view.findViewById<TextView>(R.id.fieldMode)
        val slotField = view.findViewById<TextView>(R.id.fieldSlot)

        mapField.setText(initial.map.orEmpty())
        alliesField.setText(initial.allies.joinToString("، "))
        enemiesField.setText(initial.enemies.joinToString("، "))
        bansField.setText(initial.bans.joinToString("، "))

        // Tapping cycles the value: a Spinner would open a PopupWindow on an
        // overlay window, which some OEM ROMs reject with a BadTokenException.
        val modes = GameMode.entries
        var modeIndex = modes.indexOf(initial.mode).coerceAtLeast(0)
        fun renderMode() { modeField.text = modes[modeIndex].labelAr }
        renderMode()
        modeField.setOnClickListener {
            modeIndex = (modeIndex + 1) % modes.size
            renderMode()
        }

        val slots = listOf(context.getString(R.string.draft_slot_unknown), "1", "2", "3")
        var slotIndex = initial.pickSlot?.coerceIn(1, 3) ?: 0
        fun renderSlot() { slotField.text = slots[slotIndex] }
        renderSlot()
        slotField.setOnClickListener {
            slotIndex = (slotIndex + 1) % slots.size
            renderSlot()
        }

        view.findViewById<View>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.saveButton).setOnClickListener {
            val unknown = mutableListOf<String>()
            fun names(raw: String): List<String> {
                val parts = raw.split(',', '،', '\n', '|').map { it.trim() }.filter { it.isNotEmpty() }
                return parts.mapNotNull { name ->
                    val found = BrawlerCatalog.find(name)
                    if (found == null) {
                        unknown += name
                        null
                    } else {
                        found.name
                    }
                }.distinct()
            }

            val allies = names(alliesField.text?.toString().orEmpty())
            val enemies = names(enemiesField.text?.toString().orEmpty())
            val bans = names(bansField.text?.toString().orEmpty())
            val map = mapField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val mode = modes.getOrElse(modeIndex) { GameMode.UNKNOWN }
            val slot = if (slotIndex in 1..3) slotIndex else null

            if (map == null && mode == GameMode.UNKNOWN && allies.isEmpty() && enemies.isEmpty()) {
                errorText.visibility = View.VISIBLE
                errorText.text = context.getString(R.string.draft_edit_need_something)
                return@setOnClickListener
            }

            if (unknown.isNotEmpty()) {
                errorText.visibility = View.VISIBLE
                errorText.text = context.getString(R.string.draft_edit_unknown_names, unknown.joinToString("، "))
            } else {
                errorText.visibility = View.GONE
            }

            onResult(
                initial.copy(
                    map = map,
                    mode = mode,
                    allies = allies,
                    enemies = enemies,
                    bans = bans,
                    pickSlot = slot,
                    source = RecognitionSource.MANUAL,
                    unresolved = unknown.distinct()
                )
            )
            dialog.dismiss()
        }

        runCatching { dialog.show() }.onFailure { dialog.dismiss() }
    }

    fun dismiss() {
        runCatching { dialog?.dismiss() }
        dialog = null
    }
}
