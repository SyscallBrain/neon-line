package pt.tiago.neonline.leveltool

import java.io.File
import pt.tiago.neonline.core.ai.*
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.random.SplitMix64

/** Stage distinct boss rounds, proving each is winnable against the exact packaged rival. */
object BossContent {
    fun run(root: File, output: File, onlyOrdinals: Set<Int>? = null) {
        require(root.canonicalFile != output.canonicalFile && !output.exists()) { "Use a fresh staging directory" }
        root.copyRecursively(output)
        for (old in Files.levels(root).filter { level ->
            (onlyOrdinals == null || level.ordinal in onlyOrdinals) && level.variants.values.any { it.kind.type == "boss" }
        }) {
            val witnesses = mutableMapOf<String, List<List<Certificates.Step>>>()
            val variants = old.variants.mapValues { (key, base) ->
                val difficulty = Difficulty.valueOf(key.uppercase())
                val first = Certificates.decodeCertificate(File(root,"certificates/${old.id}/$key.json").readText())
                val rounds = mutableListOf(BossRoundDto(base.seedHex, "P1", base.pieces))
                val steps = mutableListOf(first.steps.map { Certificates.Step(requireNotNull(Certificates.parseMove(it.move)), requireNotNull(it.rngHex)) })
                for (slot in 1..2) {
                    var accepted = false
                    for (attempt in 0 until 3000) {
                        val seed = Generator.seedFor("${old.id}:$key:boss:$slot:$attempt",old.ordinal,1)
                        val rng = SplitMix64(SplitMix64.seedFromHex(seed))
                        var board = base.copy(pieces=emptyList(),firstToMove="P1",bossRounds=emptyList(),seedHex=seed).toState(old.id,difficulty)
                        repeat(10 + 2*rng.nextInt(5)) {
                            if (board.status == Status.InProgress) {
                                val candidates = pt.tiago.neonline.core.rules.GameEngine.validMoves(board).map { pt.tiago.neonline.core.rules.GameEngine.apply(board,it).state }.filter { it.status == Status.InProgress }
                                if (candidates.isNotEmpty()) board = candidates[rng.nextInt(candidates.size)]
                            }
                        }
                        val pieces = board.pieces.mapIndexedNotNull { index,piece -> piece?.let { PieceDto(index/board.width,index%board.width,requireNotNull(it.owner).name) } }
                        if (rounds.any { it.pieces == pieces }) continue
                        val variant = base.copy(seedHex=seed,pieces=pieces,firstToMove=if(slot==1) "P2" else "P1",bossRounds=emptyList())
                        val start = variant.toState(old.id,difficulty)
                        if (Player.entries.any { player ->
                            val probe=start.copy(toMove=player)
                            pt.tiago.neonline.core.rules.GameEngine.validMoves(probe).any { (pt.tiago.neonline.core.rules.GameEngine.apply(probe,it).state.status as? Status.Won)?.player == player }
                        }) continue
                        if (ForcedWin(10_000).canWin(start,2) != false) continue
                        val witness = witness(start,variant) ?: continue
                        val minimum = when(difficulty) { Difficulty.EASY -> 3; Difficulty.MEDIUM -> 4; Difficulty.HARD -> 5 }
                        if (ownMoves(start,witness) < minimum) continue
                        rounds += BossRoundDto(seed,variant.firstToMove,pieces)
                        steps += witness
                        println("boss: ${old.id}/$key round ${slot+1}, attempt $attempt, ${ownMoves(start,witness)} player moves")
                        accepted=true
                        break
                    }
                    check(accepted) { "Cannot certify ${old.id}/$key round ${slot+1}" }
                }
                witnesses[key]=steps
                val counts=steps.mapIndexed { i,path -> ownMoves(base.copy(bossRounds=rounds).forBossRound(i).toState(old.id,difficulty),path) }
                val threshold=counts.sortedDescending().take(2).sum()+2
                base.copy(bossRounds=rounds,stars=StarsDto(threshold+8,threshold),expectedMetrics=base.expectedMetrics.copy(solverMoves=counts.take(2).sum()))
            }
            val bossTitle = when (old.ordinal) {
                100 -> "Midnight Diner boss" to "Chefe da Meia-Noite"
                150 -> "Flamingo Motel boss" to "Chefe do Motel Flamingo"
                200 -> "Strike Master" to "Mestre da Pista"
                250 -> "Final Projection" to "Projeção final"
                300 -> "Centrifuge Master" to "Mestre da Centrífuga"
                350 -> "Skater's Final Spin" to "Última volta do Patins"
                400 -> "Diva's Final Encore" to "Último Encore da Diva"
                450 -> "Pixel's Final Arcade" to "Arcada final do Pixel"
                500 -> "Dawn of the Road" to "Amanhecer na estrada"
                550 -> "Ace's Last Hand" to "A última mão do Ás"
                600 -> "Queen of the Avenue" to "Rainha da Avenida"
                else -> "Desert boss" to "Chefe do deserto"
            }
            val level=old.copy(revision=old.revision+1,
                titleEn=bossTitle.first, titlePt=bossTitle.second,
                variants=variants)
            File(output,"levels/${level.stopId}/${level.id}.json").writeText(levelJson(level)+"\n")
            for ((key,paths) in witnesses) for ((slot,path) in paths.withIndex()) {
                val certificate=Certificates.fromSteps(level,Difficulty.valueOf(key.uppercase()),path,slot)
                check(Certificates.verify(level,certificate).isEmpty())
                val name=if(slot==0) key else "$key-round-${slot+1}"
                File(output,"certificates/${level.id}/$name.json").writeText(kotlinx.serialization.json.Json { prettyPrint=true; encodeDefaults=true }.encodeToString(CertificateDto.serializer(),certificate)+"\n")
            }
        }
        ContentRefresh.run(output, onlyOrdinals)
    }
    private fun ownMoves(start: GameState, steps: List<Certificates.Step>): Int {
        var board=start
        var count=0
        for(step in steps) {
            if(board.toMove==Player.P1) count++
            board=pt.tiago.neonline.core.rules.GameEngine.apply(board.copy(aiStateHex=step.rngHex),step.move).state
        }
        return count
    }
    private fun witness(start: GameState, variant: VariantDto): List<Certificates.Step>? {
        var state=start
        val steps=mutableListOf<Certificates.Step>()
        val human=AiProfile(7,30_000,6,6,4,5)
        repeat(42) {
            if(state.status != Status.InProgress) return@repeat
            val mine=state.toMove==Player.P1
            val choice=AiSearch(if(mine) human else variant.ai.toProfile()).chooseMove(state) ?: return null
            val rng=if(mine) state.aiStateHex else choice.aiStateHex
            steps += Certificates.Step(choice.move,rng)
            state=pt.tiago.neonline.core.rules.GameEngine.apply(state.copy(aiStateHex=rng),choice.move).state
        }
        return steps.takeIf { (state.status as? Status.Won)?.player==Player.P1 }
    }
}
