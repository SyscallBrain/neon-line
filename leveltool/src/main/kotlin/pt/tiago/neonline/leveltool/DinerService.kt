package pt.tiago.neonline.leveltool

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.GameEngine

@Serializable
data class DinerFamily(val id: String, val kind: String, val minOpeningPairs: Int, val maxOpeningPairs: Int,
    val minimumWitnessMoves: Int, val threatColumns: List<Int> = emptyList(), val puzzleMoves: List<Int> = emptyList())
@Serializable
data class DinerLesson(val ordinal: Int, val familyId: String, val titleEn: String, val titlePt: String)
@Serializable
data class DinerRecipes(val version: Int, val engineVersion: Int, val families: List<DinerFamily>, val lessons: List<DinerLesson>)

/** Family-driven continuation. Published positions are inputs and are never rewritten. */
object DinerService {
    private data class Candidate(val variant: VariantDto, val path: List<Certificates.Step>, val physical: String, val attempt: Int)
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private const val RECIPES = "templates/families.json"
    private const val MARKER = "diner-service-stage.sha256"

    fun load(root: File): DinerRecipes = json.decodeFromString(DinerRecipes.serializer(),File(root,RECIPES).readText()).also(::validate)

    fun validate(recipes: DinerRecipes) {
        require(recipes.version == 1 && recipes.engineVersion == 2)
        require(recipes.families.size == 6 && recipes.families.map { it.id }.distinct().size == 6)
        for (family in recipes.families) {
            require(family.id.matches(Regex("diner-[a-z-]+")))
            require(family.kind in listOf("duel","defense","puzzle"))
            require(family.minOpeningPairs in 8..16 && family.maxOpeningPairs in family.minOpeningPairs..16)
            require(family.minimumWitnessMoves in 2..8)
            require(family.threatColumns.distinct() == family.threatColumns && family.threatColumns.all { it in 0..6 })
            if (family.kind == "defense") require(family.threatColumns.isNotEmpty()) else require(family.threatColumns.isEmpty())
            if (family.kind == "puzzle") require(family.puzzleMoves.size == 3 && family.puzzleMoves.all { it in 2..8 })
            else require(family.puzzleMoves.isEmpty())
        }
        require(recipes.lessons.map { it.ordinal } == (61..99).toList())
        require(recipes.lessons.all { lesson -> lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && recipes.families.any { it.id == lesson.familyId } })
        require(recipes.lessons.zipWithNext().all { (a,b) -> a.familyId != b.familyId })
        require(recipes.lessons.groupingBy { it.familyId }.eachCount().values.all { it <= 7 })
        require(recipes.families.groupingBy { it.kind }.eachCount() == mapOf("duel" to 2,"defense" to 2,"puzzle" to 2))
        val kindByFamily = recipes.families.associate { it.id to it.kind }
        require(recipes.lessons.groupingBy { kindByFamily.getValue(it.familyId) }.eachCount() ==
            mapOf("duel" to 13,"defense" to 13,"puzzle" to 13))
    }

    /** Check ownership and frozen inputs before any generation or staging writes. */
    fun prepare(root: File, output: File) {
        val sourcePath = root.canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(!outputPath.startsWith(sourcePath) && !sourcePath.startsWith(outputPath)) { "Use a separate staging directory" }
        val recipes = load(root)
        val levels = Files.levels(root)
        require(levels.filter { it.ordinal <= 60 }.map { it.ordinal } == (1..60).toList() && levels.all { it.ordinal in 1..600 })
        val frozen = listOf(RECIPES) + levels.filter { it.ordinal <= 80 }.flatMap { level ->
            listOf("levels/${level.stopId}/${level.id}.json") + File(root,"certificates/${level.id}").walkTopDown()
                .filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (path in frozen.sorted()) { digest.update(path.toByteArray());digest.update(0.toByte());digest.update(File(root,path).readBytes()) }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        if (output.exists()) {
            require(output.isDirectory && File(output,MARKER).isFile && File(output,MARKER).readText() == fingerprint) { "Staging belongs to different inputs" }
            require(frozen.all { File(output,it).isFile && File(output,it).readBytes().contentEquals(File(root,it).readBytes()) }) { "Staging changed frozen inputs" }
            for (lesson in recipes.lessons) {
                val id = "level-%04d".format(lesson.ordinal)
                val file = File(output,"levels/stop-02/$id.json")
                if (!file.exists()) continue
                val level = Certificates.decodeLevel(file.readText())
                require(level.id == id && level.ordinal == lesson.ordinal && level.familyId == lesson.familyId && level.stopId == "stop-02")
                require(Validator.validateLevel(level).isEmpty())
                for (key in listOf("easy","medium","hard")) {
                    val certFile = File(output,"certificates/$id/$key.json")
                    require(certFile.isFile) { "Missing staged certificate $id/$key" }
                    val cert = Certificates.decodeCertificate(certFile.readText())
                    require(cert.difficulty == key && Certificates.verify(level,cert).isEmpty()) { "Invalid staged certificate $id/$key" }
                }
            }
        } else {
            check(root.copyRecursively(output))
            for (ordinal in 91..99) {
                val id = "level-%04d".format(ordinal)
                File(output,"levels/stop-02/$id.json").delete()
                File(output,"certificates/$id").deleteRecursively()
            }
            File(output,MARKER).writeText(fingerprint)
        }
    }

    fun run(root: File, output: File) {
        prepare(root,output)
        val recipes = load(root)
        val seen = Files.levels(output).flatMap { it.variants.values }.map { DinerEntry.signature(it.toState("signature",Difficulty.EASY)) }.toMutableSet()
        val template = Files.levels(root).first { it.ordinal == 54 }
        for (lesson in recipes.lessons) {
            val id = "level-%04d".format(lesson.ordinal)
            val file = File(output,"levels/stop-02/$id.json")
            if (file.isFile) { println("diner-service: keeping $id");continue }
            val family = recipes.families.first { it.id == lesson.familyId }
            val variants = linkedMapOf<String,VariantDto>()
            val paths = linkedMapOf<Difficulty,List<Certificates.Step>>()
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val goal = listOf(4,6,9)[difficulty.ordinal]
                val kind = when (family.kind) {
                    "defense" -> KindDto("defense",surviveAiMoves=goal)
                    "puzzle" -> KindDto("puzzle",winInMoves=family.puzzleMoves[difficulty.ordinal])
                    else -> KindDto("duel")
                }
                val base = template.variants.getValue(key).copy(kind=kind,pieces=emptyList(),bossRounds=emptyList())
                val selected = DeterministicSearch.first(250_000) { attempt ->
                    val seed = Generator.seedFor("$id:$key:service-1:$attempt",lesson.ordinal,2)
                    val rng = SplitMix64(SplitMix64.seedFromHex(seed))
                    var state = base.copy(seedHex=seed).toState(id,difficulty)
                    var usable = true
                    repeat(2 * (family.minOpeningPairs + rng.nextInt(family.maxOpeningPairs-family.minOpeningPairs+1))) {
                        if (usable) {
                            val next = GameEngine.validMoves(state).map { GameEngine.apply(state,it).state }.filter { it.status == Status.InProgress }
                            if (next.isEmpty()) usable=false else state=next[rng.nextInt(next.size)]
                        }
                    }
                    if (!usable || DinerEntry.winning(state,Player.P1).isNotEmpty()) return@first null
                    if (family.kind == "defense") {
                        val danger = DinerEntry.winning(state,Player.P2)
                        if (danger.isEmpty() || danger.any { (it as Move.Drop).col !in family.threatColumns }) return@first null
                    }
                    val physical = DinerEntry.signature(state)
                    if (physical in seen) return@first null
                    val pieces = state.pieces.mapIndexedNotNull { index,piece -> piece?.let { PieceDto(index/7,index%7,requireNotNull(it.owner).name) } }
                    val variant = base.copy(seedHex=seed,pieces=pieces)
                    val start = variant.toState(id,difficulty)
                    if (family.kind == "puzzle") {
                        val target = requireNotNull(kind.winInMoves)
                        val proof = ForcedWin(150_000)
                        if (proof.canWin(start,target-1) != false) return@first null
                        val roots = proof.winningMoves(start,target) ?: return@first null
                        if (roots.isEmpty() || (difficulty != Difficulty.EASY && roots.size != 1)) return@first null
                    }
                    val path = DinerEntry.witness(start,variant) ?: return@first null
                    val own = (path.size+1)/2
                    if (own < family.minimumWitnessMoves) return@first null
                    val preview = simulate(start,variant,8)
                    val doubleNeon = lesson.ordinal % 10 == 0 && lesson.ordinal % 50 != 0
                    if (preview !in previewBand(difficulty,doubleNeon)) return@first null
                    val wins = simulate(start,variant,32)
                    if (wins !in selectionBand(difficulty,lesson.ordinal,kind)) return@first null
                    Candidate(variant.copy(stars=StarsDto(own+7,own+2),expectedMetrics=MetricsDto(wins/32.0,own)),path,physical,attempt)
                }
                check(selected != null) { "No candidate for $id/$key" }
                paths[difficulty] = selected.path
                seen += selected.physical
                variants[key] = selected.variant
                println("diner-service: $id/$key family=${family.id} attempt=${selected.attempt} witness=${selected.variant.expectedMetrics.solverMoves} selected=${kotlin.math.round(selected.variant.expectedMetrics.humanWinRate*32).toInt()}/32")
            }
            val level = template.copy(id=id,ordinal=lesson.ordinal,titleEn=lesson.titleEn,titlePt=lesson.titlePt,
                familyId=family.id,isDoubleNeon=lesson.ordinal % 10 == 0 && lesson.ordinal % 50 != 0,tutorial=null,variants=variants,
                learningTags=listOf("mirror",family.kind))
            check(Validator.validateLevel(level).isEmpty())
            for ((difficulty,path) in paths) {
                val cert = Certificates.fromSteps(level,difficulty,path)
                check(Certificates.verify(level,cert).isEmpty())
                File(output,"certificates/$id/${difficultyKey(difficulty)}.json").apply {
                    parentFile.mkdirs();writeText(json.encodeToString(CertificateDto.serializer(),cert)+"\n")
                }
            }
            file.parentFile.mkdirs();file.writeText(levelJson(level)+"\n")
        }
        Certifier.rebuildCatalog(output,2,Files.levels(output))
    }

    private fun simulate(start: GameState, variant: VariantDto, games: Int): Int = (0 until games).count { index ->
        val agent = HumanAgent(HumanAgent.HumanPolicy.forDifficulty(start.difficulty),SplitMix64(20261022L+start.levelId.hashCode()*101L+index))
        if (variant.kind.type == "defense") agent.playDefense(start,variant.ai.toProfile(),requireNotNull(variant.kind.surviveAiMoves)).playerWon
        else agent.play(start,variant.ai.toProfile(),variant.kind.winInMoves).playerWon
    }

    private fun previewBand(difficulty: Difficulty, double: Boolean): IntRange = when(difficulty) {
        Difficulty.EASY -> if(double)4..8 else 6..8
        Difficulty.MEDIUM -> if(double)1..5 else 3..8
        Difficulty.HARD -> if(double)0..4 else 1..6
    }
    internal fun selectionBand(difficulty: Difficulty, ordinal: Int, kind: KindDto): IntRange = when {
        ordinal % 10 == 0 && ordinal % 50 != 0 -> when(difficulty) { Difficulty.EASY->21..24;Difficulty.MEDIUM->10..12;Difficulty.HARD->5..8 }
        ordinal<=63 -> when(difficulty) { Difficulty.EASY->27..31;Difficulty.MEDIUM->20..27;Difficulty.HARD->13..20 }
        // Four-move puzzles show bimodal results with this shallow simulated player.
        // Keep the exact proof, admit a wider authoring sample, and report the independent rate.
        difficulty==Difficulty.HARD && kind.type=="puzzle" && kind.winInMoves==4 -> 11..20
        else -> when(difficulty) { Difficulty.EASY->27..30;Difficulty.MEDIUM->19..24;Difficulty.HARD->11..16 }
    }

    fun audit(root: File, output: File, games: Int) {
        require(games > 0)
        val levels = Files.levels(root).filter { it.ordinal in 61..99 }
        require(levels.map { it.ordinal } == (61..99).toList())
        val samples = levels.flatMap { level -> Difficulty.entries.map { difficulty ->
            val result = Calibrator.calibrate(root,level.id,difficulty,games)
            val wins = kotlin.math.round(result.winRate*games).toInt()
            println("service-audit: ${level.id}/$difficulty $wins/$games inBand=${result.inBand}")
            DifficultyAudit.Sample(level.id,difficultyKey(difficulty),games,wins,result.solverMoves)
        } }
        output.parentFile?.mkdirs()
        output.writeText(json.encodeToString(DifficultyAudit.Report.serializer(),DifficultyAudit.Report(4,samples))+"\n")
    }
}
