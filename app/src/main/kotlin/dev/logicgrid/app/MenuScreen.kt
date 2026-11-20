package dev.logicgrid.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logicgrid.core.LibraryEntry

/** How far along one puzzle is, as the menu needs to show it. */
enum class RowState { Untouched, InProgress, Solved }

@Composable
fun MenuScreen(
    entries: List<LibraryEntry>,
    stateOf: (LibraryEntry) -> RowState,
    onOpen: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
    /** Solved on this device all told, including puzzles since replaced. */
    solvedEver: Int = 0,
    sync: SyncUi = SyncUi.Quiet,
    onAcknowledge: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val chart = LocalChart.current
    val solved = entries.count { stateOf(it) == RowState.Solved }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 14.dp, 4.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f)) {
            Text("Logic Problems", style = MaterialTheme.typography.titleLarge)
            Text(
                // The all-time figure appears only once it says something the
                // first number does not -- which is to say, once an update has
                // taken a puzzle the player solved out of the library.
                "$solved of ${entries.size} solved" +
                    if (solvedEver > solved) "  ·  $solvedEver all time" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = chart.dim,
                modifier = Modifier.testTag("menuSummary"),
            )
          }
            TextButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                Text("Settings")
            }
        }
        SyncNotice(sync, onDismiss)
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize().testTag("menuList")) {
            items(entries, key = { it.pid }) { entry ->
                PuzzleRow(entry, stateOf(entry)) { onOpen(entry) }
                HorizontalDivider(color = chart.hairline)
            }
        }
    }
    if (sync is SyncUi.Warning) WipeWarning(sync, onAcknowledge)
    if (sync is SyncUi.Working && sync.blocking) Updating(sync)
}

/**
 * Holds the screen while an acknowledged update downloads.
 *
 * Two jobs, and the second is the one that is easy to miss. It shows that
 * something is happening -- tapping through the notice and getting a library
 * that looks untouched for twenty seconds reads as a dead button. And it stops
 * a puzzle being opened in the meantime, because the set those rows describe
 * is the one being replaced: start the old puzzle 40 and the update lands and
 * there is no puzzle 40.
 *
 * Only for the acknowledged path. A silent update has nothing to interrupt and
 * no reason to.
 */
@Composable
private fun Updating(sync: SyncUi.Working) {
    val chart = LocalChart.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Updating puzzles") },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = {
                        if (sync.total == 0) 0f else sync.done.toFloat() / sync.total
                    },
                    modifier = Modifier.fillMaxWidth().testTag("syncProgress"),
                )
                Spacer(Modifier.height(12.dp))
                // The number, not only the bar: a bar that moves in six
                // uneven jumps looks stuck, and the count never does.
                Text(
                    "${sync.done} of ${sync.total} files",
                    style = MaterialTheme.typography.bodySmall,
                    color = chart.dim,
                )
            }
        },
        // No button at all. There is nothing to decide and nothing to cancel:
        // the old set is already on its way out.
        confirmButton = {},
        modifier = Modifier.testTag("syncUpdating"),
    )
}

/**
 * A line under the title, never a dialog.
 *
 * Checking for new puzzles is not something the player asked for and not
 * something they can help with, so it gets one line of small text and no more.
 * A failure gets nothing at all: the library is complete either way, and the
 * sync will try again next launch.
 */
@Composable
private fun SyncNotice(sync: SyncUi, onDismiss: () -> Unit) {
    val chart = LocalChart.current
    val (message, colour) = when (sync) {
        is SyncUi.Checking -> "Checking for new puzzles…" to chart.dim
        // The blocking case has the dialog instead; this is the quiet one,
        // where nothing is at stake and the line is the whole of the news.
        is SyncUi.Working ->
            if (sync.blocking) return
            else "Updating… ${sync.done} of ${sync.total}" to chart.dim
        is SyncUi.Updated -> {
            val lost = if (sync.lost > 0) " · ${sync.lost} replaced" else ""
            "Updated — ${sync.puzzles} puzzles$lost" to chart.bannerGood
        }
        else -> return
    }
    Text(
        message,
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss)
            .padding(16.dp, 0.dp, 16.dp, 10.dp)
            .testTag("syncNotice"),
        style = MaterialTheme.typography.bodySmall,
        color = colour,
    )
}

/**
 * Shown before the content is replaced, and only when replacing it costs a
 * grid that is part-finished.
 *
 * There is no decline. Keeping the old set indefinitely would mean supporting
 * two of them, and the puzzles leaving are leaving because they were
 * regenerated -- there is nothing to go back to. So this is a notice, not a
 * question, and the one thing it guarantees is that nobody loses a half-solved
 * puzzle to something they never saw happen.
 */
@Composable
private fun WipeWarning(sync: SyncUi.Warning, onAcknowledge: () -> Unit) {
    AlertDialog(
        // Not dismissible by tapping away: the whole point is that it was read.
        onDismissRequest = {},
        title = { Text("New puzzles are ready") },
        text = {
            val grids = if (sync.started == 1) "1 puzzle" else "${sync.started} puzzles"
            val kept = when (sync.solved) {
                0 -> ""
                1 -> " One of those you had solved; it stays in your all-time count."
                else -> " ${sync.solved} of those you had solved; they stay in your " +
                    "all-time count."
            }
            Text(
                "This update replaces the library with ${sync.puzzles} puzzles.\n\n" +
                    "$grids you had started are not in the new set, so that work " +
                    "cannot be carried over.$kept"
            )
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge, modifier = Modifier.testTag("syncOk")) {
                Text("Update now")
            }
        },
        modifier = Modifier.testTag("syncWarning"),
    )
}

@Composable
private fun PuzzleRow(entry: LibraryEntry, state: RowState, onClick: () -> Unit) {
    val chart = LocalChart.current
    val label = when (state) {
        RowState.Solved -> "solved"
        RowState.InProgress -> "in progress"
        RowState.Untouched -> "not started"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // One description for the whole row, so a screen reader announces
            // "12. Regatta, hard, 5x6, solved" instead of five separate nodes.
            .semantics { contentDescription = "${entry.title}, ${entry.profile}, ${entry.shape}, $label" }
            .padding(16.dp, 12.dp)
            .testTag("row:${entry.pid}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(state)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (state == RowState.Untouched) FontWeight.Normal else FontWeight.Medium,
                color = if (state == RowState.Solved) chart.dim else Color.Unspecified,
            )
            Text(
                "${entry.profile} · ${entry.rating}/7 · ${entry.shape} · ${entry.clues} clues" +
                    if (entry.narrated) " · narrated" else "",
                style = MaterialTheme.typography.bodySmall,
                color = chart.dim,
            )
        }
    }
}

/**
 * Glyph plus colour, never colour alone -- a green dot and a grey dot are the
 * same dot to a colour-blind player.
 */
@Composable
private fun StatusGlyph(state: RowState) {
    val chart = LocalChart.current
    val (glyph, colour) = when (state) {
        RowState.Solved -> "✓" to chart.bannerGood
        RowState.InProgress -> "···" to chart.inProgress
        RowState.Untouched -> " " to chart.untouched
    }
    Text(
        glyph,
        color = colour,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(26.dp),
    )
}
