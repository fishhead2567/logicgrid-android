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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
) {
    val solved = entries.count { stateOf(it) == RowState.Solved }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp, 14.dp, 16.dp, 8.dp)) {
            Text("Logic Problems", style = MaterialTheme.typography.titleLarge)
            Text(
                "$solved of ${entries.size} solved",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF666666),
                modifier = Modifier.testTag("menuSummary"),
            )
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize().testTag("menuList")) {
            items(entries, key = { it.pid }) { entry ->
                PuzzleRow(entry, stateOf(entry)) { onOpen(entry) }
                HorizontalDivider(color = Color(0xFFEEEEEE))
            }
        }
    }
}

@Composable
private fun PuzzleRow(entry: LibraryEntry, state: RowState, onClick: () -> Unit) {
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
                color = if (state == RowState.Solved) Color(0xFF555555) else Color.Unspecified,
            )
            Text(
                "${entry.profile} · ${entry.rating}/7 · ${entry.shape} · ${entry.clues} clues" +
                    if (entry.narrated) " · narrated" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF777777),
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
    val (glyph, colour) = when (state) {
        RowState.Solved -> "✓" to Color(0xFF1B7F3B)
        RowState.InProgress -> "···" to Color(0xFFB07400)
        RowState.Untouched -> " " to Color(0xFFBBBBBB)
    }
    Text(
        glyph,
        color = colour,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(26.dp),
    )
}
