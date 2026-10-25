package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.data.*
import pt.tiago.neonline.ui.art.RoadArt
import pt.tiago.neonline.ui.art.SignArt
import pt.tiago.neonline.ui.theme.*

internal fun stopNameResource(stop: Int): Int = when(stop) {
    1 -> R.string.home_stop_name
    2 -> R.string.stop_name_2
    3 -> R.string.stop_name_3
    4 -> R.string.stop_name_4
    5 -> R.string.stop_name_5
    6 -> R.string.stop_name_6
    7 -> R.string.stop_name_7
    8 -> R.string.stop_name_8
    9 -> R.string.stop_name_9
    10 -> R.string.stop_name_10
    11 -> R.string.stop_name_11
    12 -> R.string.stop_name_12
    else -> R.string.campaign_title
}

@Composable
internal fun CampaignRoadView(stops: List<RoadStop>, difficulty: Difficulty, reducedMotion: Boolean, onStop: (Int) -> Unit) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = stops.indexOfFirst { it.current }.coerceAtLeast(0))
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.road_title), Modifier.padding(horizontal=16.dp), style=MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.road_note,stringResource(difficulty.labelResource())), Modifier.padding(horizontal=16.dp,vertical=4.dp), style=MaterialTheme.typography.bodySmall, color=TextDim)
        Box(Modifier.fillMaxWidth().height((300*scale).dp)) {
            RoadArt(reducedMotion,Modifier.matchParentSize())
            LazyRow(state=list,contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                items(stops,key={it.number}) { stop ->
                    val stateText = stringResource(when {
                        stop.status == RoadStopStatus.UNAVAILABLE -> R.string.road_unavailable
                        stop.status == RoadStopStatus.LOCKED -> R.string.road_locked
                        stop.current -> R.string.road_current
                        else -> R.string.road_open
                    })
                    Card(onClick={ onStop(stop.number) },enabled=stop.canOpen,
                        modifier=Modifier.width((220*scale.coerceAtMost(1.5f)).dp).padding(top=8.dp).semantics { role=Role.Button; stateDescription=stateText },
                        colors=CardDefaults.cardColors(containerColor=if(stop.current) Rail else Shoulder,
                            disabledContainerColor=Shoulder, disabledContentColor=TextDim)) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.home_stop_eyebrow,stop.number),style=MaterialTheme.typography.labelSmall,color=if(stop.current) Pink else TextDim)
                            SignArt(stop.number,stop.litSegments,stop.canOpen,Modifier.fillMaxWidth().height(80.dp))
                            Text(stringResource(stopNameResource(stop.number)),style=MaterialTheme.typography.titleMedium,minLines=2)
                            Text(if(stop.availableLevels>0) stringResource(R.string.road_progress,stop.litSegments.size,stop.availableLevels,stop.stars,stop.maxStars)
                                else stringResource(R.string.road_range,(stop.number-1)*50+1,stop.number*50),style=MaterialTheme.typography.bodySmall)
                            Text(stateText,style=MaterialTheme.typography.labelMedium,color=if(stop.current) Pink else TextDim)
                        }
                    }
                }
            }
        }
    }
}
