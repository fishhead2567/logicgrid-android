package dev.logicgrid.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import dev.logicgrid.core.LibraryEntry
import dev.logicgrid.core.ManifestJson
import dev.logicgrid.core.Puzzle
import dev.logicgrid.core.PuzzleJson
import dev.logicgrid.core.Size as CoreSize

/** Reads the content bundled in assets. The network is never on this path. */
object AssetContent {
    fun library(context: Context): List<LibraryEntry> =
        context.assets.open("content/manifest.json").use {
            ManifestJson.parse(it.readBytes().decodeToString())
        }

    fun puzzle(context: Context, entry: LibraryEntry): Puzzle =
        context.assets.open("content/${entry.path}").use {
            PuzzleJson.parse(it.readBytes().decodeToString())
        }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
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
    val library = remember { AssetContent.library(context) }

    // Which puzzle is open, or null for the menu. rememberSaveable so a
    // rotation or a process death returns to the same puzzle rather than
    // dumping the player back at the top of a fifty-row list.
    var openPid: String? by rememberSaveable { mutableStateOf(null) }

    // Bumped whenever a puzzle is left, to re-read progress for the menu.
    // SharedPreferences is not observable, so without this the ticks would
    // only ever update on a cold start.
    var revision by remember { mutableStateOf(0) }

    val entry = library.firstOrNull { it.pid == openPid }
    if (entry == null) {
        val solved = remember(revision) { store.solvedPids() }
        val started = remember(revision) { store.startedPids() }
        MenuScreen(
            entries = library,
            stateOf = {
                when {
                    it.pid in solved -> RowState.Solved
                    it.pid in started -> RowState.InProgress
                    else -> RowState.Untouched
                }
            },
            onOpen = { openPid = it.pid },
        )
    } else {
        val puzzle = remember(entry.pid) { AssetContent.puzzle(context, entry) }
        val leave = {
            revision++
            openPid = null
        }
        // System back leaves the puzzle, not the app. Marks are already on
        // disk by this point, so leaving never loses anything.
        BackHandler(enabled = true, onBack = leave)
        SolveScreen(puzzle, store, onExit = leave)
    }
}

@Composable
fun SolveScreen(puzzle: Puzzle, store: ProgressStore, onExit: () -> Unit) {
    val model = remember(puzzle.pid) { SolveViewModel(puzzle, store) }
    val viewport = remember { CoreSize(1000f, 1600f) }
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
                .background(Color.White)
                .testTag("grid")
        ) {
            CompositeGrid(
                puzzle = puzzle,
                closure = model.closure,
                selected = selected,
                geometry = model.geometry,
                rotatedHeaders = model.rotatedHeaders,
                onTransform = { zoom, pan -> model.onTransform(zoom, pan, viewport) },
                onCellTap = { model.select(it) },
                onHeaderTap = { },
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
                .background(Color(0xFFF2F2F2))
                .padding(12.dp, 8.dp)
                .testTag("status"),
            style = MaterialTheme.typography.bodySmall,
        )

        when (model.outcome) {
            SolveViewModel.Outcome.Solved -> SolvedBanner(onExit)
            SolveViewModel.Outcome.Wrong -> Banner(
                "Not quite — the sheet is full but ${model.mistakes().size} mark(s) are wrong.",
                Color(0xFFB3261E),
            )
            SolveViewModel.Outcome.Playing -> {}
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

        puzzle.text?.let { text ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .testTag("clues")
            ) {
                Text(text.intro.replace("**", ""), style = MaterialTheme.typography.bodySmall)
                text.clues.forEachIndexed { i, clue ->
                    Text(
                        "${i + 1}. $clue",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
            HorizontalDivider()
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
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1B7F3B)).padding(12.dp, 4.dp).testTag("banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SOLVED — every placing correct.",
            Modifier.weight(1f),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onExit, modifier = Modifier.testTag("exitSolved")) {
            Text("Next puzzle", color = Color.White)
        }
    }
}

@Composable
private fun Banner(message: String, colour: Color) {
    Text(
        message,
        Modifier.fillMaxWidth().background(colour).padding(12.dp, 8.dp).testTag("banner"),
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun AnswerSheet(puzzle: Puzzle, answer: Map<String, List<String?>>) {
    if (answer.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFFAFAFA)).padding(8.dp).testTag("answer")
    ) {
        for (position in 0 until puzzle.size) {
            val row = puzzle.schema.categories.joinToString("  ") { cat ->
                (answer[cat.name]?.get(position) ?: "?").take(14).padEnd(14)
            }
            Text(row, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1)
        }
    }
}
