package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.game.GameUiState
import pt.tiago.neonline.game.RivalThreatCue
import pt.tiago.neonline.ui.board.rivalThreatText

/** State changes and unavailable input discard inspection; the move clock is never paused here. */
@Composable
internal fun RivalThreatDetails(ui: GameUiState, cues: List<RivalThreatCue>) {
    if (cues.isEmpty()) return
    key(ui.state, ui.acceptsInput) {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.rival_threat_details, cues.size)) }
        if (open) AlertDialog(onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.rival_threat_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rival_threat_note))
                    cues.forEach { Text(rivalThreatText(it)) }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.rotation_close)) } })
    }
}
