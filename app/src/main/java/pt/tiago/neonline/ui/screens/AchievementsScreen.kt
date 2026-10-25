package pt.tiago.neonline.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.data.*

fun CampaignGoal.titleResource(): Int = when (this) {
    CampaignGoal.FIRST_WIN -> R.string.achievement_first_win
    CampaignGoal.LEVELS_10 -> R.string.achievement_levels_10
    CampaignGoal.LEVELS_25 -> R.string.achievement_levels_25
    CampaignGoal.LEVELS_50 -> R.string.achievement_levels_50
    CampaignGoal.PERFECT_10 -> R.string.achievement_perfect_10
    CampaignGoal.PERFECT_25 -> R.string.achievement_perfect_25
    CampaignGoal.STARS_100 -> R.string.achievement_stars_100
    CampaignGoal.STOP_1 -> R.string.achievement_stop_1
    CampaignGoal.STOP_2 -> R.string.achievement_stop_2
    CampaignGoal.STOP_3 -> R.string.achievement_stop_3
    CampaignGoal.STOP_4 -> R.string.achievement_stop_4
    CampaignGoal.STOP_5 -> R.string.achievement_stop_5
    CampaignGoal.STOP_6 -> R.string.achievement_stop_6
    CampaignGoal.STOP_7 -> R.string.achievement_stop_7
    CampaignGoal.STOP_8 -> R.string.achievement_stop_8
    CampaignGoal.STOP_9 -> R.string.achievement_stop_9
}

@Composable
fun AchievementsScreen(profile: Profile, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var difficulty by remember { mutableStateOf(profile.settings.lastDifficulty) }
    val goals = remember(profile, difficulty) { CampaignAchievements.progress(profile, difficulty) }
    val rush = remember(profile, difficulty) { RushAchievements.progress(profile, difficulty) }
    val tournament = remember(profile) { TournamentAchievements.progress(profile) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        ScreenHeader(stringResource(R.string.achievements_title), onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Difficulty.entries.forEach { choice -> FilterChip(difficulty == choice, { difficulty = choice }, label = { Text(stringResource(choice.labelResource())) }) }
                }
                Text(stringResource(R.string.achievements_count, goals.count { it.unlocked } + (if (rush.unlocked) 1 else 0) + (if (tournament.unlocked) 1 else 0), goals.size + 2), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.achievements_note), modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            items(goals, key = { it.definition.id }) { goal ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Star, null, tint = if (goal.unlocked) pt.tiago.neonline.ui.theme.Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(goal.definition.goal.titleResource()), style = MaterialTheme.typography.titleMedium)
                        }
                        val description = when (goal.definition.goal) {
                            CampaignGoal.FIRST_WIN, CampaignGoal.LEVELS_10, CampaignGoal.LEVELS_25, CampaignGoal.LEVELS_50 -> pluralStringResource(R.plurals.achievement_goal_levels, goal.target, goal.target)
                            CampaignGoal.PERFECT_10, CampaignGoal.PERFECT_25 -> stringResource(R.string.achievement_goal_perfect, goal.target)
                            CampaignGoal.STARS_100 -> stringResource(R.string.achievement_goal_stars, goal.target)
                            CampaignGoal.STOP_1 -> stringResource(R.string.achievement_goal_stop_1)
                            CampaignGoal.STOP_2 -> stringResource(R.string.achievement_goal_stop_2)
                            CampaignGoal.STOP_3 -> stringResource(R.string.achievement_goal_stop_3)
                            CampaignGoal.STOP_4 -> stringResource(R.string.achievement_goal_stop_4)
                            CampaignGoal.STOP_5 -> stringResource(R.string.achievement_goal_stop_5)
                            CampaignGoal.STOP_6 -> stringResource(R.string.achievement_goal_stop_6)
                            CampaignGoal.STOP_7 -> stringResource(R.string.achievement_goal_stop_7)
                            CampaignGoal.STOP_8 -> stringResource(R.string.achievement_goal_stop_8)
                            CampaignGoal.STOP_9 -> stringResource(R.string.achievement_goal_stop_9)
                        }
                        Text(description)
                        LinearProgressIndicator(progress = { goal.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(if (goal.unlocked) stringResource(R.string.achievement_unlocked) else stringResource(R.string.achievement_progress, goal.current, goal.target), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item(key = rush.id) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Star, null, tint = if (rush.unlocked) pt.tiago.neonline.ui.theme.Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.achievement_rush_25), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(stringResource(R.string.achievement_goal_rush, rush.target))
                        LinearProgressIndicator(progress = { rush.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(if (rush.unlocked) stringResource(R.string.achievement_unlocked) else stringResource(R.string.achievement_progress, rush.current, rush.target), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item(key = TournamentAchievements.ID) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Star, null, tint = if (tournament.unlocked) pt.tiago.neonline.ui.theme.Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.achievement_tournament_win), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(stringResource(R.string.achievement_goal_tournament))
                        LinearProgressIndicator(progress = { tournament.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(if (tournament.unlocked) stringResource(R.string.achievement_unlocked) else stringResource(R.string.achievement_progress, tournament.current, tournament.target), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}
