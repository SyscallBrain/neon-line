package pt.tiago.neonline.ui.board

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.game.*
import pt.tiago.neonline.ui.screens.labelResource

/** One description on the existing turn live region; clock ticks do not rebuild engine facts. */
@Composable
internal fun boardMoveNoticeText(ui: GameUiState, names: Pair<String, String>): String {
    val notice = remember(ui.state, ui.events, ui.paused, ui.animating, ui.localPlayers, ui.outcome) {
        BoardMoveNotices.snapshot(ui)
    } ?: return ""
    fun name(player: Player) = if (player == Player.P1) names.first else names.second
    val action = when (notice.action) {
        MoveNoticeAction.DROP -> stringResource(R.string.a11y_move_drop, name(notice.player), stringResource(notice.kind.labelResource()), notice.column + 1)
        MoveNoticeAction.POP -> stringResource(R.string.a11y_move_pop, name(notice.player), notice.column + 1)
        MoveNoticeAction.TIMEOUT -> stringResource(R.string.a11y_move_timeout, name(notice.player), notice.column + 1)
    }
    val rotation = if (notice.rotated) stringResource(R.string.a11y_move_rotation) else ""
    val win = notice.win?.let { winner ->
        val directions = winner.directions.map { direction -> stringResource(when (direction) {
            MoveNoticeDirection.HORIZONTAL -> R.string.a11y_line_horizontal
            MoveNoticeDirection.VERTICAL -> R.string.a11y_line_vertical
            MoveNoticeDirection.DIAGONAL -> R.string.a11y_line_diagonal
        }) }.joinToString(", ")
        stringResource(R.string.a11y_move_win, name(winner.player), directions)
    }.orEmpty()
    val threat = if (notice.threatColumns.isEmpty()) "" else stringResource(R.string.a11y_move_threat,
        name(notice.player), notice.threatColumns.joinToString(", ") { (it + 1).toString() })
    return listOf(action, rotation, win, threat).filter { it.isNotBlank() }.joinToString(" ")
}
