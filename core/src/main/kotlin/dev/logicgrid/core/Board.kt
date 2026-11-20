package dev.logicgrid.core

/**
 * The player's solving chart.
 *
 * This is the Penny Dell grid as data: for every pair of distinct categories, an
 * M x M matrix of marks, each `true` (these go together), `false` (they do not)
 * or absent (not yet known).
 *
 * **Only the player's own marks are stored.** Everything else -- the x's a
 * circled cell forces across its row and column, and anything assist deduces --
 * is recomputed by [closure] on every change. Keeping the two apart is what
 * makes editing sound: change your mind about a cell and the consequences of
 * the old answer go with it, rather than lingering as x's nothing supports any
 * more. It also makes undo a list of states and makes toggling assist a pure
 * recompute.
 *
 * ### Why this is immutable where the Python is not
 *
 * The reference implementation is a mutable `Board`. Three reasons not to copy
 * that shape here:
 *
 *  * a mutable holder is the wrong thing for Compose to observe -- reading a
 *    plain field from a composable triggers no recomposition, and the usual
 *    workaround is a version counter, which is a known anti-pattern;
 *  * the Python `assist` setter assigns the flag *before* recomputing, so a
 *    conflict leaves the object with the flag set and a closure computed under
 *    the old value. Returning a new state makes that unrepresentable;
 *  * "a refused mark changes nothing" is a comment in Python and a guarantee
 *    here -- a failed [setMark] hands back a [MarkResult.Rejected] and the
 *    caller still holds the untouched state.
 */

/** A cell: an unordered pair of refs, canonically ordered. */
data class Cell(val a: Ref, val b: Ref) {
    init { require(a <= b) { "Cell must be canonically ordered; use cellOf()" } }
}

fun cellOf(a: Ref, b: Ref): Cell = if (b < a) Cell(b, a) else Cell(a, b)

/** Why a mark was refused. Structured, so `:app` can translate it. */
sealed interface ConflictReason {
    /** [a] and [b] are in the same category, so they are never one entity. */
    data class SameCategory(val a: Ref, val b: Ref, val categoryLabel: String) :
        ConflictReason

    /** A forced consequence clashed with a mark the player already made. */
    data class AlreadyHeld(val a: Ref, val b: Ref, val held: Boolean) : ConflictReason

    /**
     * Every cell of [ref]'s row against [categoryName] was ruled out, leaving
     * the item nowhere to go.
     */
    data class NowhereToGo(val ref: Ref, val categoryName: String) : ConflictReason
}

/** The player's marks plus whether assist is on. The whole of board state. */
data class BoardState(
    val userMarks: Map<Cell, Boolean> = emptyMap(),
    val assist: Boolean = false,
)

/** [BoardState] plus everything it forces. */
data class Closure(val marks: Map<Cell, Boolean>) {
    operator fun get(cell: Cell): Boolean? = marks[cell]
    fun get(a: Ref, b: Ref): Boolean? = marks[cellOf(a, b)]
    val size: Int get() = marks.size
}

sealed interface ClosureResult {
    data class Ok(val closure: Closure) : ClosureResult
    data class Rejected(val reason: ConflictReason) : ClosureResult
}

sealed interface MarkResult {
    data class Ok(val state: BoardState, val closure: Closure) : MarkResult
    data class Rejected(val reason: ConflictReason) : MarkResult
}

object Board {

    /**
     * Everything that follows from [state].
     *
     * Two levels of inference:
     *
     *  * **always on** -- a circled cell rules out the rest of its row and
     *    column. That is the pencil-work the rules force, not a deduction.
     *  * **assist, opt-in** -- transitivity (A=B and B=C so A=C) and
     *    last-cell-standing. Those *are* deductions, so doing them unasked
     *    changes the puzzle.
     *
     * The result is order-independent: the fixpoint is monotone and a cell is
     * never overwritten, only agreed with or rejected. `BoardConformanceTest`
     * asserts that by shuffling insertion order.
     */
    fun closure(puzzle: Puzzle, state: BoardState): ClosureResult {
        val schema = puzzle.schema
        val marks = LinkedHashMap<Cell, Boolean>()
        val queue = ArrayDeque<Cell>()
        var rejection: ConflictReason? = null

        fun put(cell: Cell, value: Boolean): Boolean {
            val previous = marks[cell]
            if (previous == null) {
                marks[cell] = value
                queue.addLast(cell)
                return true
            }
            if (previous != value) {
                rejection = ConflictReason.AlreadyHeld(cell.a, cell.b, previous)
                return false
            }
            return true
        }

        for ((cell, value) in state.userMarks) {
            if (!put(cell, value)) return ClosureResult.Rejected(rejection!!)
        }

        /** A circled cell empties the rest of its row and column. */
        fun propagateForced(): Boolean {
            while (queue.isNotEmpty()) {
                val cell = queue.removeLast()
                if (marks[cell] != true) continue
                val (a, b) = cell
                for (other in schema.category(b.cat).refs()) {
                    if (other != b && !put(cellOf(a, other), false)) return false
                }
                for (other in schema.category(a.cat).refs()) {
                    if (other != a && !put(cellOf(other, b), false)) return false
                }
            }
            return true
        }

        /**
         * Every item must still have somewhere to go. Ruling out all of an
         * item's cells against some category leaves a state nothing can
         * satisfy; without this check the board accepts it silently.
         */
        fun checkNowhereToGo(): Boolean {
            for ((catA, catB) in schema.pairs) {
                for ((first, second) in listOf(catA to catB, catB to catA)) {
                    for (ref in schema.category(first).refs()) {
                        val options = schema.category(second).refs()
                        if (options.any { marks[cellOf(ref, it)] != false }) continue
                        rejection = ConflictReason.NowhereToGo(ref, second)
                        return false
                    }
                }
            }
            return true
        }

        if (!propagateForced()) return ClosureResult.Rejected(rejection!!)
        if (!checkNowhereToGo()) return ClosureResult.Rejected(rejection!!)
        if (!state.assist) return ClosureResult.Ok(Closure(marks))

        var changed = true
        while (changed) {
            changed = false

            // Transitivity: a fact about one item is a fact about its partner.
            for ((cell, value) in marks.toList()) {
                if (value != true) continue
                val (a, b) = cell
                for (cat in schema.categories) {
                    if (cat.name == a.cat || cat.name == b.cat) continue
                    for (c in cat.refs()) {
                        val va = marks[cellOf(a, c)]
                        val vb = marks[cellOf(b, c)]
                        if (va != null && vb == null) {
                            if (!put(cellOf(b, c), va)) {
                                return ClosureResult.Rejected(rejection!!)
                            }
                            changed = true
                        } else if (vb != null && va == null) {
                            if (!put(cellOf(a, c), vb)) {
                                return ClosureResult.Rejected(rejection!!)
                            }
                            changed = true
                        }
                    }
                }
            }
            if (!propagateForced()) return ClosureResult.Rejected(rejection!!)

            // Last cell standing in a row.
            for ((catA, catB) in schema.pairs) {
                for ((first, second) in listOf(catA to catB, catB to catA)) {
                    for (ref in schema.category(first).refs()) {
                        val options = schema.category(second).refs()
                        if (options.any { marks[cellOf(ref, it)] == true }) continue
                        val open = options.filter { marks[cellOf(ref, it)] != false }
                        if (open.size == 1) {
                            if (!put(cellOf(ref, open[0]), true)) {
                                return ClosureResult.Rejected(rejection!!)
                            }
                            changed = true
                        }
                    }
                }
            }
            if (!propagateForced()) return ClosureResult.Rejected(rejection!!)
            if (!checkNowhereToGo()) return ClosureResult.Rejected(rejection!!)
        }

        return ClosureResult.Ok(Closure(marks))
    }

    /**
     * Mark a cell. On rejection nothing changes -- the caller still holds the
     * previous state, by construction rather than by discipline.
     *
     * The targeted cell is always overwritable: changing your own answer is an
     * edit, not a contradiction. Only a *forced consequence* clashing with
     * another of the player's marks is a conflict.
     */
    fun setMark(
        puzzle: Puzzle,
        state: BoardState,
        a: Ref,
        b: Ref,
        value: Boolean,
    ): MarkResult {
        if (a.cat == b.cat) {
            return MarkResult.Rejected(
                ConflictReason.SameCategory(a, b, puzzle.schema.category(a.cat).label)
            )
        }
        val next = state.copy(userMarks = state.userMarks + (cellOf(a, b) to value))
        return when (val result = closure(puzzle, next)) {
            is ClosureResult.Ok -> MarkResult.Ok(next, result.closure)
            is ClosureResult.Rejected -> MarkResult.Rejected(result.reason)
        }
    }

    /** Erase one of the player's own marks, and whatever it forced. */
    fun clearMark(puzzle: Puzzle, state: BoardState, a: Ref, b: Ref): MarkResult? {
        val cell = cellOf(a, b)
        if (cell !in state.userMarks) return null
        val next = state.copy(userMarks = state.userMarks - cell)
        return when (val result = closure(puzzle, next)) {
            is ClosureResult.Ok -> MarkResult.Ok(next, result.closure)
            is ClosureResult.Rejected -> MarkResult.Rejected(result.reason)
        }
    }

    /** Turn assist on or off. Retroactive in both directions, being a recompute. */
    fun withAssist(puzzle: Puzzle, state: BoardState, on: Boolean): MarkResult {
        val next = state.copy(assist = on)
        return when (val result = closure(puzzle, next)) {
            is ClosureResult.Ok -> MarkResult.Ok(next, result.closure)
            is ClosureResult.Rejected -> MarkResult.Rejected(result.reason)
        }
    }

    // --- derived views ----------------------------------------------------

    /** `(confirmed, ruledOut)` for [ref] against one category. */
    fun linksFor(
        puzzle: Puzzle,
        closure: Closure,
        ref: Ref,
        categoryName: String,
    ): Pair<List<Ref>, List<Ref>> {
        val yes = mutableListOf<Ref>()
        val no = mutableListOf<Ref>()
        for (other in puzzle.schema.category(categoryName).refs()) {
            when (closure.get(ref, other)) {
                true -> yes += other
                false -> no += other
                null -> {}
            }
        }
        return yes to no
    }

    /**
     * The answer sheet: spine-indexed rows, `null` where unplaced.
     *
     * Recomputed from the closure rather than cached, but callers should hold
     * the result -- [answerComplete], [solved] and [progress] all derive from
     * it, and in Compose each call would otherwise be a fresh state read inside
     * recomposition.
     */
    fun answer(puzzle: Puzzle, closure: Closure): Map<String, List<String?>> {
        val schema = puzzle.schema
        val rows = LinkedHashMap<String, List<String?>>()
        rows[schema.spineName] = schema.spine.items

        // A circle means "these are the same entity", and that chains.  Circle
        // builder-score and builder-biome and the biome's score is pinned, with
        // no mark in the biome-score block at all -- which is how a logic grid
        // is normally solved: you fill the blocks the clues talk about and read
        // the rest off.
        //
        // Reading each category only against the spine made the answer sheet
        // depend on *which* blocks the player filled rather than on what they
        // had actually determined.  A complete and correct solve still showed
        // "?" for every row, unless assist happened to be on and supplied the
        // transitive marks -- so the board asked for busywork and withheld the
        // solved state until it got it.  Whether the answer is determined is a
        // question about consequence, not about where the ink is, so it must
        // not depend on the assist setting.
        val linked = HashMap<Ref, MutableSet<Ref>>()
        for ((cell, value) in closure.marks) {
            if (value != true) continue
            linked.getOrPut(cell.a) { mutableSetOf() }.add(cell.b)
            linked.getOrPut(cell.b) { mutableSetOf() }.add(cell.a)
        }

        /**
         * The one spine item reachable from [start] through circles, or null.
         *
         * Stops at the first spine ref on each path rather than walking past
         * it: beyond a spine value lie *other* entities, and chaining through
         * would conflate them.  Two reachable spine items means the marks
         * contradict each other -- possible without assist, since nothing
         * cross-checks the blocks then -- and that is not an answer, so it
         * stays unplaced and [mistakes] reports it.
         */
        fun spineOf(start: Ref): Ref? {
            val seen = mutableSetOf(start)
            val queue = ArrayDeque(listOf(start))
            val found = mutableSetOf<Ref>()
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (cur.cat == schema.spineName) {
                    found += cur
                    continue
                }
                for (next in linked[cur].orEmpty()) {
                    if (seen.add(next)) queue += next
                }
            }
            return found.singleOrNull()
        }

        for (cat in schema.otherCategories()) {
            val row = arrayOfNulls<String>(schema.size)
            for (ref in cat.refs()) {
                val spine = spineOf(ref) ?: continue
                row[schema.spine.index(spine.item)] = ref.item
            }
            rows[cat.name] = row.toList()
        }
        return rows
    }

    fun answerComplete(answer: Map<String, List<String?>>): Boolean =
        answer.values.none { row -> row.any { it == null } }

    /** Marks that contradict the real solution, in canonical cell order. */
    fun mistakes(puzzle: Puzzle, closure: Closure): List<Pair<Cell, Boolean>> =
        closure.marks.entries
            .filter { (cell, value) -> value != puzzle.solution.sameEntity(cell.a, cell.b) }
            .sortedWith(compareBy({ it.key.a }, { it.key.b }))
            .map { it.key to it.value }

    fun solved(puzzle: Puzzle, closure: Closure): Boolean =
        answerComplete(answer(puzzle, closure)) && mistakes(puzzle, closure).isEmpty()

    /** `(placed, total)` cells of the answer sheet, not of the whole chart. */
    fun progress(puzzle: Puzzle, answer: Map<String, List<String?>>): Pair<Int, Int> {
        val cats = puzzle.schema.otherCategories().map { it.name }
        val placed = cats.sumOf { name -> answer.getValue(name).count { it != null } }
        return placed to cats.size * puzzle.schema.size
    }

    fun totalCells(puzzle: Puzzle): Int =
        puzzle.schema.pairs.size * puzzle.schema.size * puzzle.schema.size
}
