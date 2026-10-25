package com.myai.assistant.privacy

/**
 * Device-only store for text read from the screen.
 *
 * Before this existed, the Accessibility readout was written into
 * `PlanStep.resultMessage`, i.e. *inside* the very object that is persisted and
 * that the remote planner reads. Filtering kept it out of the cloud, but the
 * data and the outbound message were the same structure — one forgotten filter
 * away from a leak.
 *
 * Now the two are separated by construction:
 *
 * * `PlanStep.resultMessage` holds only [PLACEHOLDER] for a screen step.
 * * The real text lives here, keyed by step id, in memory only. It is never
 *   serialized into `conversation.json`, never part of a `ChatMessage`, and
 *   therefore cannot reach a provider even if every filter above it regresses.
 * * The UI asks this vault for the display text when it binds a local-only step.
 *
 * Memory only, on purpose: after the process dies the text is gone and the step
 * shows the placeholder. Nothing sensitive is written to disk.
 */
class ScreenContentVault(
    private val guard: ScreenContentGuard = ScreenContentGuard()
) {

    private val entries = LinkedHashMap<String, String>()

    /** Stores [text] for [stepId] and registers it with the outbound guard. */
    fun put(stepId: String, text: String) {
        if (stepId.isBlank() || text.isBlank()) return
        synchronized(this) {
            entries.remove(stepId)
            entries[stepId] = text
            while (entries.size > MAX_ENTRIES) {
                val eldest = entries.keys.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
        }
        guard.registerScreenRead(text)
    }

    /** Display text for a local-only step, or null after a restart / eviction. */
    fun get(stepId: String): String? = synchronized(this) { entries[stepId] }

    fun clear() {
        synchronized(this) { entries.clear() }
    }

    fun size(): Int = synchronized(this) { entries.size }

    companion object {
        const val PLACEHOLDER =
            "«قُرئ من الشاشة — محفوظ على هذا الجهاز فقط ولا يُرسل إلى أي مزوّد»"

        /** Small bound: only what the chat currently shows needs to survive. */
        const val MAX_ENTRIES = 40
    }
}
