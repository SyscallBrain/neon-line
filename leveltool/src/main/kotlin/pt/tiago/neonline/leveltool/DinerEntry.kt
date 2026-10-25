package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.ai.*
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.GameEngine

/** Reproducible chapter entry, staged separately from the frozen published chapter. */
object DinerEntry {
    private val json=Json { prettyPrint=true;encodeDefaults=true }
    private val defenses=setOf(51,52,53,56,58)
    private val puzzles=setOf(55,59)
    private val human=AiProfile(6,8_000,6,6,4,5)
    private val titles=listOf("The first watch" to "A primeira vigia", "Keep the counter" to "Defende o balcão",
        "Corner booth" to "Mesa de canto", "Mirror service" to "Serviço ao espelho", "Coffee fork" to "Café com ameaça dupla",
        "Hold the booth" to "Aguenta a mesa", "Midnight reflection" to "Reflexo da meia-noite",
        "Last order" to "Último pedido", "Neon menu" to "Menu de néon", "Double shift" to "Turno duplo")

    fun audit(root:File,output:File,games:Int) {
        require(games>0)
        val samples=Files.levels(root).filter { it.ordinal in 51..60 }.flatMap { level->
            Difficulty.entries.map { difficulty->
                val result=Calibrator.calibrate(root,level.id,difficulty,games)
                val wins=kotlin.math.round(result.winRate*games).toInt()
                println("diner-audit: ${level.id}/$difficulty $wins/$games inBand=${result.inBand}")
                DifficultyAudit.Sample(level.id,difficultyKey(difficulty),games,wins,result.solverMoves)
            }
        }
        require(samples.size==30)
        output.parentFile?.mkdirs()
        output.writeText(json.encodeToString(DifficultyAudit.Report.serializer(),DifficultyAudit.Report(3,samples))+"\n")
    }

    fun run(root: File, output: File) {
        require(!output.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) &&
            !root.canonicalFile.toPath().startsWith(output.canonicalFile.toPath())) { "Use a separate staging directory" }
        val source=Files.levels(root)
        require(source.count { it.ordinal in 1..50 }==50 && source.all { it.ordinal in 1..600 }) { "Expected the first chapter and published campaign levels" }
        if (!output.exists()) {
            check(root.copyRecursively(output))
            // Regenerate a copied chapter entry; never remove anything from the source root.
            for(ordinal in 51..60) {
                val id="level-%04d".format(ordinal)
                File(output,"levels/stop-02/$id.json").delete()
                File(output,"certificates/$id").deleteRecursively()
            }
        }
        val seen=Files.levels(output).flatMap { it.variants.values }.map { signature(it.toState("signature",Difficulty.EASY)) }.toMutableSet()
        val template=Files.levels(root).first { it.ordinal==11 }
        for(ordinal in 51..60) {
            val id="level-%04d".format(ordinal)
            val out=File(output,"levels/stop-02/$id.json")
            if(out.isFile) { println("diner-entry: keeping $id");continue }
            val variants=linkedMapOf<String,VariantDto>()
            val paths=linkedMapOf<Difficulty,List<Certificates.Step>>()
            for(difficulty in Difficulty.entries) {
                val key=difficultyKey(difficulty)
                val defense=ordinal in defenses
                val puzzle=ordinal in puzzles
                val goal=if(ordinal==51)3 else when(difficulty) { Difficulty.EASY->3+(ordinal%2);Difficulty.MEDIUM->5;Difficulty.HARD->8 }
                val target=if(difficulty==Difficulty.EASY)2 else 3
                val ai=when(difficulty) {
                    Difficulty.EASY->AiDto(2,500,100,3,4,2,1,mirror=true,blunderMayMiss=true)
                    Difficulty.MEDIUM->AiDto(3,1500,0,4,5,3,3,mirror=true)
                    Difficulty.HARD->AiDto(4,3000,0,6,6,4,5,mirror=true)
                }
                val kind=if(defense)KindDto("defense",surviveAiMoves=goal) else if(puzzle)KindDto("puzzle",winInMoves=target) else KindDto("duel")
                val base=template.variants.getValue(key).copy(kind=kind,rival="dolores",ai=ai,rules=RulesDto(),
                    pieces=emptyList(),bossRounds=emptyList(),stars=StarsDto(30,26),hints=when(difficulty){Difficulty.EASY->3;Difficulty.MEDIUM->2;Difficulty.HARD->1})
                var found:VariantDto?=null
                for(attempt in 0 until 80_000) {
                    val seed=Generator.seedFor("$id:$key:diner-1:$attempt",ordinal,2)
                    val rng=SplitMix64(SplitMix64.seedFromHex(seed))
                    var state=base.copy(seedHex=seed).toState(id,difficulty)
                    var usable=true
                    repeat(22+2*rng.nextInt(5)) {
                        if(usable) {
                            val next=GameEngine.validMoves(state).map { GameEngine.apply(state,it).state }.filter { it.status==Status.InProgress }
                            if(next.isEmpty()) usable=false else state=next[rng.nextInt(next.size)]
                        }
                    }
                    if(!usable || winning(state,Player.P1).isNotEmpty())continue
                    val danger=winning(state,Player.P2)
                    if(defense && danger.isEmpty())continue
                    if(ordinal==51 && danger!=listOf(Move.Drop(3)))continue
                    val physical=signature(state)
                    if(physical in seen)continue
                    val pieces=state.pieces.mapIndexedNotNull { index,piece->piece?.let { PieceDto(index/7,index%7,requireNotNull(it.owner).name) } }
                    val variant=base.copy(seedHex=seed,pieces=pieces)
                    val start=variant.toState(id,difficulty)
                    if(puzzle) {
                        val proof=ForcedWin(100_000)
                        if(proof.canWin(start,target-1)!=false)continue
                        val roots=proof.winningMoves(start,target) ?: continue
                        if(roots.isEmpty() || (difficulty!=Difficulty.EASY && roots.size!=1))continue
                    }
                    val path=witness(start,variant) ?: continue
                    val own=(path.size+1)/2
                    if(own < if(defense || puzzle)2 else 3)continue
                    if(ordinal==51 && path.first().move!=Move.Drop(3))continue
                    val wins=(0 until 8).count { game ->
                        val agent=HumanAgent(HumanAgent.HumanPolicy.forDifficulty(difficulty),SplitMix64(20261021L+ordinal*101L+game))
                        if(defense)agent.playDefense(start,ai.toProfile(),goal).playerWon
                        else agent.play(start,ai.toProfile(),kind.winInMoves).playerWon
                    }
                    // Small deterministic authoring sample; report it without claiming confidence bands.
                    val accepted=when {
                        ordinal==51 -> when(difficulty){Difficulty.EASY->8..8;Difficulty.MEDIUM->7..8;Difficulty.HARD->6..8}
                        ordinal in 52..53 -> when(difficulty){Difficulty.EASY->7..8;Difficulty.MEDIUM->6..8;Difficulty.HARD->4..8}
                        ordinal==60 -> when(difficulty){Difficulty.EASY->6..6;Difficulty.MEDIUM->3..3;Difficulty.HARD->2..2}
                        else -> when(difficulty){Difficulty.EASY->7..7;Difficulty.MEDIUM->5..6;Difficulty.HARD->3..4}
                    }
                    if(wins !in accepted)continue
                    found=variant.copy(stars=StarsDto(own+7,own+2),expectedMetrics=MetricsDto(wins/8.0,own))
                    paths[difficulty]=path
                    seen+=physical
                    println("diner-entry: $id/$key attempt=$attempt goal=$kind witness=$own sample=$wins/8")
                    break
                }
                check(found!=null) { "No acceptable $id/$key candidate" }
                variants[key]=found
            }
            val (en,pt)=titles[ordinal-51]
            val level=template.copy(id=id,ordinal=ordinal,stopId="stop-02",revision=1,titleEn=en,titlePt=pt,
                familyId="diner-${ordinal-50}",learningTags=listOf("mirror",if(ordinal in defenses)"defense" else if(ordinal in puzzles)"puzzle" else "duel"),
                isDoubleNeon=ordinal==60,tutorial=if(ordinal==51)TutorialDto(listOf(TutorialStepDto("tut_defense_1",listOf(3),3))) else null,variants=variants)
            check(Validator.validateLevel(level).isEmpty()) { "Invalid $id" }
            for((difficulty,path)in paths) {
                val cert=Certificates.fromSteps(level,difficulty,path)
                check(Certificates.verify(level,cert).isEmpty()) { "Invalid witness $id/$difficulty" }
                File(output,"certificates/$id/${difficultyKey(difficulty)}.json").apply { parentFile.mkdirs();writeText(json.encodeToString(CertificateDto.serializer(),cert)+"\n") }
            }
            out.parentFile.mkdirs();out.writeText(levelJson(level)+"\n")
        }
        Certifier.rebuildCatalog(output,2,Files.levels(output))
    }

    internal fun winning(state:GameState,player:Player):List<Move> {
        val probe=state.copy(toMove=player)
        return GameEngine.validMoves(probe).filter { GameEngine.apply(probe,it).state.status==Status.Won(player) }
    }
    internal fun signature(state:GameState):String {
        val rows=(0 until state.height).map { r->(0 until state.width).joinToString("") { c->state.pieceAt(r,c)?.owner?.ordinal?.toString() ?: "." } }
        return minOf(rows.joinToString("/"),rows.joinToString("/"){it.reversed()})
    }
    internal fun witness(start:GameState,variant:VariantDto):List<Certificates.Step>? {
        var state=start
        var rivalMoves=0
        var ownMoves=0
        val steps=mutableListOf<Certificates.Step>()
        repeat(42) {
            if(state.status!=Status.InProgress)return@repeat
            if(variant.kind.type=="defense" && rivalMoves>=requireNotNull(variant.kind.surviveAiMoves))return null
            val mine=state.toMove==Player.P1
            val action=if(mine && variant.kind.type=="puzzle") {
                val move=ForcedWin(100_000).winningMoves(state,requireNotNull(variant.kind.winInMoves)-ownMoves)?.firstOrNull() ?: return null
                AiChoice(move,state.aiStateHex)
            } else {
                AiSearch(if(mine)human else variant.ai.toProfile()).chooseMove(state) ?: return null
            }
            val rng=if(mine)state.aiStateHex else action.aiStateHex
            steps+=Certificates.Step(action.move,rng)
            state=GameEngine.apply(state.copy(aiStateHex=rng),action.move).state
            if(mine)ownMoves++ else rivalMoves++
        }
        return steps.takeIf { state.status==Status.Won(Player.P1) }
    }
}
