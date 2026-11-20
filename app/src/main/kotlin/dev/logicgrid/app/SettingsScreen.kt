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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Settings, and a plain statement of where the puzzles came from.
 *
 * Deliberately short. Every row here is either a choice the player has already
 * been making one puzzle at a time, or a fact about the content set that the
 * app otherwise keeps to itself.
 *
 * Not here: a narrative-versus-canonical toggle. The published set has no
 * narratives, so it would be a switch with nothing on either side of it.
 */
@Composable
fun SettingsScreen(
    prefs: ViewPrefs,
    info: ContentModel.Info,
    sync: SyncUi,
    solvedEver: Int,
    onCheckForUpdates: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chart = LocalChart.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp, 2.dp, 4.dp, 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("settingsBack")) {
                Text("‹  Library")
            }
            Text("Settings", style = MaterialTheme.typography.titleMedium)
        }

        Section("Solving")
        // Mirrors of the per-puzzle overflow, so a player who wants a setting
        // to stick does not have to find it mid-puzzle and hope it carries.
        SwitchRow(
            title = "Assist on new puzzles",
            detail = "Fills in what follows by transitivity and last cell " +
                "standing. Those are deductions, so it is off unless you ask. " +
                "Puzzles you have already started keep whatever they were " +
                "saved with.",
            checked = prefs.assistByDefault,
            tag = "setAssist",
        ) { prefs.assistByDefault = it }

        SwitchRow(
            title = "Turned labels",
            detail = "Whole names a quarter turn round, as a printed chart has " +
                "them, instead of shortened prefixes upright. Buys room for " +
                "the label at the cost of a taller header band.",
            checked = prefs.rotatedHeaders,
            tag = "setRotated",
        ) { prefs.rotatedHeaders = it }

        Section("Puzzles")
        Column(Modifier.padding(16.dp, 10.dp)) {
            Text(
                "${info.puzzles} puzzles" +
                    if (info.bundled) ", as shipped with the app" else ", downloaded",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Set generated ${info.generated.take(10)}",
                style = MaterialTheme.typography.bodySmall,
                color = chart.dim,
                modifier = Modifier.testTag("contentVersion"),
            )
            Text(
                "$solvedEver solved on this device, all time",
                style = MaterialTheme.typography.bodySmall,
                color = chart.dim,
                modifier = Modifier.testTag("settingsTally"),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    enabled = sync !is SyncUi.Checking && sync !is SyncUi.Working,
                    modifier = Modifier.testTag("checkUpdates"),
                ) { Text("Check for new puzzles") }
                // The same words the library uses, so the two places do not
                // describe one event differently.
                val note = when (sync) {
                    is SyncUi.Checking -> "Checking…"
                    is SyncUi.Working -> "${sync.done} of ${sync.total}"
                    is SyncUi.Updated -> "Updated"
                    else -> ""
                }
                if (note.isNotEmpty()) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = chart.dim)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Updates are fetched in the background and never hold up a " +
                    "puzzle. Everything you have solved stays counted even if " +
                    "an update replaces the puzzle that earned it.",
                style = MaterialTheme.typography.bodySmall,
                color = chart.dim,
            )
        }

        Section("Privacy")
        Column(Modifier.padding(16.dp, 10.dp)) {
            Text(
                "Nothing you do here leaves the device. Progress is stored " +
                    "locally with no account and no backend. The only network " +
                    "request the app ever makes is an unauthenticated fetch of " +
                    "public puzzle files, which carries nothing about you.",
                style = MaterialTheme.typography.bodySmall,
                color = chart.dim,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    val chart = LocalChart.current
    HorizontalDivider(color = chart.hairline)
    Text(
        title.uppercase(),
        Modifier.padding(16.dp, 14.dp, 16.dp, 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = chart.dim,
    )
}

/**
 * The whole row toggles, not just the switch.
 *
 * A 32dp switch at the far edge of a phone screen is a poor target, and the
 * label is the part a thumb is already near. `Role.Switch` keeps the
 * announcement right for a screen reader.
 */
@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    val chart = LocalChart.current
    // Mirrored into local state because ViewPrefs writes straight through to
    // SharedPreferences, which is not observable: without this the switch
    // would not move until the screen was left and re-entered.
    var on by remember(tag) { mutableStateOf(checked) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { on = !on; onChange(on) }
            .padding(16.dp, 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = chart.dim)
        }
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}
