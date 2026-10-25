package pt.tiago.neonline.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.ShapeRule
import pt.tiago.neonline.core.objectives.LevelKind
import pt.tiago.neonline.data.*
import pt.tiago.neonline.ui.theme.*

@Composable
internal fun CampaignMapNodeCard(node:CampaignMapNode,index:Int,reducedMotion:Boolean,onPlay:()->Unit) {
    val level=node.level
    val boss=level.kind==LevelKind.BOSS
    val status=stringResource(when {
        !node.unlocked -> R.string.campaign_locked
        node.resuming -> R.string.map_resume
        node.current -> R.string.map_current
        node.completed -> R.string.map_completed
        else -> R.string.map_available
    })
    val rival=RivalGallery.roster.firstOrNull { it.id==level.rival }
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal=8.dp)) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            val edge=if(index%2==0)size.width*.96f else size.width*.04f
            val path=Path().apply { moveTo(size.width/2,0f); cubicTo(edge,0f,edge,size.height,size.width/2,size.height) }
            drawPath(path,TubeBlue.copy(alpha=.2f),style=Stroke(3.dp.toPx()))
        }
        Card(onClick=onPlay,enabled=node.unlocked,
            modifier=Modifier.fillMaxWidth(.9f).align(if(index%2==0)Alignment.CenterStart else Alignment.CenterEnd).padding(vertical=10.dp)
                .semantics { role=Role.Button; stateDescription=status },
            colors=CardDefaults.cardColors(containerColor=if(node.current || boss) Rail else Shoulder,disabledContainerColor=Shoulder,disabledContentColor=TextDim)) {
            Column(Modifier.padding(if(boss)16.dp else 12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(boss && rival!=null) RivalAvatar(RivalCard(rival,node.unlocked,
                        if(node.completed)setOf(level.difficulty)else emptySet(),RivalRecord(),null),Modifier.size(80.dp))
                    else MapNodeBadge(level.ordinal,node.current,node.unlocked,level.doubleNeon,reducedMotion)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.campaign_level,level.ordinal),style=if(boss)MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                        Text(status,style=MaterialTheme.typography.labelMedium,color=if(node.current) Pink else TextDim)
                        if(level.doubleNeon) Text(stringResource(R.string.map_double_neon),style=MaterialTheme.typography.labelSmall,color=if(node.unlocked)TubeBlue else TextDim)
                    }
                }
                Text(if(LocalConfiguration.current.locales[0].language=="pt")level.titlePt else level.titleEn,style=MaterialTheme.typography.titleSmall)
                Text(mapGoal(level),style=MaterialTheme.typography.bodyMedium)
                if(boss) Text(stringResource(R.string.intro_rival,rivalName(level.rival)),style=MaterialTheme.typography.bodySmall)
                val rules=buildList {
                    if(level.popOut)add(stringResource(R.string.free_pop_short))
                    level.rotateEvery?.let { add(pluralStringResource(R.plurals.intro_rotation,it,it)) }
                    level.fadeAfter?.let { add(pluralStringResource(R.plurals.free_fade_short,it,it)) }
                    level.clockSeconds?.let { add(stringResource(R.string.free_clock_short,it)) }
                    if(level.specials)add(stringResource(R.string.free_specials_short))
                    if(level.blockedCells>0)add(stringResource(R.string.intro_blocked,level.blockedCells))
                    if(level.connect!=4)add(stringResource(R.string.local_connect,level.connect))
                }
                if(rules.isNotEmpty())Text(rules.joinToString(" · "),style=MaterialTheme.typography.bodySmall)
                if(node.unlocked) {
                    val stars=node.stars.coerceIn(0,3)
                    val starsLabel=pluralStringResource(R.plurals.result_stars,stars,stars)
                    Text("★".repeat(stars)+"☆".repeat(3-stars),style=MaterialTheme.typography.titleMedium,color=Amber,
                        modifier=Modifier.clearAndSetSemantics { contentDescription=starsLabel })
                }
            }
        }
    }
}

@Composable
private fun MapNodeBadge(ordinal:Int,current:Boolean,enabled:Boolean,doubleNeon:Boolean,reducedMotion:Boolean) {
    val opacity=if(current && !reducedMotion) {
        val transition=rememberInfiniteTransition(label="current-map-node")
        transition.animateFloat(.35f,.8f,infiniteRepeatable(tween(1300),RepeatMode.Reverse),label="node-pulse")
    } else rememberUpdatedState(.55f)
    Box(Modifier.size(60.dp),contentAlignment=Alignment.Center) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            val color=if(!enabled)Color(0xff514b58) else if(current)Pink else TubeBlue
            val center=Offset(size.width/2,size.height/2)
            if(current)drawCircle(color.copy(alpha=opacity.value*.2f),size.minDimension*.46f,center,style=Stroke(8.dp.toPx()))
            drawCircle(color,size.minDimension*.41f,center,style=Stroke(2.dp.toPx()))
            if(doubleNeon)drawCircle(color,size.minDimension*.33f,center,style=Stroke(1.dp.toPx()))
        }
        // The adjacent labelled text is the accessible level number.
        Text(ordinal.toString(),Modifier.clearAndSetSemantics {},style=MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun mapGoal(level:CampaignMapLevel):String=when(level.kind) {
    LevelKind.DUEL -> stringResource(R.string.map_duel)
    LevelKind.PUZZLE -> (level.winInMoves ?: 0).let { pluralStringResource(R.plurals.objective_win_in,it,it) }
    LevelKind.BOSS -> stringResource(R.string.map_boss)
    LevelKind.DEFENSE -> (level.surviveAiMoves ?: 0).let { pluralStringResource(R.plurals.intro_defense,it,it) }
    LevelKind.SHAPE -> stringResource(when(level.shapeRule) {
        ShapeRule.DIAGONAL_ONLY -> R.string.intro_diagonal
        ShapeRule.HORIZONTAL_ONLY -> R.string.intro_horizontal
        ShapeRule.VERTICAL_ONLY -> R.string.intro_vertical
        ShapeRule.THROUGH_TARGET -> R.string.intro_target
        null -> R.string.intro_shape
    })
}
