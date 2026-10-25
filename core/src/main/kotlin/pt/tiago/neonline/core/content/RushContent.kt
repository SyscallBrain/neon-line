package pt.tiago.neonline.core.content

import kotlinx.serialization.Serializable
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher

@Serializable
data class RushEntryDto(val id: String, val seedHex: String, val opening: List<Int>, val winInMoves: Int,
    val winningColumns: List<Int>, val stateSha256: String)
@Serializable
data class RushPoolDto(val version: Int, val engineVersion: Int, val generationSeed: String, val entries: List<RushEntryDto>)

/** Reconstructs a reachable, non-terminal classic position; never trusts supplied board cells. */
@JvmOverloads
fun RushEntryDto.toRushState(engineVersion: Int, checkCancelled: () -> Unit = {}): GameState {
    checkCancelled()
    require(engineVersion == 2 && seedHex.matches(Regex("[0-9a-f]{16}")))
    require(opening.size in 28..36 && opening.size % 2 == 0)
    var state = GameState.initial(id,Difficulty.HARD,engineVersion,seedHex,7,6,4,List(42){true},List(42){null})
    for(col in opening) {
        checkCancelled()
        require(col in 0..6)
        val result = GameEngine.apply(state,Move.Drop(col))
        require(result.events.none { it is GameEvent.MoveRejected } && result.state.status == Status.InProgress)
        state=result.state
    }
    require(state.toMove == Player.P1)
    return state
}

/** Ignores IDs, seeds and move order; reflected boards represent the same puzzle. */
fun rushPositionKey(state: GameState): String {
    fun board(mirror: Boolean) = (0..5).joinToString("/") { row -> (0..6).joinToString("") { col ->
        when(state.pieceAt(row,if(mirror)6-col else col)?.owner) { Player.P1 -> "1"; Player.P2 -> "2"; null -> "." }
    } }
    return minOf(board(false),board(true))
}

/** Cheap package validation. Exact goal/root proofs are separately checked by the leveltool. */
@JvmOverloads
fun validateRushPool(pool: RushPoolDto, checkCancelled: () -> Unit = {}): List<GameState> {
    checkCancelled()
    require(pool.version > 0 && pool.engineVersion == 2 && pool.generationSeed.matches(Regex("[0-9a-f]{16}")))
    require(pool.entries.size in 3..500)
    val seen=HashSet<String>()
    return pool.entries.mapIndexed { index,entry ->
        checkCancelled()
        require(entry.id == "rush-"+(index+1).toString().padStart(4,'0'))
        require(entry.winInMoves in 1..3 && entry.winningColumns.isNotEmpty())
        require(entry.winningColumns == entry.winningColumns.distinct().sorted() && entry.winningColumns.all { it in 0..6 })
        val state=entry.toRushState(pool.engineVersion,checkCancelled)
        require(StateHasher.hash(state) == entry.stateSha256)
        require(entry.winningColumns.all { Move.Drop(it) in GameEngine.validMoves(state) })
        require(seen.add(rushPositionKey(state))) { "Duplicate or mirrored rush position ${entry.id}" }
        state
    }.also { require((1..3).all { stage -> pool.entries.any { it.winInMoves == stage } }) }
}
