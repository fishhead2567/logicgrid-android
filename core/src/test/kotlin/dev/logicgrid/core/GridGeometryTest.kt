package dev.logicgrid.core

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Round-trip invariants for the composite chart's geometry.
 *
 * Hit-testing a dense grid under a pan/zoom transform is where this app is
 * most likely to be quietly wrong -- a half-pixel rounding difference between
 * "where do I draw cell (4,7)" and "which cell is under this finger" gives a
 * grid that selects the neighbouring cell near its edges, which is miserable to
 * use and very easy to ship. These sweep the real grid shapes across a range of
 * zooms, including deliberately non-dyadic ones, so rounding has nowhere to
 * hide.
 */
class GridGeometryTest {

    private val zooms = listOf(0.25f, 0.5f, 0.731f, 1f, 1.37f, 2f, 2.5f, 4f)

    private fun schemas(): List<Pair<String, Schema>> {
        val dir = File("src/test/resources/fixtures")
        return dir.listFiles { f -> f.name.endsWith(".board.json") }!!
            .sortedBy { it.name }
            .map { file ->
                val root = PuzzleJson.json.parseToJsonElement(file.readText()).jsonObject
                val puzzle = PuzzleJson.fromObject(root.getValue("puzzle").jsonObject)
                "${puzzle.categoryCount}x${puzzle.size} (${file.name})" to puzzle.schema
            }
            // One of each shape is enough; geometry does not care about content.
            .distinctBy { it.first.substringBefore(' ') }
    }

    private fun geometry(schema: Schema, zoom: Float, pan: Pt = Pt(0f, 0f)) =
        GridGeometry(schema, cellPx = 40f, gutterPx = 96f, headerPx = 72f, zoom = zoom, pan = pan)

    @TestFactory
    fun `the centre of a drawn cell hit-tests back to that cell`(): List<DynamicTest> =
        schemas().map { (name, schema) ->
            DynamicTest.dynamicTest(name) {
                for (zoom in zooms) {
                    for (pan in listOf(Pt(0f, 0f), Pt(-37.5f, -11.25f), Pt(-400f, -250f))) {
                        val g = geometry(schema, zoom, pan)
                        for (row in 0 until g.rows) {
                            for (col in 0 until g.cols) {
                                if (!g.isUsable(row, col)) continue
                                val centre = g.cellRect(row, col).centre
                                // A cell panned under the sticky headers is
                                // occluded by them and must NOT be hittable --
                                // tapping a header should never mark a cell.
                                // Those cells are covered by the occlusion test
                                // below; here we only round-trip visible ones.
                                if (centre.x < g.scaledGutter || centre.y < g.scaledHeader) {
                                    continue
                                }
                                val hit = g.hitTest(centre)
                                assertEquals(
                                    GridHit.CellHit(GridCell(row, col)), hit,
                                    "$name zoom=$zoom pan=$pan cell=($row,$col)",
                                )
                            }
                        }
                    }
                }
            }
        }

    @TestFactory
    fun `display and canonical coordinates round-trip`(): List<DynamicTest> =
        schemas().map { (name, schema) ->
            DynamicTest.dynamicTest(name) {
                val g = geometry(schema, 1f)
                var usable = 0
                for (row in 0 until g.rows) {
                    for (col in 0 until g.cols) {
                        val cell = g.canonicalAt(row, col)
                        if (!g.isUsable(row, col)) {
                            assertNull(cell, "$name ($row,$col) should be dead")
                            continue
                        }
                        assertNotNull(cell, "$name ($row,$col) should be usable")
                        assertEquals(
                            GridCell(row, col), g.displayOf(cell!!),
                            "$name: ($row,$col) -> $cell did not come back",
                        )
                        usable++
                    }
                }
                // C(N,2) * M^2 usable cells, as the roadmap's table says.
                val n = schema.categories.size
                assertEquals(n * (n - 1) / 2 * g.items * g.items, usable, "$name usable count")
            }
        }

    @TestFactory
    fun `cells tile the content with no gaps and no overlaps`(): List<DynamicTest> =
        schemas().map { (name, schema) ->
            DynamicTest.dynamicTest(name) {
                for (zoom in zooms) {
                    val g = geometry(schema, zoom)
                    // Walking the diagonal of every cell boundary must change
                    // index by exactly one, with nothing skipped.
                    for (col in 0 until g.cols - 1) {
                        val a = g.cellRect(0, col)
                        val b = g.cellRect(0, col + 1)
                        assertEquals(
                            a.right, b.left, 0.001f,
                            "$name zoom=$zoom: gap between cols $col and ${col + 1}",
                        )
                    }
                    for (row in 0 until g.rows - 1) {
                        val a = g.cellRect(row, 0)
                        val b = g.cellRect(row + 1, 0)
                        assertEquals(
                            a.bottom, b.top, 0.001f,
                            "$name zoom=$zoom: gap between rows $row and ${row + 1}",
                        )
                    }
                    val content = g.contentSize()
                    assertEquals(
                        content.width, g.cellRect(0, g.cols - 1).right, 0.01f,
                        "$name zoom=$zoom: content width disagrees with the last cell",
                    )
                    assertEquals(
                        content.height, g.cellRect(g.rows - 1, 0).bottom, 0.01f,
                        "$name zoom=$zoom: content height disagrees with the last cell",
                    )
                }
            }
        }

    @TestFactory
    fun `headers stay pinned on their own axis`(): List<DynamicTest> =
        schemas().map { (name, schema) ->
            DynamicTest.dynamicTest(name) {
                val pan = Pt(-250f, -130f)
                val g = geometry(schema, 1f, pan)
                val unpanned = geometry(schema, 1f)
                for (row in 0 until g.rows) {
                    val r = g.rowHeaderRect(row)
                    assertEquals(0f, r.left, "$name: row header drifted horizontally")
                    assertEquals(
                        g.cellRect(row, 0).top, r.top, 0.001f,
                        "$name: row header lost vertical sync with its row",
                    )
                }
                for (col in 0 until g.cols) {
                    val c = g.colHeaderRect(col)
                    assertEquals(0f, c.top, "$name: column header drifted vertically")
                    assertEquals(
                        g.cellRect(0, col).left, c.left, 0.001f,
                        "$name: column header lost horizontal sync with its column",
                    )
                }
                // And the corner gutter is inert.
                assertEquals(GridHit.Dead, g.hitTest(Pt(10f, 10f)), "$name: corner should be dead")
                assertTrue(unpanned.rows > 0)
            }
        }

    @TestFactory
    fun `a point outside the content is not a cell`(): List<DynamicTest> =
        schemas().map { (name, schema) ->
            DynamicTest.dynamicTest(name) {
                val g = geometry(schema, 1f)
                val content = g.contentSize()
                for (p in listOf(
                    Pt(content.width + 5f, 100f),
                    Pt(200f, content.height + 5f),
                    Pt(content.width + 50f, content.height + 50f),
                )) {
                    assertEquals(GridHit.Outside, g.hitTest(p), "$name: $p should be outside")
                }
            }
        }

    /**
     * A point above or left of the grid must not fall onto index 0.
     *
     * `Float.toInt()` truncates toward zero, so -0.4 becomes 0 and a tap in the
     * header band would select the first row. Panned content produces negative
     * coordinates all the time, so this is a live hazard rather than a nicety.
     */
    @Test
    fun `negative coordinates do not land on the first cell`() {
        val (_, schema) = schemas().first()
        val g = geometry(schema, 1f, Pt(-200f, -200f))
        // Just inside the content area but left/above the first visible cell.
        val justBefore = Pt(g.scaledGutter + 1f, g.scaledHeader + 1f)
        val hit = g.hitTest(justBefore)
        assertTrue(
            hit != GridHit.CellHit(GridCell(0, 0)),
            "a point before the panned origin must not be cell (0,0), got $hit",
        )
    }

    /**
     * Cells scrolled beneath the sticky headers belong to the headers.
     *
     * The headers are drawn over the cells, so a tap there must resolve to the
     * header -- never to the cell hiding underneath it. Getting this backwards
     * would mark a random cell every time the player taps a label.
     */
    @TestFactory
    fun `cells under the sticky headers resolve to the header`(): List<DynamicTest> =
        schemas().map { (name, schema) ->
            DynamicTest.dynamicTest(name) {
                // Pan far enough that the first few rows and columns are hidden.
                val g = geometry(schema, 1f, Pt(-3.5f * 40f, -2.5f * 40f))
                var occluded = 0
                for (row in 0 until g.rows) {
                    for (col in 0 until g.cols) {
                        if (!g.isUsable(row, col)) continue
                        val centre = g.cellRect(row, col).centre
                        val underGutter = centre.x < g.scaledGutter
                        val underHeader = centre.y < g.scaledHeader
                        if (!underGutter && !underHeader) continue
                        occluded++
                        val hit = g.hitTest(centre)
                        assertTrue(
                            hit !is GridHit.CellHit,
                            "$name ($row,$col) is under a header but hit-tested as a cell",
                        )
                    }
                }
                assertTrue(occluded > 0, "$name: the pan should have occluded something")
            }
        }

    @Test
    fun `clamp keeps content against the viewport and never past it`() {
        val (_, schema) = schemas().last()
        val g = geometry(schema, 1f)
        val viewport = Size(400f, 800f)
        // Panning right/down past the origin is not allowed.
        assertEquals(Pt(0f, 0f), g.clampPan(Pt(200f, 300f), viewport))
        // Panning far left/up stops at the content edge.
        val far = g.clampPan(Pt(-99999f, -99999f), viewport)
        val content = g.contentSize()
        val minX = (viewport.width - g.scaledGutter) - (content.width - g.scaledGutter)
        assertEquals(minOf(minX, 0f), far.x, 0.01f)
        assertTrue(far.x <= 0f && far.y <= 0f)
    }

    @Test
    fun `fit-width zoom accounts for the gutter`() {
        val (_, schema) = schemas().last()
        val g = geometry(schema, 1f)
        val viewport = Size(400f, 800f)
        val zoom = g.zoomToFitWidth(viewport)
        val fitted = g.withZoom(zoom)
        assertEquals(
            viewport.width, fitted.contentSize().width, 0.01f,
            "fit-width must include the row-label gutter, not just the cells",
        )
    }

    /** The roadmap's measurement, asserted rather than trusted. */
    @Test
    fun `the worst-case chart really is 24 columns`() {
        val worst = schemas().maxByOrNull { (_, s) -> (s.categories.size - 1) * s.size }!!
        val g = geometry(worst.second, 1f)
        assertEquals(24, g.cols, "worst case should be 24 columns: ${worst.first}")
        assertEquals(24, g.rows)
        // 44dp-equivalent cells would be far wider than a phone.
        val at44 = GridGeometry(worst.second, 44f, 96f, 72f).contentSize()
        assertTrue(at44.width > 1000f, "expected >1000px at 44dp cells, got ${at44.width}")
    }
}
