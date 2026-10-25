package pt.tiago.neonline.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import pt.tiago.neonline.data.CampaignMap
import pt.tiago.neonline.data.CampaignMapLevel
import pt.tiago.neonline.data.SavedMatch
import pt.tiago.neonline.data.CampaignRoad
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.data.CatalogLevel
import pt.tiago.neonline.data.Profile

fun Difficulty.labelResource(): Int = when (this) {
    Difficulty.EASY -> R.string.difficulty_easy
    Difficulty.MEDIUM -> R.string.difficulty_medium
    Difficulty.HARD -> R.string.difficulty_hard
}

/** Shows only packaged levels; stars and unlocks are independent for each difficulty. */
@Composable
fun CampaignScreen(
    levels: List<CatalogLevel>,
    profile: Profile,
    metadata: List<CampaignMapLevel>,
    resumable: SavedMatch?,
    reducedMotion: Boolean,
    initialMissingStarsOnly: Boolean = false,
    initialStop: Int? = null,
    initialDifficulty: Difficulty? = null,
    onBack: () -> Unit,
    onDifficulty: (Difficulty) -> Unit,
    onPlay: (Int, Difficulty) -> Unit,
) {
    var difficulty by remember { mutableStateOf(initialDifficulty ?: profile.settings.lastDifficulty) }
    var missingStarsOnly by remember { mutableStateOf(initialMissingStarsOnly) }
    var stop by remember { mutableStateOf(initialStop?.takeIf { CampaignRoad.canOpen(profile, levels, difficulty, it) }) }
    LaunchedEffect(difficulty, profile, levels) {
        if (stop?.let { !CampaignRoad.canOpen(profile, levels, difficulty, it) } == true) stop = null
    }
    val stopLevels = remember(levels, stop) { stop?.let { CampaignRoad.levelsForStop(levels, it) } ?: levels }
    val nodes = remember(profile,metadata,difficulty,stop,missingStarsOnly,resumable) {
        CampaignMap.nodes(profile,metadata,difficulty,stop,missingStarsOnly,resumable)
    }
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    LaunchedEffect(difficulty,stop,missingStarsOnly) {
        val focus=nodes.indexOfFirst { it.current }
        list.scrollToItem(if(focus>0 && !missingStarsOnly)focus+1 else 0)
    }
    BackHandler(onBack = onBack)
    // The filters sit at the top of the list; the shortcut only appears once they are scrolled away.
    val filtersHidden by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal=16.dp)) {
        ScreenHeader(stringResource(R.string.map_heading,stringResource(R.string.campaign_title),stringResource(difficulty.labelResource())),onBack) {
            if(filtersHidden) TextButton(onClick={ scope.launch { list.animateScrollToItem(0) } }) { Text(stringResource(R.string.map_filters)) }
        }
        LazyColumn(state=list,modifier=Modifier.weight(1f)) {
        item(key="map-filters") {
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(stringResource(stop?.let { stopNameResource(it) } ?: R.string.road_all_stops), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.campaign_available, stopLevels.size), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Difficulty.entries.forEach { option ->
                FilterChip(
                    selected = difficulty == option,
                    onClick = { difficulty = option; onDifficulty(option) },
                    label = { Text(stringResource(option.labelResource())) },
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(stop == null, { stop = null }, label = { Text(stringResource(R.string.road_all_stops)) })
            (1..12).filter { CampaignRoad.levelsForStop(levels, it).isNotEmpty() }.forEach { number ->
                FilterChip(stop == number, { stop = number }, enabled = CampaignRoad.canOpen(profile, levels, difficulty, number),
                    label = { Text(stringResource(stopNameResource(number))) })
            }
        }
        FilterChip(selected = missingStarsOnly, onClick = { missingStarsOnly = !missingStarsOnly },
            label = { Text(stringResource(R.string.campaign_missing_stars)) })
        if (missingStarsOnly) Text(stringResource(R.string.campaign_missing_note), style = MaterialTheme.typography.bodySmall)
        if (nodes.isEmpty()) Text(stringResource(if(metadata.isEmpty())R.string.content_unavailable else if(missingStarsOnly)R.string.campaign_stars_done else R.string.content_unavailable),modifier=Modifier.padding(vertical=16.dp))
        }
        }
        itemsIndexed(nodes,key={_,node->node.level.id}) { index,node ->
            CampaignMapNodeCard(node,index,reducedMotion,onPlay={ onPlay(node.level.ordinal,difficulty) })
        }
        }
    }
}
