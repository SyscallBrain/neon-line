package pt.tiago.neonline.data

import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*

/** Published pack1 uses independent classic positions, never campaign ordinals. */
class RushPack private constructor(val version:Int, private val positions:List<Pair<RushPuzzle,GameState>>) {
    val puzzles:List<RushPuzzle> = positions.map { it.first }
    private val byId=positions.associateBy { it.first.entryId }
    fun stateFor(puzzle:RushPuzzle):GameState? = byId[puzzle.entryId]?.takeIf { it.first==puzzle }?.second
    companion object {
        val RIVAL=AiProfile(maxDepth=6,nodeBudget=12000,attack=6,defense=6,center=4,trap=5)
        @JvmOverloads
        fun from(pool:RushPoolDto,checkCancelled:()->Unit={}):RushPack {
            require(pool.version==1 && pool.entries.size==500)
            val states=validateRushPool(pool,checkCancelled)
            return RushPack(pool.version,pool.entries.zip(states).mapIndexed { index,(entry,state) ->
                checkCancelled()
                RushPuzzle(index+1,Difficulty.HARD,entry.winInMoves,entry.id) to state
            })
        }
    }
}
