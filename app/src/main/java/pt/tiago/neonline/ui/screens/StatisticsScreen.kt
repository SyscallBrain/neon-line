package pt.tiago.neonline.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.data.*
import java.text.NumberFormat

@Composable
fun StatisticsScreen(profile: Profile, levels: List<CatalogLevel>, onBack: () -> Unit, onImprove: (Difficulty) -> Unit) {
    BackHandler(onBack = onBack)
    var difficulty by remember { mutableStateOf(profile.settings.lastDifficulty) }
    val summary = remember(profile, levels, difficulty) { CampaignStatistics.summarize(profile, levels, difficulty) }
    val canImprove = remember(profile, levels, difficulty) { CampaignStatistics.levelsToImprove(profile, levels, difficulty).isNotEmpty() }
    val numberFormat = NumberFormat.getIntegerInstance()
    val rateFormat = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        ScreenHeader(stringResource(R.string.stats_title), onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Difficulty.entries.forEach { choice ->
                    FilterChip(selected = difficulty == choice, onClick = { difficulty = choice }, label = { Text(stringResource(choice.labelResource())) })
                }
            }
            StatCard(R.string.stats_progress) {
                Text(stringResource(R.string.stats_completed, summary.completedLevels, summary.availableLevels))
                Text(stringResource(R.string.stats_stars, summary.stars, summary.maxStars), style = MaterialTheme.typography.headlineSmall)
                LinearProgressIndicator(progress = { summary.starProgress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.stats_perfect, summary.threeStarLevels))
                Button(onClick = { onImprove(difficulty) }, enabled = canImprove, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stats_improve))
                }
            }
            StatCard(R.string.stats_results) {
                StatRow(R.string.stats_wins, numberFormat.format(summary.stats.wins))
                StatRow(R.string.stats_losses, numberFormat.format(summary.stats.losses))
                StatRow(R.string.stats_draws, numberFormat.format(summary.stats.draws))
                StatRow(R.string.stats_win_rate, summary.winRate?.let { rateFormat.format(it / 100.0) } ?: stringResource(R.string.stats_no_results))
                Text(stringResource(R.string.stats_results_note), style = MaterialTheme.typography.bodySmall)
            }
            StatCard(R.string.stats_play) {
                StatRow(R.string.stats_moves, numberFormat.format(summary.stats.moves))
                StatRow(R.string.stats_hints, numberFormat.format(summary.stats.hintsUsed))
                val seconds = summary.stats.timeMs / 1000L
                StatRow(R.string.stats_time, stringResource(R.string.stats_duration,
                    numberFormat.format(seconds / 3600L), numberFormat.format(seconds / 60L % 60L), numberFormat.format(seconds % 60L)))
                Text(stringResource(R.string.stats_time_note), style = MaterialTheme.typography.bodySmall)
                if (summary.hasLegacyGaps) Text(stringResource(R.string.stats_legacy_note), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StatCard(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatRow(label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
