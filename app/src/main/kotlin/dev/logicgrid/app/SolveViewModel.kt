package dev.logicgrid.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.logicgrid.core.Board
import dev.logicgrid.core.BoardState
import dev.logicgrid.core.Cell
import dev.logicgrid.core.Closure
import dev.logicgrid.core.ClosureResult
import dev.logicgrid.core.ConflictReason
import dev.logicgrid.core.GridCell
import dev.logicgrid.core.GridGeometry
import dev.logicgrid.core.MarkResult
import dev.logicgrid.core.Pt
import dev.logicgrid.core.Puzzle
import dev.logicgrid.core.PuzzleProgress
import dev.logicgrid.core.Size as CoreSize

/**
 * Everything the Solve screen needs, kept out of the Compose layer so it can be
 * tested on the JVM with no device.
 *
 * `:core`'s [Board] is immutable, so this holds a *stack* of states: undo is
 * popping one, and there is no mutation to miss.
 */
class SolveViewModel(
    val puzzle: Puzzle,
    private val store: ProgressStore = InMemoryProgressStore(),
    private val prefs: ViewPrefs = store as? ViewPrefs ?: InMemoryProgressStore(),
) : ViewModel() {

    private val history = ArrayDeque<BoardState>()

    var state: BoardState by mutableStateOf(BoardState())
        private set

    var closure: Closure by mutableStateOf(Closure(emptyMap()))
        private set

    var selected: GridCell? by mutableStateOf(null)
        private set

    /** One-shot message for a snackbar; cleared once shown. */
    var conflict: ConflictReason? by mutableStateOf(null)
        private set

    /**
     * The result of the last explicit check, or null if the board has changed
     * since.
     *
     * Deliberately only two values. "You have gone wrong somewhere" is the
     * help a printed logic problem gives you: you glance at the answers, see
     * that yours is not it, and start again from the clue you trust most. A
     * count would narrow the search -- only one wrong means it was recent --
     * and naming the cell would solve that much of the puzzle outright.
     */
    var checked: Checked? by mutableStateOf(null)
        private set

    enum class Checked { Clean, Wrong }

    /**
     * Does the board contradict the answer anywhere?
     *
     * Over the closure rather than the player's own marks, and that is the
     * point: forced elimination follows from a circle, so a circle in the
     * wrong place is caught by the X's it produced, before the circle itself
     * clashes with anything.
     */
    fun check() {
        checked = if (Board.mistakes(puzzle, closure).isEmpty()) Checked.Clean else Checked.Wrong
    }

    var outcome: Outcome by mutableStateOf(Outcome.Playing)
        private set

    /** Answer sheet, recomputed once per closure rather than per read. */
    var answer: Map<String, List<String?>> by mutableStateOf(emptyMap())
        private set

    /**
     * Upright prefixes, or whole names turned a quarter turn as a printed
     * chart has them. Rotating buys vertical room for the label at the cost of
     * a much taller header band, so the two modes need different margins --
     * which is why this rebuilds the geometry rather than setting a flag on it.
     */
    var rotatedHeaders: Boolean by mutableStateOf(prefs.rotatedHeaders)
        private set

    var geometry: GridGeometry by mutableStateOf(geometryFor(prefs.rotatedHeaders))
        private set

    private fun geometryFor(rotated: Boolean, zoom: Float = 1f, pan: Pt = Pt(0f, 0f)) =
        GridGeometry(
            puzzle.schema,
            cellPx = 40f,
            gutterPx = if (rotated) 190f else 96f,
            headerPx = if (rotated) 165f else 72f,
            zoom = zoom,
            pan = pan,
        )

    fun toggleHeaderStyle() {
        rotatedHeaders = !rotatedHeaders
        // A way of reading the chart, not a property of this puzzle: carry it
        // to every puzzle the player opens from now on.
        prefs.rotatedHeaders = rotatedHeaders
        // Pan is in pixels from a corner that just moved, so carrying it over
        // would shift the chart by the difference in margins. Start at rest.
        geometry = geometryFor(rotatedHeaders, zoom = geometry.zoom)
        if (viewport.width > 0f) {
            geometry = geometry.withPan(geometry.clampPan(geometry.pan, viewport))
            revealAfterLayout()
        }
    }

    enum class Outcome { Playing, Solved, Wrong }

    /**
     * Sticky: solving is a fact about history. A player who finishes and then
     * clears a cell to look at something has still solved the puzzle, and the
     * menu should go on saying so.
     */
    var everSolved: Boolean = false
        private set

    init {
        val saved = store.read(puzzle.pid)
        everSolved = saved.solved
        state = saved.toBoardState()
        // The default applies to a puzzle that has never been opened, and only
        // to that. A board that was saved with assist off was saved that way
        // on purpose, and changing the default later must not reach back and
        // switch it on under them.
        if (!saved.started && saved.userMarks.isEmpty()) {
            state = state.copy(assist = prefs.assistByDefault)
        }
        closure = when (val r = Board.closure(puzzle, state)) {
            is ClosureResult.Ok -> r.closure
            // A save that no longer fits its puzzle -- content changed under a
            // stored board -- must not wedge the app on open. Drop the marks.
            else -> { state = BoardState(); Closure(emptyMap()) }
        }
        recompute()
    }

    private fun persist() {
        store.write(puzzle.pid, PuzzleProgress.of(state, everSolved))
    }

    val assist: Boolean get() = state.assist

    val progress: Pair<Int, Int> get() = Board.progress(puzzle, answer)

    fun markAt(cell: GridCell): Boolean? =
        geometry.canonicalAt(cell.row, cell.col)?.let { closure[it] }

    /**
     * Tap selects; tapping the already-selected cell cycles it. No size
     * threshold: making the behaviour depend on zoom means the player cannot
     * tell which they will get, and the fast path would be off at exactly the
     * zoom levels where solving is slowest.
     *
     * [reveal] scrolls the chart to the cell. A tap never needs it -- the
     * player just touched the cell, so it is on screen by definition, and
     * scrolling under their finger would be jarring. Arrow navigation always
     * needs it, since the selection can move past the edge.
     */
    fun select(cell: GridCell, reveal: Boolean = false) {
        if (selected == cell) {
            cycle(cell)
        } else {
            selected = cell
        }
        if (reveal) revealSelection()
    }

    /** Bring the selected cell into view, moving the chart as little as
     *  possible. Half a cell of margin so the selection does not sit flush
     *  against the gutter, where it reads as clipped. */
    fun revealSelection() {
        val cell = selected ?: return
        if (viewport.width <= 0f || viewport.height <= 0f) return
        geometry = geometry.withPan(
            geometry.panToReveal(cell, viewport, margin = geometry.scaledCell / 2f)
        )
    }

    fun cycle(cell: GridCell) {
        val canonical = geometry.canonicalAt(cell.row, cell.col) ?: return
        when (closure[canonical]) {
            null -> set(canonical, true)
            true -> set(canonical, false)
            false -> clear(canonical)
        }
    }

    fun markSelected(value: Boolean) {
        val cell = selected ?: return
        geometry.canonicalAt(cell.row, cell.col)?.let { set(it, value) }
    }

    fun clearSelected() {
        val cell = selected ?: return
        geometry.canonicalAt(cell.row, cell.col)?.let { clear(it) }
    }

    /** Move the selection by one cell, skipping the dead triangle. */
    fun nudge(dRow: Int, dCol: Int) {
        val from = selected ?: GridCell(0, 0)
        var row = from.row
        var col = from.col
        repeat(geometry.rows + geometry.cols) {
            row += dRow
            col += dCol
            if (row !in 0 until geometry.rows || col !in 0 until geometry.cols) return
            if (geometry.isUsable(row, col)) {
                select(GridCell(row, col), reveal = true)
                return
            }
        }
    }

    private fun set(cell: Cell, value: Boolean) =
        apply(Board.setMark(puzzle, state, cell.a, cell.b, value))

    private fun clear(cell: Cell) =
        Board.clearMark(puzzle, state, cell.a, cell.b)?.let { apply(it) }

    private fun apply(result: MarkResult) {
        when (result) {
            is MarkResult.Ok -> {
                history.addLast(state)
                if (history.size > 60) history.removeFirst()
                state = result.state
                closure = result.closure
                recompute()
            }
            is MarkResult.Rejected -> conflict = result.reason
        }
    }

    fun undo(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        state = previous
        closure = (Board.closure(puzzle, previous) as ClosureResult.Ok).closure
        recompute()
        return true
    }

    fun toggleAssist() {
        when (val r = Board.withAssist(puzzle, state, !state.assist)) {
            is MarkResult.Ok -> { history.addLast(state); state = r.state; closure = r.closure; recompute() }
            is MarkResult.Rejected -> conflict = r.reason
        }
    }

    fun reset() {
        history.addLast(state)
        state = BoardState(assist = state.assist)
        closure = Closure(emptyMap())
        recompute()
    }

    fun mistakes(): List<Pair<Cell, Boolean>> = Board.mistakes(puzzle, closure)

    fun consumeConflict() { conflict = null }

    /** The chart's measured size. Zero until the first layout pass. */
    var viewport: CoreSize by mutableStateOf(CoreSize(0f, 0f))
        private set

    /**
     * Whether the chart has been sized to the screen yet.
     *
     * Once only, and per puzzle. Re-fitting on every viewport change would
     * throw away a pinch the moment anything resized the window.
     */
    private var fitted = false

    fun onViewportChanged(size: CoreSize) {
        viewport = size
        // The first real measurement is the first chance to fit the chart to
        // the screen, and a chart that opens at 1x sits in the top-left corner
        // of a phone-sized space looking like a rendering bug. The roadmap has
        // said "default zoom fits width" since before the chart was drawn; the
        // call existed, behind a menu, and nothing ever made it on open.
        if (!fitted && size.width > 0f && size.height > 0f) {
            fitted = true
            fitWidth()
            return
        }
        // Re-clamp: a rotation or a window resize can shrink the viewport and
        // strand already-panned content off-screen with no way back.
        geometry = geometry.withPan(geometry.clampPan(geometry.pan, size))
        revealAfterLayout()
    }

    fun onTransform(zoom: Float, pan: Pt) {
        // Before the first measurement there is nothing to clamp against, and
        // clamping against zero would pin the chart permanently.
        if (viewport.width <= 0f || viewport.height <= 0f) return
        val zoomed = geometry.withZoom(zoom)
        geometry = zoomed.withPan(zoomed.clampPan(pan, viewport))
    }

    /** Keep the selection in view when the chart itself is re-laid-out. */
    private fun revealAfterLayout() {
        if (selected != null) revealSelection()
    }

    fun fitWidth() {
        if (viewport.width <= 0f) return
        // Bounded by the same limits the pinch uses, so this cannot reach a
        // zoom the player could not have reached themselves.
        val target = geometry.zoomToFitWidth(viewport).coerceIn(0.2f, 4f)
        val zoomed = geometry.withZoom(target)
        geometry = zoomed.withPan(zoomed.clampPan(Pt(0f, 0f), viewport))
        revealAfterLayout()
    }

    private fun recompute() {
        // Any edit at all invalidates the last check, including an undo.
        // Leaving a stale "no mistakes so far" on screen after a new mark
        // would be the one thing worse than not having a check button.
        checked = null
        answer = Board.answer(puzzle, closure)
        outcome = when {
            !Board.answerComplete(answer) -> Outcome.Playing
            Board.mistakes(puzzle, closure).isEmpty() -> Outcome.Solved
            else -> Outcome.Wrong
        }
        if (outcome == Outcome.Solved) everSolved = true
        persist()
    }
}
