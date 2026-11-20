package com.laksh.coffee.ui.screens.gear

import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.GrinderEntity

/**
 * The inline editor's rules at expanded and up (v4 §3.10): the detail pane is the editor, so
 * moving the selection away from unsaved changes asks "Discard changes?" first ("Keep editing" is
 * the safe, focused answer). Pure; GearScreen keeps the state. The web twin is
 * features/gear/inlineEdit.ts.
 */
data class InlineSelection(
    /** A row key ("bean:12", "grinder:new") or null for the section's default. */
    val selected: String?,
    /** A move waiting on "Discard changes?"; [NONE] when nothing is asked. */
    val pending: String? = NONE,
) {
    val asking: Boolean get() = pending != NONE

    companion object {
        /** No question open. A sentinel, because "back to the default" (null) is a real answer. */
        const val NONE = "~no question~"
    }
}

object InlineEdit {

    /**
     * Asks to move to [to]. With unsaved changes on the current row the move waits for an answer;
     * otherwise it happens at once. Choosing the current row again changes nothing.
     */
    fun requestSelect(s: InlineSelection, to: String?, dirty: Boolean): InlineSelection = when {
        to == s.selected -> InlineSelection(s.selected)
        dirty -> InlineSelection(s.selected, pending = to)
        else -> InlineSelection(to)
    }

    /** "Discard" moves on (the caller drops the draft); "Keep editing" stays put. */
    fun answer(s: InlineSelection, discard: Boolean): InlineSelection = when {
        !s.asking -> s
        discard -> InlineSelection(s.pending)
        else -> InlineSelection(s.selected)
    }

    /**
     * True when [draft] holds an edit [saved] does not. Text is compared as it will be saved
     * (trimmed), and bookkeeping (updatedAt) never counts as a change you made.
     */
    fun dirty(saved: BeanEntity, draft: BeanEntity): Boolean =
        cleaned(saved).copy(updatedAt = 0) != cleaned(draft).copy(updatedAt = 0)

    fun dirty(saved: GrinderEntity, draft: GrinderEntity): Boolean =
        tidy(saved).copy(updatedAt = 0) != tidy(draft).copy(updatedAt = 0)

    /** A grinder as the editor saves it. */
    fun tidy(g: GrinderEntity): GrinderEntity = g.copy(brand = g.brand.trim(), model = g.model.trim())

    /** The bean editor can save once it has a name; the grinder editor once it has either part. */
    fun canSave(b: BeanEntity): Boolean = b.name.isNotBlank()
    fun canSave(g: GrinderEntity): Boolean = g.brand.isNotBlank() || g.model.isNotBlank()
}
