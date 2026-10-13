package io.github.katznmeik.cinexperience.feature.seatmap.editor

/**
 * Multi-step undo / redo of immutable states.
 *
 * [record] stores the state *before* an edit. Edits with the same non-null
 * coalesce key directly after each other (e.g. typing a row label) form one
 * step; any other record, undo or redo, or [breakCoalescing], ends the group.
 */
internal class UndoHistory<T>(private val limit: Int = DEFAULT_LIMIT) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    private var lastKey: String? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(before: T, coalesceKey: String? = null) {
        if (coalesceKey != null && coalesceKey == lastKey) return
        undoStack.addLast(before)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        lastKey = coalesceKey
    }

    /** The state to go back to from [current], or `null` if there is nothing to undo. */
    fun undo(current: T): T? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        lastKey = null
        return previous
    }

    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        lastKey = null
        return next
    }

    fun breakCoalescing() {
        lastKey = null
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastKey = null
    }

    private companion object {
        const val DEFAULT_LIMIT = 100
    }
}
