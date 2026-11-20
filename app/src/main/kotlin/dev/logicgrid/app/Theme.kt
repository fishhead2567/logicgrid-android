package dev.logicgrid.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The chart's own palette.
 *
 * Material 3's `colorScheme` dresses the chrome -- buttons, menus, the app
 * background -- but a logic chart is drawn on a Canvas, and the things it draws
 * are not Material roles. "Grid line", "category separator", "the unused half
 * of the triangle", "the O", "the X" have no equivalent in `surfaceVariant` and
 * `onSecondaryContainer`, and forcing them into those names would mean every
 * future reader working out which role meant which pencil stroke.
 *
 * So they are named for what they draw, defined once per scheme, and reached
 * through [LocalChart]. The rule the test enforces: no composable outside this
 * file writes a colour literal, because a literal is a colour that cannot
 * change with the scheme and will be the one thing still glowing white when
 * everything around it has gone dark.
 */
@Immutable
data class ChartColours(
    /** The chart's background. Paper, in both senses. */
    val paper: Color,
    /** Cell borders. */
    val line: Color,
    /** The heavier rules between categories. */
    val block: Color,
    /** The triangle's unused half: inert, not empty. */
    val dead: Color,
    /** The row-and-column wash behind the selected cell. Translucent. */
    val selection: Color,
    /** Header text on the chart. */
    val label: Color,
    /** The O. */
    val yes: Color,
    /** The X. */
    val no: Color,
    /** The strip under the chart that names the selected cell. */
    val statusStrip: Color,
    /** The answer sheet's background. */
    val sheet: Color,
    /** Secondary text: counts, subtitles, the sync line. */
    val dim: Color,
    /** List dividers. */
    val hairline: Color,
    /** The library glyph for a puzzle begun but not finished. */
    val inProgress: Color,
    /** The library glyph for a puzzle not yet opened. */
    val untouched: Color,
    /**
     * Banner fills, with one text colour for both.
     *
     * These stay dark in *both* schemes, unlike [yes] and [no]. A banner is a
     * solid block with white text on it, so it needs a colour that white reads
     * against; a glyph is a thin stroke on the chart, so it needs a colour that
     * reads against the paper. In dark mode those two requirements pull in
     * opposite directions, which is why they are separate fields rather than
     * one green reused twice.
     */
    val bannerGood: Color,
    val bannerBad: Color,
    val onBanner: Color,
)

/**
 * Light. These are the values the app shipped with, kept exactly, so turning
 * the lights on changes nothing anybody had already looked at.
 */
private val lightChart = ChartColours(
    paper = Color.White,
    line = Color(0xFFBBBBBB),
    block = Color(0xFF666666),
    dead = Color(0xFFEDEDED),
    selection = Color(0x332196F3),
    label = Color(0xFF444444),
    yes = Color(0xFF1B7F3B),
    no = Color(0xFFB3261E),
    statusStrip = Color(0xFFF2F2F2),
    sheet = Color(0xFFFAFAFA),
    dim = Color(0xFF666666),
    hairline = Color(0xFFEEEEEE),
    inProgress = Color(0xFFB07400),
    untouched = Color(0xFFBBBBBB),
    bannerGood = Color(0xFF1B7F3B),
    bannerBad = Color(0xFFB3261E),
    onBanner = Color.White,
)

/**
 * Dark.
 *
 * Not the light values inverted. A chart is mostly empty space with thin
 * strokes on it, and a thin stroke behaves differently on a dark ground: pure
 * black with white lines glares, and a 1px line that read as grey on white
 * disappears entirely. So the paper is a near-black with a little blue in it,
 * the lines come *up* in value rather than down, and the O and X are
 * lightened -- the shipped dark green on a dark ground is unreadable.
 */
private val darkChart = ChartColours(
    paper = Color(0xFF16181B),
    line = Color(0xFF3A3F45),
    block = Color(0xFF8A9199),
    dead = Color(0xFF1E2126),
    selection = Color(0x4D64B5F6),
    label = Color(0xFFC9CED4),
    yes = Color(0xFF5CC97F),
    no = Color(0xFFFF8A80),
    statusStrip = Color(0xFF1E2126),
    sheet = Color(0xFF1A1D21),
    dim = Color(0xFF9BA1A8),
    hairline = Color(0xFF2A2E33),
    inProgress = Color(0xFFE0A64A),
    untouched = Color(0xFF5A6066),
    bannerGood = Color(0xFF1F6B3C),
    bannerBad = Color(0xFF8C2F28),
    onBanner = Color.White,
)

/**
 * Defaults to light rather than throwing, so a preview or a stray composable
 * outside the theme renders readably instead of crashing.
 */
val LocalChart = staticCompositionLocalOf { lightChart }

@Composable
fun LogicGridTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalChart provides if (dark) darkChart else lightChart
    ) {
        MaterialTheme(
            // Baseline Material, not dynamic colour: the chart is the thing
            // being looked at, and a wallpaper-derived scheme would retint the
            // chrome around it from one phone to the next while the chart's own
            // palette stayed put.
            colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
            content = content,
        )
    }
}

/** The two schemes, for tests. */
internal val chartSchemes: List<Pair<String, ChartColours>> =
    listOf("light" to lightChart, "dark" to darkChart)
