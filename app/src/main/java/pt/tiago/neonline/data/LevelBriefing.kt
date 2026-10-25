package pt.tiago.neonline.data

import pt.tiago.neonline.LevelInfo
import pt.tiago.neonline.objective
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*

enum class BriefingStars { MOVES, HINTS, BOSS_WINNING_MOVES, DEFENSE }

/** Presentation snapshot only: constructing this never starts or checkpoints a match. */
data class LevelBriefing(
    val levelId: String, val ordinal: Int, val titlePt: String, val titleEn: String,
    val difficulty: Difficulty, val objective: LevelObjective, val rivalId: String, val rivalName: String, val rivalStop: Int?,
    val width: Int, val height: Int, val connect: Int, val starter: Player,
    val popOut: Boolean, val rotateEvery: Int?, val fadeAfter: Int?, val clockSeconds: Int?, val blockedCells: Int,
    val inventory: Map<Player,Map<PieceKind,Int>>, val hints: Int, val undos: Int, val stars: BriefingStars,
) {
    companion object {
        fun from(info: LevelInfo, state: GameState): LevelBriefing {
            require(info.id == state.levelId && info.width == state.width && info.height == state.height && info.connect == state.connect)
            val rival = RivalGallery.roster.firstOrNull { it.id == info.rival }
            return LevelBriefing(info.id,info.ordinal,info.titlePt,info.titleEn,state.difficulty,
                info.objective().copy(shapeRule=state.shapeRule),info.rival,rival?.name ?: info.rival,rival?.stop,
                state.width,state.height,state.connect,state.toMove,state.popOut,state.rotateEvery,state.fadeAfter,state.moveClockSec,
                state.playable.count { !it },state.inventory.mapValues { it.value.toMap() },info.hints,if(state.difficulty==Difficulty.EASY)3 else 0,
                when(info.kind) {
                    LevelKind.PUZZLE -> BriefingStars.HINTS
                    LevelKind.BOSS -> BriefingStars.BOSS_WINNING_MOVES
                    LevelKind.DEFENSE -> BriefingStars.DEFENSE
                    else -> BriefingStars.MOVES
                })
        }
    }
}
