package dev.logicgrid.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logicgrid.core.GridHit
import dev.logicgrid.core.Puzzle
import dev.logicgrid.core.PuzzleJson
import dev.logicgrid.core.Size as CoreSize

/** Reads the content bundled in assets. The network is never on this path. */
object AssetContent {
    fun puzzleIds(context: Context): List<String> =
        context.assets.list("content/puzzles")!!.sorted().map { it.removeSuffix(".json") }

    fun puzzle(context: Context, id: String): Puzzle =
        context.assets.open("content/puzzles/$id.json").use { stream ->
            PuzzleJson.parse(stream.readBytes().decodeToString())
        }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ids = AssetContent.puzzleIds(this)
        val puzzle = AssetContent.puzzle(this, ids.first())
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { SolveScreen(puzzle) }
            }
        }
    }
}

@Composable
fun SolveScreen(puzzle: Puzzle) {
    val model = remember(puzzle.pid) { SolveViewModel(puzzle) }
    var viewport by remember { mutableStateOf(CoreSize(1000f, 1600f)) }
    val selected = model.selected

    Column(Modifier.fillMaxSize()) {
        Text(
            puzzle.title,
            Modifier.padding(12.dp, 10.dp, 12.dp, 2.dp).testTag("title"),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "${puzzle.profile} ${puzzle.rating}/7 — " +
                "${puzzle.categoryCount}x${puzzle.size} — " +
                "${model.progress.first}/${model.progress.second} placed",
            Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
        )

        // The chart.
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
                onTransform = { zoom, pan -> model.onTransform(zoom, pan, viewport) },
                onCellTap = { model.select(it) },
                onHeaderTap = { hit ->
                    // Tapping a label is a navigation aid, not a mark.
                    when (hit) {
                        is GridHit.RowHeader -> model.nudge(0, 0)
                        is GridHit.ColHeader -> model.nudge(0, 0)
                        else -> {}
                    }
                },
            )
        }

        // Status line: always names the exact cell, which is what lets the
        // chart's own cells stay small enough to fit.
        val g = model.geometry
        val where = selected?.let { cell ->
            val mark = model.markAt(cell)
            val state = when (mark) {
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
            SolveViewModel.Outcome.Solved -> Banner("SOLVED — every placing correct.", Color(0xFF1B7F3B))
            SolveViewModel.Outcome.Wrong ->
                Banner("Not quite — the sheet is full but ${model.mistakes().size} mark(s) are wrong.", Color(0xFFB3261E))
            SolveViewModel.Outcome.Playing -> {}
        }

        // Marking acts on the selection, so it works at any zoom.
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button({ model.markSelected(true) }, Modifier.testTag("markYes")) { Text("O") }
            Button({ model.markSelected(false) }, Modifier.testTag("markNo")) { Text("X") }
            OutlinedButton({ model.clearSelected() }, Modifier.testTag("clear")) { Text("Clear") }
            OutlinedButton({ model.undo() }, Modifier.testTag("undo")) { Text("Undo") }
        }
        // Nudge: at small zoom a mis-tap is common, and without this the only
        // correction is zoom in, re-tap, zoom out. Also gives keyboard and
        // switch access a route in.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton({ model.nudge(0, -1) }, Modifier.testTag("left")) { Text("←") }
            OutlinedButton({ model.nudge(0, 1) }, Modifier.testTag("right")) { Text("→") }
            OutlinedButton({ model.nudge(-1, 0) }, Modifier.testTag("up")) { Text("↑") }
            OutlinedButton({ model.nudge(1, 0) }, Modifier.testTag("down")) { Text("↓") }
            OutlinedButton({ model.toggleAssist() }, Modifier.testTag("assist")) {
                Text(if (model.assist) "Assist on" else "Assist off")
            }
        }

        // The answer sheet is the deliverable of the puzzle, so it stays
        // visible rather than being reduced to "n/m placed".
        AnswerSheet(puzzle, model.answer)

        // Clue text: pre-rendered by the generator, so nothing here has to
        // reimplement English.
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
