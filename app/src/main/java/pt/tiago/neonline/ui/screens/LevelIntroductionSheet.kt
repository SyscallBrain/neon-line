package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.LevelKind
import pt.tiago.neonline.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelIntroductionSheet(brief: LevelBriefing,onDismiss:()->Unit,onPlay:()->Unit) {
    val bombName=stringResource(R.string.piece_bomb)
    val wildName=stringResource(R.string.piece_wild)
    val lockName=stringResource(R.string.piece_lock)
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=24.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.intro_level,brief.ordinal,stringResource(brief.difficulty.labelResource())),style=MaterialTheme.typography.labelLarge)
            Text(if(LocalConfiguration.current.locales[0].language=="pt")brief.titlePt else brief.titleEn,style=MaterialTheme.typography.headlineSmall)
            Text(when(brief.objective.kind) {
                LevelKind.PUZZLE -> (brief.objective.winInMoves ?: 0).let { pluralStringResource(R.plurals.objective_win_in,it,it) }
                LevelKind.DEFENSE -> (brief.objective.surviveAiMoves ?: 0).let { pluralStringResource(R.plurals.intro_defense,it,it) }
                LevelKind.SHAPE -> stringResource(when(brief.objective.shapeRule) {
                    ShapeRule.DIAGONAL_ONLY -> R.string.intro_diagonal
                    ShapeRule.HORIZONTAL_ONLY -> R.string.intro_horizontal
                    ShapeRule.VERTICAL_ONLY -> R.string.intro_vertical
                    ShapeRule.THROUGH_TARGET -> R.string.intro_target
                    null -> R.string.intro_shape
                })
                LevelKind.BOSS -> stringResource(R.string.objective_boss,rivalName(brief.rivalId))
                LevelKind.DUEL -> stringResource(R.string.objective_duel,rivalName(brief.rivalId))
            },style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.intro_rival,rivalName(brief.rivalId)))
            brief.rivalStop?.let { Text(stringResource(styleResources[it-1]),style=MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.free_board_short,brief.width,brief.height,brief.connect))
            Text(stringResource(R.string.intro_starter,stringResource(if(brief.starter==Player.P1)R.string.boss_starter_you else R.string.boss_starter_rival)))
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(brief.popOut) Text(stringResource(R.string.free_pop_short))
                brief.rotateEvery?.let { Text(pluralStringResource(R.plurals.intro_rotation,it,it)) }
                brief.fadeAfter?.let { Text(pluralStringResource(R.plurals.free_fade_short,it,it)) }
                brief.clockSeconds?.let { Text(stringResource(R.string.free_clock_short,it)) }
                if(brief.blockedCells>0) Text(stringResource(R.string.intro_blocked,brief.blockedCells))
                for(player in Player.entries) {
                    val pieces=brief.inventory[player].orEmpty().filter { it.key!=PieceKind.NORMAL && it.value>0 }
                    if(pieces.isNotEmpty()) Text(stringResource(R.string.intro_inventory,
                        stringResource(if(player==Player.P1)R.string.boss_starter_you else R.string.boss_starter_rival),
                        PieceKind.entries.filter { it in pieces }.joinToString(", ") { kind ->
                            "${pieces.getValue(kind)} ${when(kind) { PieceKind.BOMB -> bombName; PieceKind.WILD -> wildName; PieceKind.LOCK -> lockName; else -> "" }}"
                        }))
                }
            }
            Text(stringResource(R.string.intro_resources,brief.hints,brief.undos),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.intro_stars_title),style=MaterialTheme.typography.titleSmall)
            Text(when(brief.stars) {
                BriefingStars.HINTS -> stringResource(R.string.intro_stars_hints)
                BriefingStars.DEFENSE -> stringResource(R.string.intro_stars_defense)
                BriefingStars.BOSS_WINNING_MOVES -> stringResource(R.string.intro_stars_boss,brief.objective.starsThree ?: 0,brief.objective.starsTwo ?: 0)
                BriefingStars.MOVES -> stringResource(R.string.intro_stars_moves,brief.objective.starsThree ?: 0,brief.objective.starsTwo ?: 0)
            },style=MaterialTheme.typography.bodySmall)
            Button(onClick=onPlay,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.game_play)) }
            TextButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
