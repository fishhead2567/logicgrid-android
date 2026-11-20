package dev.logicgrid.app

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Dark mode, checked as arithmetic rather than by looking at it.
 *
 * Two kinds of failure get caught here, and neither is visible in a
 * screenshot taken on the machine that made the change:
 *
 *  - a colour that never got a dark value, so it stays bright when everything
 *    around it has gone dark;
 *  - a colour that got one, but one nobody can read -- the shipped `yes` green
 *    (#1B7F3B) on a near-black chart is 1.4:1, which is a dark smudge.
 */
class ThemeTest {

    // -- contrast -------------------------------------------------------------

    /** sRGB relative luminance, per WCAG 2.1. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    /** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). */
    private fun contrast(a: Color, b: Color): Double {
        val (x, y) = luminance(a) to luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    @Test
    fun `the marks and labels read against the paper in both schemes`() {
        val bad = mutableListOf<String>()
        for ((name, s) in chartSchemes) {
            // 4.5:1 is the WCAG bar for body text; the chart labels are 9sp,
            // so they get the strict one rather than the large-text 3:1.
            for ((what, colour) in listOf("label" to s.label, "dim" to s.dim)) {
                val ratio = contrast(colour, s.paper)
                if (ratio < 4.5) bad += "$name/$what on paper: %.2f:1".format(ratio)
            }
            // The O, the X and the in-progress glyph are bold shapes, so 3:1 --
            // the bar for graphics and large text.
            for ((what, colour) in listOf(
                "yes" to s.yes, "no" to s.no, "inProgress" to s.inProgress,
            )) {
                val ratio = contrast(colour, s.paper)
                if (ratio < 3.0) bad += "$name/$what on paper: %.2f:1".format(ratio)
            }
            for ((what, fill) in listOf("good" to s.bannerGood, "bad" to s.bannerBad)) {
                val ratio = contrast(s.onBanner, fill)
                if (ratio < 4.5) bad += "$name/banner $what text: %.2f:1".format(ratio)
            }
        }
        assertTrue(bad.isEmpty(), "unreadable: " + bad.joinToString("; "))
    }

    @Test
    fun `a hairline is faint and a block rule is not`() {
        // The two weights carry the chart's structure, so getting them the same
        // way round in both schemes matters more than their exact values.
        for ((name, s) in chartSchemes) {
            assertTrue(
                contrast(s.block, s.paper) > contrast(s.line, s.paper),
                "$name: the block rule must stand out more than a cell border",
            )
            assertTrue(
                contrast(s.dead, s.paper) < 1.5,
                "$name: the unused triangle is inert, not a feature",
            )
        }
    }

    // -- completeness ---------------------------------------------------------

    @Test
    fun `every colour actually changes between the schemes`() {
        val (_, light) = chartSchemes[0]
        val (_, dark) = chartSchemes[1]
        // onBanner is white in both on purpose: a banner is a dark solid fill
        // in either scheme, so the text on it does not need to flip.
        val allowedSame = setOf("onBanner")
        // Reflection rather than a hand-written list of fields, so a colour
        // added later is covered without anyone remembering to add it here.
        // Statics are skipped: Compose generates a `$stable` int on every
        // @Immutable class, and it is the same in both instances by definition.
        val same = ChartColours::class.java.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .filter { it.name !in allowedSame }
            .mapNotNull { field ->
                field.isAccessible = true
                if (field.get(light) == field.get(dark)) field.name else null
            }
        val compared = ChartColours::class.java.declaredFields
            .count { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
        assertTrue(compared >= 17, "only $compared fields compared -- reflection broke")
        assertTrue(
            same.isEmpty(),
            "identical in light and dark, so probably never given a dark value: $same",
        )
    }

    // -- the rule that keeps it that way --------------------------------------

    @Test
    fun `no screen writes a colour literal`() {
        // The whole point of the palette is that a scheme switch reaches every
        // colour. A literal is one that cannot change, and it will be the one
        // thing still glowing white on a dark phone. Theme.kt is where they
        // are allowed to live, and nowhere else.
        val literal = Regex("""Color\(0x|Color\.White|Color\.Black|Color\.Red|Color\.Green""")
        val sources = File("src/main/kotlin/dev/logicgrid/app")
            .listFiles { f -> f.name.endsWith(".kt") && f.name != "Theme.kt" }
            .orEmpty()
        // Without this, a wrong working directory makes the whole test pass by
        // finding nothing to complain about.
        assertTrue(
            sources.size >= 5,
            "found ${sources.size} sources to scan, which cannot be right",
        )
        val offenders = sources
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> literal.containsMatchIn(line) }
                    .map { (i, line) -> "${file.name}:${i + 1}  ${line.trim()}" }
            }
        assertTrue(
            offenders.isEmpty(),
            "colour literals outside Theme.kt:\n" + offenders.joinToString("\n"),
        )
    }
}
