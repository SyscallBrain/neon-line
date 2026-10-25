package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.ui.theme.Amber
import pt.tiago.neonline.ui.theme.Asphalt
import pt.tiago.neonline.ui.theme.Dusk
import pt.tiago.neonline.ui.theme.Pink
import pt.tiago.neonline.ui.theme.TextDim

/**
 * Campaign home: continue the current difficulty or choose a packaged level.
 * The road reflects only published variants and keeps Continue available above the signs.
 */
@Composable
fun HomeScreen(
    ordinal: Int,
    difficulty: pt.tiago.neonline.core.model.Difficulty,
    stars: Int,
    availableLevels: Int,
    resuming: Boolean = false,
    savedUnavailable: Boolean = false,
    onPlay: (ordinal: Int, difficulty: pt.tiago.neonline.core.model.Difficulty) -> Unit,
    onCampaign: () -> Unit,
    onSettings: () -> Unit,
    onStatistics: () -> Unit,
    onAchievements: () -> Unit,
    onLocal: () -> Unit,
    onDaily: () -> Unit,
    onRivals: () -> Unit,
    onFreePlay: () -> Unit,
    onRush: () -> Unit,
    onTournament: () -> Unit,
    road: List<pt.tiago.neonline.data.RoadStop>,
    reducedMotion: Boolean,
    onStop: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Asphalt, Dusk))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Logo()
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = TextDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_build, pt.tiago.neonline.BuildConfig.VERSION_NAME, availableLevels),
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = pt.tiago.neonline.ui.theme.Shoulder),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.home_stop_eyebrow, (ordinal - 1) / 50 + 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = pt.tiago.neonline.ui.theme.TubeBlue,
                    )
                    Text(
                        text = stringResource(stopNameResource((ordinal - 1) / 50 + 1)),
                        style = MaterialTheme.typography.titleLarge,
                        color = pt.tiago.neonline.ui.theme.Text,
                    )
                    Text(
                        text = stringResource(R.string.home_level_line, ordinal, stars, stringResource(difficulty.labelResource())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = pt.tiago.neonline.ui.theme.TextDim,
                    )
                    road.firstOrNull { it.number == (ordinal - 1) / 50 + 1 }?.let { stop ->
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { if(stop.maxStars==0) 0f else stop.stars.toFloat()/stop.maxStars },
                            modifier = Modifier.fillMaxWidth().padding(top=8.dp), color = Amber)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onPlay(ordinal, difficulty) },
                        enabled = availableLevels > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = pt.tiago.neonline.ui.theme.Pink),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(if (resuming) R.string.home_resume else R.string.home_continue, ordinal), color = pt.tiago.neonline.ui.theme.OnPink)
                    }
                }
            }
            if (savedUnavailable) {
                Text(
                    stringResource(R.string.saved_unavailable),
                    color = TextDim, modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            androidx.compose.material3.TextButton(onClick = onCampaign) {
                Text(stringResource(R.string.campaign_choose))
            }
            CampaignRoadView(road, difficulty, reducedMotion, onStop)
            Spacer(Modifier.height(8.dp))
            MenuSection(stringResource(R.string.nav_play), listOf(
                stringResource(R.string.free_title) to onFreePlay,
                stringResource(R.string.daily_title) to onDaily,
                stringResource(R.string.rush_title) to onRush,
                stringResource(R.string.tournament_title) to onTournament,
                stringResource(R.string.local_title) to onLocal,
            ))
            Spacer(Modifier.height(16.dp))
            MenuSection(stringResource(R.string.home_section_progress), listOf(
                stringResource(R.string.nav_stats) to onStatistics,
                stringResource(R.string.achievements_title) to onAchievements,
                stringResource(R.string.nav_rivals) to onRivals,
            ))
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Text(stringResource(R.string.nav_settings), modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(24.dp))

        }
    }
}

/** A titled group of equal tiles, two per row, so every mode reads as a peer. */
@Composable
private fun MenuSection(title: String, items: List<Pair<String, () -> Unit>>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = pt.tiago.neonline.ui.theme.TubeBlue,
            modifier = Modifier.semantics { heading() })
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, onClick) -> MenuTile(label, onClick, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MenuTile(label: String, onClick: () -> Unit, modifier: Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        colors = CardDefaults.cardColors(containerColor = pt.tiago.neonline.ui.theme.Shoulder),
        border = androidx.compose.foundation.BorderStroke(1.dp, pt.tiago.neonline.ui.theme.Rail),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Pink, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Logo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.logo_neon),
            style = MaterialTheme.typography.displayLarge.copy(
                shadow = Shadow(color = Pink, blurRadius = 28f),
            ),
            color = Pink,
        )
        Text(
            text = stringResource(R.string.logo_line),
            style = MaterialTheme.typography.displayLarge.copy(
                shadow = Shadow(color = Amber, blurRadius = 28f),
            ),
            color = Amber,
        )
    }
}
