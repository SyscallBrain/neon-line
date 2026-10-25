package pt.tiago.neonline.ui.board

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.game.RivalThreatCue
import pt.tiago.neonline.ui.screens.labelResource

@Composable
internal fun rivalThreatText(cue: RivalThreatCue): String = when (val move = cue.move) {
    is Move.Drop -> stringResource(R.string.rival_threat_drop, stringResource(move.kind.labelResource()), move.col + 1)
    is Move.Pop -> stringResource(R.string.rival_threat_pop, move.col + 1)
    Move.TimeUp -> ""
}
