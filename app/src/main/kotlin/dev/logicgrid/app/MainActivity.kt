package dev.logicgrid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logicgrid.core.Narrative
import dev.logicgrid.core.Puzzle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LogicGridTheme {
                Surface(
                    Modifier
                        .fillMaxSize()
                        // From Android 15 every app is laid out edge to edge
                        // whether it opts in or not, so without this the first
                        // rows sit under the status bar and the clock eats
                        // their taps. safeDrawing covers the cutout too.
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val store = remember { PrefsProgressStore(context) }
    val content = remember { ContentModel.forApp(context, store) }

    // Which puzzle is open, or null for the menu. rememberSaveable so a
    // rotation or a process death returns to the same puzzle rather than
    // dumping the player back at the top of a fifty-row list.
    var openPid: String? by rememberSaveable { mutableStateOf(null) }

    // Bumped whenever a puzzle is left, to re-read progress for the menu.
    // SharedPreferences is not observable, so without this the ticks would
    // only ever update on a cold start.
    var revision by remember { mutableStateOf(0) }

    // Saveable, so the settings screen survives a rotation rather than
    // dropping back to the library.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // The puzzle itself, held rather than looked up per frame.
    //
    // A content update can land while someone is solving, and the puzzle they
    // are on may not be in the new set. Looking the entry up every frame would
    // then drop them back to the menu mid-grid. Once a puzzle is open it stays
    // open until they leave it.
    var open: Puzzle? by remember { mutableStateOf(null) }
    var narrative: Narrative? by remember { mutableStateOf(null) }
    LaunchedEffect(openPid, content.library) {
        val pid = openPid
        if (pid == null) {
            open = null
        } else if (open?.pid != pid) {
            // Both files in one IO pass: a narrative fetched a frame later
            // would show the canonical clues first and then swap them under
            // the player mid-read.
            val loaded = withContext(Dispatchers.IO) {
                content.library.firstOrNull { it.pid == pid }?.let { entry ->
                    content.puzzle(entry)?.let { p -> p to content.narrative(entry, p) }
                }
            }
            open = loaded?.first
            narrative = loaded?.second
            // Restored to a puzzle the current set no longer has.
            if (open == null) openPid = null
        }
    }

    val puzzle = open
    if (puzzle == null) {
        // Both are re-read when an update lands, because applying one forgets
        // the progress of every puzzle that left.
        val solved = remember(revision, content.library) { store.solvedPids() }
        val started = remember(revision, content.library) { store.startedPids() }
        val tally = remember(revision, content.library) { store.solvedEver().size }
        LaunchedEffect(Unit) { content.refresh() }
        if (settingsOpen) {
            // System back returns to the library, not out of the app.
            BackHandler(enabled = true) { settingsOpen = false }
            SettingsScreen(
                prefs = store,
                // Re-read when the library or the sync state changes, so a
                // check started from in here updates what it is showing.
                info = remember(content.library, content.status) { content.info() },
                sync = content.status,
                solvedEver = tally,
                onCheckForUpdates = content::recheck,
                onBack = { settingsOpen = false },
            )
        } else {
            MenuScreen(
                entries = content.library,
                stateOf = {
                    when {
                        it.pid in solved -> RowState.Solved
                        it.pid in started -> RowState.InProgress
                        else -> RowState.Untouched
                    }
                },
                onOpen = { openPid = it.pid },
                solvedEver = tally,
                sync = content.status,
                onAcknowledge = content::acknowledge,
                onDismiss = content::dismiss,
                onSettings = { settingsOpen = true },
            )
        }
    } else {
        val leave = {
            revision++
            openPid = null
        }
        // System back leaves the puzzle, not the app. Marks are already on
        // disk by this point, so leaving never loses anything.
        BackHandler(enabled = true, onBack = leave)
        SolveScreen(puzzle, store, narrative, onExit = leave)
    }
}

@Composable
fun SolveScreen(
    puzzle: Puzzle,
    store: ProgressStore,
    narrative: Narrative? = null,
    onExit: () -> Unit,
) {
    val chart = LocalChart.current
    val model = remember(puzzle.pid) { SolveViewModel(puzzle, store, narrative = narrative) }
    val selected = model.selected

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp, 2.dp, 4.dp, 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leaving stays a single tap, and mirrors the back gesture. Only
            // the settings move behind the overflow.
            TextButton(onClick = onExit, modifier = Modifier.testTag("exit")) {
                Text("‹  Menu")
            }
            Text(
                puzzle.title,
                Modifier.weight(1f).testTag("title"),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
            ViewMenu(model)
        }
        Text(
            "${puzzle.profile} ${puzzle.rating}/7 — " +
                "${puzzle.categoryCount}x${puzzle.size} — " +
                "${model.progress.first}/${model.progress.second} placed",
            Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
        )

        androidx.compose.foundation.layout.Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(chart.paper)
                .testTag("grid")
        ) {
            CompositeGrid(
                puzzle = puzzle,
                closure = model.closure,
                selected = selected,
                geometry = model.geometry,
                rotatedHeaders = model.rotatedHeaders,
                onTransform = { zoom, pan -> model.onTransform(zoom, pan) },
                onCellTap = { model.select(it) },
                onHeaderTap = { },
                onViewportChanged = model::onViewportChanged,
            )
        }

        val g = model.geometry
        val where = selected?.let { cell ->
            val state = when (model.markAt(cell)) {
                true -> "marked O (together)"
                false -> "marked x (apart)"
                null -> "unmarked"
            }
            "${g.rowRef(cell.row).item}  /  ${g.colRef(cell.col).item} — $state"
        } ?: "tap a cell to select it"
        Text(
            where,
            Modifier
                .fillMaxWidth()
                .background(chart.statusStrip)
                .padding(12.dp, 8.dp)
                .testTag("status"),
            style = MaterialTheme.typography.bodySmall,
        )

        when (model.outcome) {
            SolveViewModel.Outcome.Solved -> SolvedBanner(onExit)
            SolveViewModel.Outcome.Wrong -> Banner(
                "Not quite — the sheet is full but ${model.mistakes().size} mark(s) are wrong.",
                chart.bannerBad,
            )
            SolveViewModel.Outcome.Playing -> {}
        }

        // Below the outcome, not instead of it. A solved board can still be
        // checked, and a full-but-wrong one says both things without either
        // contradicting the other.
        when (model.checked) {
            SolveViewModel.Checked.Clean -> Banner(
                "No mistakes so far — everything you have placed is right.",
                chart.bannerGood,
            )
            SolveViewModel.Checked.Wrong -> Banner(
                "Something is wrong — one of your marks contradicts the answer.",
                chart.bannerBad,
            )
            null -> {}
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button({ model.markSelected(true) }, Modifier.testTag("markYes")) { Text("O") }
            Button({ model.markSelected(false) }, Modifier.testTag("markNo")) { Text("X") }
            OutlinedButton({ model.clearSelected() }, Modifier.testTag("clear")) { Text("Clear") }
            OutlinedButton({ model.undo() }, Modifier.testTag("undo")) { Text("Undo") }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton({ model.nudge(0, -1) }, Modifier.testTag("left")) { Text("←") }
            OutlinedButton({ model.nudge(0, 1) }, Modifier.testTag("right")) { Text("→") }
            OutlinedButton({ model.nudge(-1, 0) }, Modifier.testTag("up")) { Text("↑") }
            OutlinedButton({ model.nudge(1, 0) }, Modifier.testTag("down")) { Text("↓") }
        }

        AnswerSheet(puzzle, model.answer)

        CluePanel(model)
    }
}


/**
 * The clue panel: narrated prose where this puzzle has any, canonical text
 * otherwise.
 *
 * The two are interchangeable by construction -- a published narrative was
 * read back by three independent extractors who all recovered the same unique
 * solution -- so switching between them mid-puzzle can cost the player a
 * sentence they were part way through, and nothing more.
 *
 * The narrated version prints its own title and scene, then the cast block
 * verbatim, then its clues. The cast block is not narrated on purpose: it is a
 * list, and a list read aloud as prose is longer and harder to scan than the
 * list.
 */
@Composable
private fun ColumnScope.CluePanel(model: SolveViewModel) {
    val narrative = model.narrative
    val canonical = model.puzzle.text
    if (narrative == null && canonical == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("clues")
    ) {
        val body = MaterialTheme.typography.bodySmall
        if (model.narrated && narrative != null) {
            if (narrative.title.isNotBlank()) {
                Text(
                    narrative.title,
                    Modifier.padding(bottom = 6.dp).testTag("narrativeTitle"),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (narrative.introduction.isNotBlank()) {
                Text(narrative.introduction, style = body)
            }
            if (narrative.preamble.isNotBlank()) {
                Text(
                    narrative.preamble.replace("**", ""),
                    Modifier.padding(top = 8.dp),
                    style = body,
                )
            }
            narrative.clues.forEachIndexed { i, clue ->
                Text(
                    "${i + 1}. $clue",
                    Modifier.padding(top = 6.dp),
                    style = body,
                )
            }
        } else if (canonical != null) {
            Text(canonical.intro.replace("**", ""), style = body)
            canonical.clues.forEachIndexed { i, clue ->
                Text("${i + 1}. $clue", Modifier.padding(top = 6.dp), style = body)
            }
        }
    }
}

/**
 * View settings, behind an overflow.
 *
 * They were two more buttons in the control row, which pushed it past the
 * screen and cost a scroll to reach the arrows -- the controls used most. A
 * setting is touched once a session; a nudge is touched constantly. Putting
 * the rare thing behind a tap to keep the common thing immediate is the right
 * way round, and it costs no vertical space at all.
 */
@Composable
private fun ViewMenu(model: SolveViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("viewMenu")) {
            Text("⋮", style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(check(model.assist) + "Assist") },
                onClick = { model.toggleAssist(); open = false },
                modifier = Modifier.testTag("menuAssist"),
            )
            DropdownMenuItem(
                text = { Text(check(model.rotatedHeaders) + "Turned labels") },
                onClick = { model.toggleHeaderStyle(); open = false },
                modifier = Modifier.testTag("menuHeaders"),
            )
            // Absent rather than disabled when the puzzle has no narrative:
            // a greyed row invites a tap and explains nothing, and most
            // puzzles have none.
            if (model.narrative != null) {
                DropdownMenuItem(
                    text = { Text(check(model.narrated) + "Narrated clues") },
                    onClick = { model.toggleNarrated(); open = false },
                    modifier = Modifier.testTag("menuNarrated"),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                // Not "Check answer": it does not show one. It says whether
                // what you have can still be right, which is the help the
                // back of a puzzle magazine gives you.
                text = { Text("Check my work") },
                onClick = { model.check(); open = false },
                modifier = Modifier.testTag("menuCheck"),
            )
            DropdownMenuItem(
                // A pinch can leave the chart at a zoom that is hard to undo
                // by pinching back. This is the way out, and on a wide chart
                // it is also the most useful starting view.
                text = { Text("Fit width") },
                onClick = { model.fitWidth(); open = false },
                modifier = Modifier.testTag("menuFit"),
            )
            DropdownMenuItem(
                text = { Text("Reset this puzzle") },
                onClick = { model.reset(); open = false },
                modifier = Modifier.testTag("menuReset"),
            )
        }
    }
}

/** A tick in the text, so state does not rest on colour or position alone. */
private fun check(on: Boolean): String = if (on) "✓  " else "    "

/**
 * Solving is the one moment the player is certainly finished, so it is the one
 * moment worth offering the way out directly.
 */
@Composable
private fun SolvedBanner(onExit: () -> Unit) {
    val chart = LocalChart.current
    Row(
        Modifier.fillMaxWidth().background(chart.bannerGood).padding(12.dp, 4.dp).testTag("banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SOLVED — every placing correct.",
            Modifier.weight(1f),
            color = chart.onBanner,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onExit, modifier = Modifier.testTag("exitSolved")) {
            Text("Next puzzle", color = chart.onBanner)
        }
    }
}

@Composable
private fun Banner(message: String, colour: Color) {
    Text(
        message,
        Modifier.fillMaxWidth().background(colour).padding(12.dp, 8.dp).testTag("banner"),
        color = LocalChart.current.onBanner,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun AnswerSheet(puzzle: Puzzle, answer: Map<String, List<String?>>) {
    if (answer.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().background(LocalChart.current.sheet).padding(8.dp).testTag("answer")
    ) {
        for (position in 0 until puzzle.size) {
            val row = puzzle.schema.categories.joinToString("  ") { cat ->
                (answer[cat.name]?.get(position) ?: "?").take(14).padEnd(14)
            }
            Text(row, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1)
        }
    }
}
