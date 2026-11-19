package dev.logicgrid.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logicgrid.core.Cell
import dev.logicgrid.core.Closure
import dev.logicgrid.core.GridCell
import dev.logicgrid.core.GridGeometry
import dev.logicgrid.core.GridHit
import dev.logicgrid.core.Pt
import dev.logicgrid.core.Ref
import dev.logicgrid.core.Puzzle
import dev.logicgrid.core.Size as CoreSize

/**
 * The composite chart: every category pair at once, the way a printed puzzle
 * lays them out.
 *
 * Drawn as a single [androidx.compose.foundation.Canvas] rather than composed
 * cells. A 5x6 puzzle is 24x24 = 576 slots, and that many composables would be
 * slow; worse, Compose's layout system will not give sticky headers on *both*
 * axes without a fight, and drawing them into the same canvas as the cells is
 * the only way they cannot drift out of sync.
 *
 * ### Why there is no graphicsLayer here
 *
 * Applying `Modifier.graphicsLayer { scaleX = zoom; translationX = pan.x }`
 * would transform pointer coordinates too, so inverting the transform by hand
 * to hit-test would apply it twice. Instead the transform is applied only
 * inside [DrawScope.withTransform]; pointer coordinates stay in untransformed
 * composable space, and hit-testing is then exactly
 * [GridGeometry.hitTest] -- deterministic, and unit-tested in `:core` without
 * a device.
 *
 * ### Why the geometry is read through rememberUpdatedState
 *
 * `pointerInput(Unit) { detectTapGestures { ... } }` captures whatever it can
 * see when the block *starts*. Capture `geometry` directly and the lambda keeps
 * using the zoom and pan from first composition, so after one pinch every tap
 * hit-tests against a stale transform. Keying `pointerInput` on the transform
 * instead would restart the gesture detector mid-gesture and cancel drags. So
 * the geometry is read from a [State] *inside* the lambda.
 */
@Composable
fun CompositeGrid(
    puzzle: Puzzle,
    closure: Closure,
    selected: GridCell?,
    geometry: GridGeometry,
    rotatedHeaders: Boolean,
    onTransform: (zoom: Float, pan: Pt) -> Unit,
    onCellTap: (GridCell) -> Unit,
    onHeaderTap: (GridHit) -> Unit,
    onViewportChanged: (CoreSize) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val current: State<GridGeometry> = rememberUpdatedState(geometry)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = remember(density) { TextStyle(fontSize = 9.sp, color = Color(0xFF444444)) }
    val markStyle = remember(density) { TextStyle(fontSize = 12.sp) }
    val cache = remember { HashMap<String, TextLayoutResult>() }
    // Per category, the shortest prefixes that still tell its items apart.
    // shortLabel() alone just truncates, which collides badly at 40px cells:
    // "Ifeoma"/"Blaise"/"Tansy"/"Kipling" all overlap, whereas the
    // distinguishing prefixes are three characters each.
    val labels: Map<String, List<String>> = remember(puzzle.pid, rotatedHeaders) {
        puzzle.schema.categories.associate { cat ->
            // Rotated headers run along the vertical axis, where there is room
            // for the whole name; upright ones have a 40px cell and must fall
            // back to the shortest prefix that stays unambiguous.
            cat.name to if (rotatedHeaders) cat.items.map { fullLabel(it) }
                        else shortLabels(cat.items)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Compose does NOT clip a Canvas to its bounds. Without this the
            // selection highlight -- a full row and column, sized from the
            // chart rather than the viewport -- paints straight over the
            // status line, the buttons and the clue text below.
            .clipToBounds()
            // The real measured size. Panning is clamped against the viewport,
            // so a guessed one silently disables it: guess too large and the
            // clamp decides the chart already fits and pins pan to zero.
            .onSizeChanged { onViewportChanged(CoreSize(it.width.toFloat(), it.height.toFloat())) }
            // Two detectors, not Modifier.transformable: that claims
            // single-pointer drags and can starve the tap detector, and the end
            // of a pan commonly emits a spurious tap -- which would select a
            // random cell every time the player scrolls.
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    val g = current.value
                    val nextZoom = (g.zoom * zoomChange).coerceIn(0.2f, 4f)
                    val candidate = Pt(g.pan.x + panChange.x, g.pan.y + panChange.y)
                    onTransform(nextZoom, candidate)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    when (val hit = current.value.hitTest(Pt(offset.x, offset.y))) {
                        is GridHit.CellHit -> onCellTap(hit.cell)
                        is GridHit.RowHeader, is GridHit.ColHeader -> onHeaderTap(hit)
                        GridHit.Dead, GridHit.Outside -> {}
                    }
                }
            }
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val g = current.value
            drawChart(g, puzzle, closure, selected, measurer, labelStyle, markStyle, cache, labels, rotatedHeaders)
        }
    }
}

private fun DrawScope.drawChart(
    g: GridGeometry,
    puzzle: Puzzle,
    closure: Closure,
    selected: GridCell?,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    markStyle: TextStyle,
    cache: HashMap<String, TextLayoutResult>,
    labels: Map<String, List<String>>,
    rotatedHeaders: Boolean,
) {
    fun labelFor(ref: Ref): String {
        val cat = g.schema.category(ref.cat)
        return labels[ref.cat]?.getOrNull(cat.index(ref.item)) ?: ref.item
    }
    val lineColour = Color(0xFFBBBBBB)
    val blockColour = Color(0xFF666666)
    val deadColour = Color(0xFFEDEDED)
    val selectColour = Color(0x332196F3)

    fun layout(text: String, style: TextStyle): TextLayoutResult =
        cache.getOrPut(text + style.fontSize) { measurer.measure(text, style) }

    // Selection highlight spans the whole row and column, which is how a logic
    // grid is actually read.
    if (selected != null) {
        val r = g.cellRect(selected.row, 0)
        drawRect(
            selectColour,
            topLeft = Offset(g.gutterWidth, r.top),
            size = androidx.compose.ui.geometry.Size(g.cols * g.scaledCell, g.scaledCell),
        )
        val c = g.cellRect(0, selected.col)
        drawRect(
            selectColour,
            topLeft = Offset(c.left, g.headerHeight),
            size = androidx.compose.ui.geometry.Size(g.scaledCell, g.rows * g.scaledCell),
        )
    }

    for (row in 0 until g.rows) {
        for (col in 0 until g.cols) {
            val rect = g.cellRect(row, col)
            if (rect.right < g.gutterWidth || rect.bottom < g.headerHeight) continue
            if (rect.left > size.width || rect.top > size.height) continue

            if (!g.isUsable(row, col)) {
                // Inert, and visibly so -- not an empty cell inviting a tap.
                drawRect(
                    deadColour,
                    topLeft = Offset(rect.left, rect.top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                )
                continue
            }
            drawRect(
                lineColour,
                topLeft = Offset(rect.left, rect.top),
                size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
            )
            val cell: Cell = g.canonicalAt(row, col) ?: continue
            val mark = closure[cell] ?: continue
            val glyph = if (mark) "O" else "x"
            val l = layout(glyph, markStyle)
            // Glyphs, never colour alone: colour-blind players and greyscale
            // screenshots both need the shape to carry the meaning.
            drawText(
                l,
                color = if (mark) Color(0xFF1B7F3B) else Color(0xFFB3261E),
                topLeft = Offset(
                    rect.centre.x - l.size.width / 2f,
                    rect.centre.y - l.size.height / 2f,
                ),
            )
        }
    }

    // Block separators, so category boundaries read at a glance.
    val content = g.contentSize()
    val lastX = minOf(content.width + g.pan.x, size.width)
    val lastY = minOf(content.height + g.pan.y, size.height)
    for (b in 0..g.rowCategories.size) {
        val y = g.headerHeight + b * g.items * g.scaledCell + g.pan.y
        if (y < g.headerHeight - 1f || y > size.height) continue
        drawLine(blockColour, Offset(0f, y), Offset(lastX, y), strokeWidth = 2f)
    }
    for (b in 0..g.colCategories.size) {
        val x = g.gutterWidth + b * g.items * g.scaledCell + g.pan.x
        if (x < g.gutterWidth - 1f || x > size.width) continue
        drawLine(blockColour, Offset(x, 0f), Offset(x, lastY), strokeWidth = 2f)
    }

    // Headers last, so they paint over any cell panned beneath them -- which is
    // what GridGeometry.hitTest already assumes.
    drawRect(
        Color.White,
        topLeft = Offset(0f, 0f),
        size = androidx.compose.ui.geometry.Size(g.gutterWidth, size.height),
    )
    drawRect(
        Color.White,
        topLeft = Offset(0f, 0f),
        size = androidx.compose.ui.geometry.Size(size.width, g.headerHeight),
    )

    for (row in 0 until g.rows) {
        val rect = g.rowHeaderRect(row)
        if (rect.bottom < g.headerHeight || rect.top > size.height) continue
        val l = layout(labelFor(g.rowRef(row)), labelStyle)
        // Right-aligned against the gutter edge, so a label always sits
        // against the grid it labels. Left-aligning looks fine at 1x and then
        // drifts: the gutter scales with zoom, so the gap between the end of
        // the text and the first cell grows with every pinch, until the label
        // is reading at nothing. Clamped so a label wider than the gutter
        // spills right, over the dead corner, rather than off-screen left.
        val x = maxOf(2f, g.gutterWidth - l.size.width - 6f)
        drawText(l, topLeft = Offset(x, rect.centre.y - l.size.height / 2f))
    }
    for (col in 0 until g.cols) {
        val rect = g.colHeaderRect(col)
        if (rect.right < g.gutterWidth || rect.left > size.width) continue
        val l = layout(labelFor(g.colRef(col)), labelStyle)
        if (!rotatedHeaders) {
            drawText(
                l,
                topLeft = Offset(
                    rect.centre.x - l.size.width / 2f,
                    g.headerHeight - l.size.height - 2f,
                ),
            )
        } else {
            // A quarter turn anticlockwise about the anchor. Rotating by -90
            // sends an offset (dx, dy) from the pivot to (dy, -dx), so text
            // laid out rightwards ends up running upwards from the anchor and
            // its height becomes its horizontal extent -- hence the half-height
            // shift, which is what centres it over the column.
            val h = l.size.height.toFloat()
            val anchor = Offset(rect.centre.x - h / 2f, g.headerHeight - 5f)
            rotate(degrees = -90f, pivot = anchor) {
                drawText(l, topLeft = anchor)
            }
        }
    }
}
