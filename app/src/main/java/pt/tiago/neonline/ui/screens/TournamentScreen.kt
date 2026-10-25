package pt.tiago.neonline.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.data.Profile
import pt.tiago.neonline.data.RivalGallery
import pt.tiago.neonline.data.RivalLevel
import pt.tiago.neonline.data.SavedTournament
import pt.tiago.neonline.data.Tournament
import pt.tiago.neonline.data.TournamentStatus

@Composable
fun TournamentScreen(
    profile: Profile,
    levels: List<RivalLevel>,
    resumable: SavedTournament?,
    onStart: (List<String>, Difficulty) -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onAbandon: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val defeated = remember(profile, levels) { Tournament.defeatedRivals(profile, levels) }
    val available = remember(defeated) { RivalGallery.roster.filter { it.id in defeated } }
    val prior = resumable?.bracket ?: profile.savedTournament?.bracket
    var selected by remember(defeated, prior) { mutableStateOf((prior?.filter { it in defeated } ?: available.take(Tournament.ROUNDS).map { it.id }).toSet()) }
    var difficulty by remember(profile, resumable) { mutableStateOf(resumable?.difficulty ?: profile.settings.lastDifficulty) }
    var confirmAbandon by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(stringResource(R.string.tournament_title), onBack)
        Text(stringResource(R.string.tournament_description))
        Text(stringResource(R.string.tournament_defeated_count, available.size, Tournament.ROUNDS))
        Text(stringResource(R.string.tournament_best, profile.tournamentBestRounds[difficulty] ?: 0, Tournament.ROUNDS))

        if (resumable?.status == TournamentStatus.ACTIVE) {
            Text(stringResource(R.string.tournament_in_progress, resumable.roundIndex + 1, Tournament.ROUNDS))
            if (resumable.drawReplayUsed) Text(stringResource(R.string.tournament_draw_replay))
            Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tournament_resume)) }
            OutlinedButton(onClick = { confirmAbandon = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tournament_abandon)) }
        } else if (resumable != null) {
            Text(stringResource(if (resumable.status == TournamentStatus.WON) R.string.tournament_won else R.string.tournament_lost,
                if (resumable.status == TournamentStatus.WON) Tournament.ROUNDS else resumable.roundIndex))
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tournament_finish)) }
        } else if (profile.savedTournament != null) {
            Text(stringResource(R.string.tournament_saved_unavailable), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { confirmAbandon = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tournament_abandon)) }
        }

        Text(stringResource(R.string.tournament_choose_rivals), style = MaterialTheme.typography.titleMedium)
        if (available.size < Tournament.ROUNDS) {
            Text(stringResource(R.string.tournament_locked))
        } else if (resumable == null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                available.forEach { rival ->
                    FilterChip(
                        selected = rival.id in selected,
                        onClick = {
                            selected = if (rival.id in selected) selected - rival.id
                            else if (selected.size < Tournament.ROUNDS) selected + rival.id else selected
                        },
                        label = { Text(rivalName(rival.id)) },
                    )
                }
            }
            Text(stringResource(R.string.tournament_selected_count, selected.size, Tournament.ROUNDS))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Difficulty.entries.forEach { option ->
                    FilterChip(difficulty == option, { difficulty = option }, label = { Text(stringResource(option.labelResource())) })
                }
            }
            Button(
                onClick = { onStart(RivalGallery.roster.filter { it.id in selected }.map { it.id }, difficulty) },
                enabled = selected.size == Tournament.ROUNDS && profile.savedTournament == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.tournament_start)) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmAbandon) AlertDialog(
        onDismissRequest = { confirmAbandon = false },
        title = { Text(stringResource(R.string.tournament_abandon)) },
        text = { Text(stringResource(R.string.tournament_abandon_note)) },
        confirmButton = { TextButton(onClick = { confirmAbandon = false; onAbandon() }) { Text(stringResource(R.string.action_exit)) } },
        dismissButton = { TextButton(onClick = { confirmAbandon = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
