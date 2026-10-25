package pt.tiago.neonline.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.platform.LocalConfiguration
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.data.*

@Composable
fun DailyScreen(profile: Profile, available: (String, Difficulty) -> Boolean, resumable: SavedDaily?,
                onPlay: (String, Difficulty) -> Unit, onResume: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var today by remember { mutableStateOf(LocalDate.now().toString()) }
    var difficulty by remember { mutableStateOf(profile.settings.lastDifficulty) }
    var replace by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now().toString(); delay(30_000) } }
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalConfiguration.current.locales[0])
    val history = profile.dailyHistory.filter { it.difficulty == difficulty }.sortedByDescending { it.date }
    val current = history.firstOrNull { it.date == today }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        ScreenHeader(stringResource(R.string.daily_title), onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Difficulty.entries.forEach { choice -> FilterChip(difficulty == choice, { difficulty = choice }, label = { Text(stringResource(choice.labelResource())) }) }
                }
                Text(formatter.format(LocalDate.parse(today)), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.daily_note), modifier = Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.daily_today_score, current?.stars ?: 0, current?.attempts ?: 0))
                Button(onClick = {
                    if (profile.savedDaily != null && (profile.savedDaily.date != today || profile.savedDaily.match.difficulty != difficulty)) replace = true
                    else onPlay(today, difficulty)
                }, enabled = available(today, difficulty), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.daily_play)) }
                if (!available(today, difficulty)) Text(stringResource(R.string.daily_unavailable))
                resumable?.let { saved -> OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.daily_resume, formatter.format(LocalDate.parse(saved.date)), stringResource(saved.match.difficulty.labelResource())))
                } }
                if (profile.savedDaily != null && resumable == null) Text(stringResource(R.string.daily_saved_unavailable))
                Text(stringResource(R.string.daily_history), modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                if (history.isEmpty()) Text(stringResource(R.string.daily_empty))
            }
            items(history, key = { "${it.date}:${it.difficulty}" }) { record ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(formatter.format(LocalDate.parse(record.date)), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.daily_history_record, record.stars, pluralStringResource(R.plurals.daily_attempts, record.attempts, record.attempts), pluralStringResource(R.plurals.daily_wins, record.wins, record.wins)))
                } }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (replace) AlertDialog(onDismissRequest = { replace = false }, title = { Text(stringResource(R.string.daily_replace_title)) },
        text = { Text(stringResource(R.string.daily_replace_note)) },
        confirmButton = { TextButton(onClick = { replace = false; onPlay(today, difficulty) }) { Text(stringResource(R.string.daily_play)) } },
        dismissButton = { TextButton(onClick = { replace = false }) { Text(stringResource(R.string.action_cancel)) } })
}
