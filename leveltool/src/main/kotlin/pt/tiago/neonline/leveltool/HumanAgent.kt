package pt.tiago.neonline.leveltool

import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.ai.AiSearch
import pt.tiago.neonline.core.ai.Evaluator
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.Status
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.GameEngine

/**
 * The simulated player used to calibrate levels. It always takes an
 * immediate win, blocks an immediate loss with a probability that grows with the difficulty, and
 * otherwise plays the move a shallow search likes best with some probability, or a softmax pick
 * from the same scores.
 */
class HumanAgent(private val policy: HumanPolicy, private val rng: SplitMix64) {

    /** Per-difficulty calibration knobs. */
    data class HumanPolicy(
        /** Chance of blocking an immediate loss. */
        val blockChancePerMille: Int,
        /** Chance of playing the move a depth-2 search likes best. */
        val bestChancePerMille: Int,
        /** Softmax temperature; higher means noisier choices. */
        val temperature: Double,
        /** Chance of asking for a hint, in puzzles. */
        val hintChancePerMille: Int = 0,
    ) {
        companion object {
            fun forDifficulty(difficulty: pt.tiago.neonline.core.model.Difficulty) = when (difficulty) {
                pt.tiago.neonline.core.model.Difficulty.EASY ->
                    HumanPolicy(blockChancePerMille = 900, bestChancePerMille = 550, temperature = 1.0)
                pt.tiago.neonline.core.model.Difficulty.MEDIUM ->
                    HumanPolicy(blockChancePerMille = 950, bestChancePerMille = 600, temperature = 1.0)
                pt.tiago.neonline.core.model.Difficulty.HARD ->
                    HumanPolicy(blockChancePerMille = 970, bestChancePerMille = 650, temperature = 1.0)
            }
        }
    }

    /** Plays one game from [start] against the rival and returns how it ended for the player. */
    fun play(start: GameState, rival: AiProfile, maxHumanMoves: Int? = null): HumanGameResult {
        return playObjective(start,rival,maxHumanMoves,null)
    }

    fun playDefense(start: GameState, rival: AiProfile, surviveAiMoves: Int): HumanGameResult {
        require(surviveAiMoves>0)
        return playObjective(start,rival,null,surviveAiMoves)
    }

    private fun playObjective(start: GameState, rival: AiProfile, maxHumanMoves: Int?, surviveAiMoves: Int?): HumanGameResult {
        var state = start
        var humanMoves = 0
        var rivalMoves = 0
        var guard = 0
        while (state.status == Status.InProgress && guard++ < 200) {
            if (state.toMove == Player.P1) {
                val move = choose(state)
                state = GameEngine.apply(state, move).state
                humanMoves++
                if (maxHumanMoves != null && humanMoves >= maxHumanMoves && state.status == Status.InProgress) break
                continue
            }
            val choice = AiSearch(rival).chooseMove(state) ?: break
            state = GameEngine.apply(state.copy(aiStateHex = choice.aiStateHex), choice.move).state
            rivalMoves++
            if(surviveAiMoves!=null && rivalMoves>=surviveAiMoves)break
        }
        return HumanGameResult(
            playerWon = state.status == Status.Won(Player.P1) || (surviveAiMoves!=null && state.status !is Status.Won &&
                (rivalMoves>=surviveAiMoves || state.status is Status.Draw)),
            rivalWon = state.status is Status.Won && (state.status as Status.Won).player == Player.P2,
            draw = state.status is Status.Draw,
            humanMoves = humanMoves,
        )
    }

    private fun choose(state: GameState): Move {
        val moves = GameEngine.validMoves(state)
        if (moves.isEmpty()) error("no legal move for the simulated player")
        val winNow = moves.firstOrNull { winsNow(state, it) }
        if (winNow != null) return winNow
        val blockNow = moves.filter { blocksNow(state, it) }
        if (blockNow.isNotEmpty() && rng.nextInt(1000) < policy.blockChancePerMille) {
            return blockNow[rng.nextInt(blockNow.size)]
        }
        // Score the moves with a shallow search, then play the best or a softmax pick.
        val scored = moves.map { move -> move to depthTwoScore(state, move) }
        val best = scored.maxByOrNull { it.second }!!
        if (rng.nextInt(1000) < policy.bestChancePerMille) return best.first
        return softmax(scored)
    }

    private fun winsNow(state: GameState, move: Move): Boolean {
        val after = GameEngine.apply(state, move).state
        return after.status is Status.Won && (after.status as Status.Won).player == Player.P1
    }

    private fun blocksNow(state: GameState, move: Move): Boolean {
        if (!Evaluator.hasThreat(state, Player.P2)) return false
        val after = GameEngine.apply(state, move).state
        return !Evaluator.hasThreat(after, Player.P2)
    }

    private fun depthTwoScore(state: GameState, move: Move): Int {
        val after = GameEngine.apply(state, move).state
        if (after.status is Status.Won) return if ((after.status as Status.Won).player == Player.P1) 1_000 else -1_000
        if (after.status is Status.Draw) return 0
        // The player to move is the rival; a smaller score (from our point of view) is better.
        var worst = Int.MAX_VALUE
        for (reply in GameEngine.validMoves(after)) {
            val afterReply = GameEngine.apply(after, reply).state
            val score = when (val status = afterReply.status) {
                is Status.Won -> if (status.player == Player.P1) 1_000 else -1_000
                is Status.Draw -> 0
                Status.InProgress -> Evaluator.evaluate(afterReply, AiProfile.EASY) / 8
            }
            if (score < worst) worst = score
        }
        return if (worst == Int.MAX_VALUE) 0 else worst
    }

    private fun softmax(scored: List<Pair<Move, Int>>): Move {
        val max = scored.maxOf { it.second }
        val weights = scored.map { (move, score) ->
            move to Math.exp((score - max) / (8.0 * policy.temperature.coerceAtLeast(0.1)))
        }
        val total = weights.sumOf { it.second }
        var roll = rng.nextInt(1_000_000) / 1_000_000.0 * total
        for ((move, weight) in weights) {
            roll -= weight
            if (roll <= 0) return move
        }
        return scored.last().first
    }

    data class HumanGameResult(
        val playerWon: Boolean,
        val rivalWon: Boolean,
        val draw: Boolean,
        val humanMoves: Int,
    )
}
