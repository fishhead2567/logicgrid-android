package dev.logicgrid.core

/**
 * Where everything sits on the composite chart, and what the player tapped.
 *
 * This is deliberately in `:core` and deliberately free of any Compose type.
 * The arithmetic of a dense pan-and-zoom grid is the fiddliest part of the app
 * and the easiest to get subtly wrong, so it lives somewhere that can be
 * exhaustively unit-tested with nothing but a JDK. The canvas layer above it
 * only draws and forwards taps.
 *
 * ### Three coordinate spaces, not one
 *
 * A naive "one scale, one offset" model is wrong here, because the headers do
 * not move with the cells:
 *
 *  * **cells** pan and zoom on both axes;
 *  * **row headers** pan vertically only -- never horizontally, or the labels
 *    would slide off;
 *  * **column headers** pan horizontally only.
 *
 * So panning is stored once but applied per-space. [rowHeaderRect] and
 * [colHeaderRect] exist precisely so the canvas cannot get this wrong by
 * reusing [cellRect].
 *
 * ### Display vs canonical
 *
 * A chart position is `(row, col)` over *display* order -- categories 2..N down
 * the side, 1..N-1 across the top. A [Cell] is an unordered ref pair sorted by
 * `(cat, item)` **string** comparison, which has nothing to do with schema
 * order or with which axis a ref is drawn on. [canonicalAt] and [displayOf]
 * convert, and their round trip is asserted over every shape, because a silent
 * transposition here would mark the wrong cell.
 */

/** A point in untransformed composable space, in pixels. */
data class Pt(val x: Float, val y: Float)

/** A rectangle in untransformed composable space, in pixels. */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centre: Pt get() = Pt((left + right) / 2f, (top + bottom) / 2f)
    fun contains(p: Pt): Boolean = p.x >= left && p.x < right && p.y >= top && p.y < bottom
}

data class Size(val width: Float, val height: Float)

/** A cell of the chart in display coordinates. */
data class GridCell(val row: Int, val col: Int)

/** What sits under a point. */
sealed interface GridHit {
    data class CellHit(val cell: GridCell) : GridHit
    /** A row label; [index] is the display row. */
    data class RowHeader(val index: Int) : GridHit
    data class ColHeader(val index: Int) : GridHit
    /** The unused half of the triangle, or the corner gutter. */
    data object Dead : GridHit
    data object Outside : GridHit
}

class GridGeometry(
    val schema: Schema,
    /** Edge length of one cell at zoom 1, in pixels. */
    val cellPx: Float,
    /** Width of the row-label gutter at zoom 1, in pixels. */
    val gutterPx: Float,
    /** Height of the column-label band at zoom 1, in pixels. */
    val headerPx: Float,
    val zoom: Float = 1f,
    val pan: Pt = Pt(0f, 0f),
) {
    init {
        require(cellPx > 0f) { "cellPx must be positive" }
        require(zoom > 0f) { "zoom must be positive" }
    }

    /** Categories down the side: 2..N. */
    val rowCategories: List<Category> = schema.categories.drop(1)

    /** Categories across the top: 1..N-1. */
    val colCategories: List<Category> = schema.categories.dropLast(1)

    val items: Int = schema.size
    val rows: Int = rowCategories.size * items
    val cols: Int = colCategories.size * items

    val scaledCell: Float get() = cellPx * zoom
    val scaledGutter: Float get() = gutterPx * zoom
    val scaledHeader: Float get() = headerPx * zoom

    /** Content size including the gutter and header band. */
    fun contentSize(): Size = Size(
        width = scaledGutter + cols * scaledCell,
        height = scaledHeader + rows * scaledCell,
    )

    /**
     * Which block a display row/col belongs to, and the item within it.
     *
     * Display row `r` is `rowCategories[r / items]`, item `r % items`.
     */
    fun rowRef(row: Int): Ref {
        val cat = rowCategories[row / items]
        return Ref(cat.name, cat.items[row % items])
    }

    fun colRef(col: Int): Ref {
        val cat = colCategories[col / items]
        return Ref(cat.name, cat.items[col % items])
    }

    /**
     * Is this cell part of the usable triangle?
     *
     * The chart only uses pairs where the column's category comes *before* the
     * row's in schema order. Everything else is either the mirror image or a
     * category against itself.
     */
    fun isUsable(row: Int, col: Int): Boolean {
        if (row !in 0 until rows || col !in 0 until cols) return false
        // rowCategories is schema[1..], colCategories is schema[..N-2], so a
        // display row block index r maps to schema index r+1.
        val rowCat = row / items + 1
        val colCat = col / items
        return colCat < rowCat
    }

    /** Rect of a cell, in untransformed composable space. */
    fun cellRect(row: Int, col: Int): Rect {
        val left = scaledGutter + col * scaledCell + pan.x
        val top = scaledHeader + row * scaledCell + pan.y
        return Rect(left, top, left + scaledCell, top + scaledCell)
    }

    /** Row labels track vertical pan only, so they stay pinned to the left. */
    fun rowHeaderRect(row: Int): Rect {
        val top = scaledHeader + row * scaledCell + pan.y
        return Rect(0f, top, scaledGutter, top + scaledCell)
    }

    /** Column labels track horizontal pan only. */
    fun colHeaderRect(col: Int): Rect {
        val left = scaledGutter + col * scaledCell + pan.x
        return Rect(left, 0f, left + scaledCell, scaledHeader)
    }

    /** What is under [p]. Pointer coordinates must be untransformed. */
    fun hitTest(p: Pt): GridHit {
        val inGutter = p.x < scaledGutter
        val inHeader = p.y < scaledHeader
        if (inGutter && inHeader) return GridHit.Dead
        if (inGutter) {
            val row = ((p.y - scaledHeader - pan.y) / scaledCell).toIntFloor()
            return if (row in 0 until rows) GridHit.RowHeader(row) else GridHit.Outside
        }
        if (inHeader) {
            val col = ((p.x - scaledGutter - pan.x) / scaledCell).toIntFloor()
            return if (col in 0 until cols) GridHit.ColHeader(col) else GridHit.Outside
        }
        val col = ((p.x - scaledGutter - pan.x) / scaledCell).toIntFloor()
        val row = ((p.y - scaledHeader - pan.y) / scaledCell).toIntFloor()
        if (row !in 0 until rows || col !in 0 until cols) return GridHit.Outside
        return if (isUsable(row, col)) GridHit.CellHit(GridCell(row, col)) else GridHit.Dead
    }

    /** The [Cell] a display position refers to, or null if unusable. */
    fun canonicalAt(row: Int, col: Int): Cell? =
        if (!isUsable(row, col)) null else cellOf(rowRef(row), colRef(col))

    /**
     * Where a [Cell] is drawn.
     *
     * A Cell's refs are sorted by `(cat, item)` string order, which is unrelated
     * to which axis each is drawn on, so this looks both up by category rather
     * than assuming `a` is the row.
     */
    fun displayOf(cell: Cell): GridCell? {
        val rowBlockOf = { ref: Ref ->
            rowCategories.indexOfFirst { it.name == ref.cat }
        }
        val colBlockOf = { ref: Ref ->
            colCategories.indexOfFirst { it.name == ref.cat }
        }
        // Try (a as row, b as col) then the other way round.
        for ((rowRef, colRef) in listOf(cell.a to cell.b, cell.b to cell.a)) {
            val rb = rowBlockOf(rowRef)
            val cb = colBlockOf(colRef)
            if (rb < 0 || cb < 0) continue
            val row = rb * items + rowCategories[rb].index(rowRef.item)
            val col = cb * items + colCategories[cb].index(colRef.item)
            if (isUsable(row, col)) return GridCell(row, col)
        }
        return null
    }

    /** Keep the content from being panned away from the viewport. */
    /**
     * The pan that brings [cell] fully into view, moving as little as possible.
     *
     * Needed because keyboard-style navigation moves the selection without
     * moving the chart: arrow off the visible edge of a 5x6 and the selection
     * is somewhere below the fold, with the status line describing a cell that
     * cannot be seen.
     *
     * The visible region for *cells* is not the whole viewport. The gutter and
     * the header are painted over the top of it, so a cell nudged under them
     * is occluded -- and [hitTest] already agrees it is not there. The target
     * region therefore starts at [scaledGutter] and [scaledHeader], not at
     * zero, or the first column would slide under the row labels and look
     * lost.
     *
     * A cell larger than the region it must fit into is pinned to the
     * near edge, so a heavily zoomed chart still shows the cell's top-left
     * rather than oscillating.
     */
    fun panToReveal(cell: GridCell, viewport: Size, margin: Float = 0f): Pt {
        if (viewport.width <= 0f || viewport.height <= 0f) return pan
        val rect = cellRect(cell.row, cell.col)

        fun adjust(near: Float, far: Float, lo: Float, hi: Float, current: Float): Float {
            val room = hi - lo
            // Nothing sensible to do if the cell cannot fit; show its start.
            if (far - near >= room) return current + (lo - near)
            return when {
                near - margin < lo -> current + (lo - (near - margin))
                far + margin > hi -> current - ((far + margin) - hi)
                else -> current
            }
        }

        val wanted = Pt(
            x = adjust(rect.left, rect.right, scaledGutter, viewport.width, pan.x),
            y = adjust(rect.top, rect.bottom, scaledHeader, viewport.height, pan.y),
        )
        return clampPan(wanted, viewport)
    }

    fun clampPan(candidate: Pt, viewport: Size): Pt {
        val content = contentSize()
        val spanX = content.width - scaledGutter
        val spanY = content.height - scaledHeader
        val visibleX = viewport.width - scaledGutter
        val visibleY = viewport.height - scaledHeader
        // When the content is smaller than the viewport, pin it rather than
        // letting it drift.
        val minX = if (spanX <= visibleX) 0f else visibleX - spanX
        val minY = if (spanY <= visibleY) 0f else visibleY - spanY
        return Pt(
            x = candidate.x.coerceIn(minOf(minX, 0f), 0f),
            y = candidate.y.coerceIn(minOf(minY, 0f), 0f),
        )
    }

    /**
     * Zoom at which the whole chart fits the viewport width.
     *
     * The gutter counts: it is content, not chrome, so fitting only the cells
     * leaves the chart about a gutter too wide.
     */
    fun zoomToFitWidth(viewport: Size): Float =
        viewport.width / (gutterPx + cols * cellPx)

    fun withZoom(newZoom: Float): GridGeometry =
        GridGeometry(schema, cellPx, gutterPx, headerPx, newZoom, pan)

    fun withPan(newPan: Pt): GridGeometry =
        GridGeometry(schema, cellPx, gutterPx, headerPx, zoom, newPan)
}

/**
 * Floor toward negative infinity.
 *
 * `toInt()` truncates toward zero, so a point left of or above the grid would
 * land on index 0 instead of -1 and register as a hit on the first row. Panned
 * content produces negative coordinates constantly, so this matters.
 */
private fun Float.toIntFloor(): Int = kotlin.math.floor(this).toInt()
