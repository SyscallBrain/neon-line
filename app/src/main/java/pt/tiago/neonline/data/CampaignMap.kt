package pt.tiago.neonline.data

import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.LevelKind

@kotlinx.serialization.Serializable
data class CampaignMapLevel(val id:String,val ordinal:Int,val difficulty:Difficulty,val titlePt:String,val titleEn:String,
    val kind:LevelKind,val winInMoves:Int?,val surviveAiMoves:Int?,val shapeRule:ShapeRule?,val rival:String,
    val doubleNeon:Boolean,val popOut:Boolean,val rotateEvery:Int?,val fadeAfter:Int?,val clockSeconds:Int?,
    val specials:Boolean,val blockedCells:Int,val connect:Int) {
    companion object {
        fun from(level:LevelFileDto,difficulty:Difficulty):CampaignMapLevel {
            val variant=level.variants.getValue(difficultyKey(difficulty))
            val state=variant.toState(level.id,difficulty)
            return CampaignMapLevel(level.id,level.ordinal,difficulty,level.titlePt,level.titleEn,variant.kindEnum(),
                variant.kind.winInMoves,variant.kind.surviveAiMoves,state.shapeRule,variant.rival,level.isDoubleNeon,
                state.popOut,state.rotateEvery,state.fadeAfter,state.moveClockSec,
                state.inventory.values.any { kinds -> kinds.any { it.key!=PieceKind.NORMAL && it.value>0 } },state.playable.count { !it },state.connect)
        }
    }
}

data class CampaignMapNode(val level:CampaignMapLevel,val unlocked:Boolean,val completed:Boolean,val stars:Int,
    val resuming:Boolean=false,val current:Boolean=false)

/** Verified metadata supplies the nodes. Only a separately validated journal can bypass a progress gate. */
object CampaignMap {
    fun nodes(profile:Profile,levels:List<CampaignMapLevel>,difficulty:Difficulty,stop:Int?,missingStarsOnly:Boolean,resumable:SavedMatch?):List<CampaignMapNode> {
        val progress=profile.progress(difficulty)
        val nodes=levels.filter { it.difficulty==difficulty && it.ordinal in 1..CAMPAIGN_LEVELS &&
            (stop==null || (it.ordinal-1)/50+1==stop) }.distinctBy { it.ordinal }.sortedBy { it.ordinal }.map { level ->
            val resume=resumable?.levelId==level.id && resumable.difficulty==difficulty
            val unlocked=resume || (ProfileReducer.isUnlocked(profile,level.ordinal,difficulty) && ProfileReducer.isStopUnlocked(profile,(level.ordinal-1)/50+1,difficulty))
            val variant=ProfileReducer.variantId(level.ordinal,difficulty)
            CampaignMapNode(level,unlocked,variant in progress.completedVariantIds,progress.bestByVariantId[variant]?.stars ?: 0,resuming=resume)
        }.filter { !missingStarsOnly || (it.unlocked && it.stars<3) }
        val focus=nodes.firstOrNull { it.resuming } ?: nodes.firstOrNull { it.unlocked && !it.completed } ?:
            nodes.firstOrNull { it.unlocked && it.stars<3 } ?: nodes.lastOrNull { it.unlocked }
        return nodes.map { it.copy(current=it.level.id==focus?.level?.id) }
    }
}
