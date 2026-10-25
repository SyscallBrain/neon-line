package pt.tiago.neonline.leveltool

import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelKind
import pt.tiago.neonline.core.random.SplitMix64
import java.io.File
import pt.tiago.neonline.core.content.LevelFileDto
import pt.tiago.neonline.core.content.VariantDto
import pt.tiago.neonline.core.content.RulesDto
import pt.tiago.neonline.core.content.AiDto
import pt.tiago.neonline.core.content.KindDto
import pt.tiago.neonline.core.content.StarsDto
import pt.tiago.neonline.core.content.MetricsDto
import pt.tiago.neonline.core.content.PieceDto
import pt.tiago.neonline.core.content.TutorialDto
import pt.tiago.neonline.core.content.TutorialStepDto
import pt.tiago.neonline.core.content.CertificateDto
import pt.tiago.neonline.core.content.CatalogDto
import pt.tiago.neonline.core.content.CatalogStopDto
import pt.tiago.neonline.core.content.CatalogEntryDto
import pt.tiago.neonline.core.content.levelJson

/**
 * Candidate generation for the content pipeline. Stop 1 is hand authored in
 * `content/templates/stop-01-blueprints.json`; stops 2-12 come from the families in
 * `content/templates/families.json`. Both paths end in the same candidate
 * shape: one file per level with the three variants, ready for `solve` and `validate`.
 */
object Generator {

    private val json = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }

    fun generate(root: File, stop: Int, from: Int, to: Int): List<LevelFileDto> {
        val blueprints = readBlueprints(root, stop)
        require(blueprints.isNotEmpty()) { "no blueprints for stop $stop" }
        val written = mutableListOf<LevelFileDto>()
        val outDir = File(root, "candidates/stop-%02d".format(stop))
        var ordinal = from
        var blueprintIndex = from - (stop - 1) * 50 - 1
        while (ordinal <= to) {
            val blueprint = blueprints[blueprintIndex % blueprints.size]
            val level = blueprint.withOrdinal(ordinal, stop)
            outDir.mkdirs()
            File(outDir, "${level.id}.json").writeText(json.encodeToString(LevelFileDto.serializer(), level) + "\n")
            written += level
            blueprintIndex++
            ordinal++
        }
        return written
    }

    /** A deterministic 16 hex digit seed derived from the level id and its position. */
    internal fun seedFor(id: String, ordinal: Int, stop: Int): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$id:$ordinal:$stop".toByteArray())
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (digest[i].toLong() and 0xFF)
        return "%016x".format(value)
    }

    private fun readBlueprints(root: File, stop: Int): List<Blueprint> {        val file = if (stop == 1) {
            File(root, "templates/stop-01-blueprints.json")
        } else {
            File(root, "templates/families.json")
        }
        if (!file.isFile) return emptyList()
        return kotlinx.serialization.json.Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Blueprint.serializer()),
            file.readText(),
        )
    }

    /** A family template: everything except the ordinal-dependent bits. */
    @kotlinx.serialization.Serializable
    data class Blueprint(
        val familyId: String,
        val kind: String,
        val winInMoves: Int? = null,
        val surviveAiMoves: Int? = null,
        val shapeRule: String? = null,
        val width: Int = 7,
        val height: Int = 6,
        val connect: Int = 4,
        val popOut: Boolean = false,
        val rotateEvery: Int? = null,
        val fadeAfter: Int? = null,
        val moveClockSec: Int? = null,
        val firstToMove: String = "P1",
        val rival: String,
        val tags: List<String> = emptyList(),
        val titlePt: String,
        val titleEn: String,
        val opening: List<String> = emptyList(),
        val revision: Int = 1,
        val openingsByDifficulty: Map<String, List<String>> = emptyMap(),
        val seedByDifficulty: Map<String, String> = emptyMap(),
        val starsByDifficulty: Map<String, StarsDto> = emptyMap(),
        val metricsByDifficulty: Map<String, MetricsDto> = emptyMap(),
        val aiByDifficulty: Map<String, AiDto> = emptyMap(),
        val winInMovesByDifficulty: Map<String, Int> = emptyMap(),
        val bossRoundsByDifficulty: Map<String, List<pt.tiago.neonline.core.content.BossRoundDto>> = emptyMap(),
    ) {
        fun withOrdinal(ordinal: Int, stop: Int): LevelFileDto {
            val id = "level-%04d".format(ordinal)
            val isBoss = ordinal % 50 == 0
            val kindDto = KindDto(
                type = if (isBoss) "boss" else kind,
                winInMoves = winInMoves,
                surviveAiMoves = surviveAiMoves,
                shapeRule = shapeRule,
                bestOf = if (isBoss) 3 else null,
            )
            val variants = Difficulty.entries.associate { difficulty ->
                val key = difficulty.name.lowercase()
                val seed = seedByDifficulty[key] ?: seedFor(id, ordinal, stop)
                val profile = when (difficulty) {
                    Difficulty.EASY -> AiProfile(maxDepth = 2, nodeBudget = 2_000, attack = 3, defense = 4, center = 2, trap = 1, blunderRate = 220, blunderMayMiss = true)
                    Difficulty.MEDIUM -> AiProfile(maxDepth = 3, nodeBudget = 8_000, attack = 4, defense = 5, center = 3, trap = 3, blunderRate = 40)
                    Difficulty.HARD -> AiProfile(maxDepth = 4, nodeBudget = 20_000, attack = 6, defense = 5, center = 3, trap = 5, blunderRate = 0)
                }
                key to VariantDto(
                    seedHex = seed,
                    bossRounds = bossRoundsByDifficulty[key].orEmpty(),
                    kind = kindDto.copy(winInMoves = winInMovesByDifficulty[key] ?: winInMoves),
                    width = width,
                    height = height,
                    connect = connect,
                    mask = List(height) { "X".repeat(width) },
                    pieces = (openingsByDifficulty[key] ?: opening).mapIndexedNotNull { index, row ->
                        row.mapIndexedNotNull { col, ch ->
                            if (ch == '.') null else PieceDto(
                                r = index,
                                c = col,
                                owner = if (ch == '2') "P2" else "P1",
                            )
                        }
                    }.flatten(),
                    rules = RulesDto(popOut = popOut, rotateEvery = rotateEvery, fadeAfter = fadeAfter, moveClockSec = moveClockSec),
                    firstToMove = firstToMove,
                    rival = rival,
                    ai = aiByDifficulty[key] ?: AiDto(
                        maxDepth = profile.maxDepth,
                        nodeBudget = profile.nodeBudget,
                        blunderRate = profile.blunderRate,
                        attack = profile.attack,
                        defense = profile.defense,
                        center = profile.center,
                        trap = profile.trap,
                        mirror = tags.contains("mirror"),
                        blunderMayMiss = difficulty == Difficulty.EASY,
                    ),
                    stars = starsByDifficulty[key] ?: StarsDto(two = 14 + ordinal % 3, three = 10 + ordinal % 3),
                    hints = when (difficulty) {
                        Difficulty.EASY -> 3
                        Difficulty.MEDIUM -> 2
                        Difficulty.HARD -> 1
                    },
                    expectedMetrics = metricsByDifficulty[key] ?: MetricsDto(humanWinRate = 0.9, solverMoves = 8),
                )
            }
            return LevelFileDto(
                engineVersion = pt.tiago.neonline.core.rules.GameEngine.ENGINE_VERSION,
                id = id,
                ordinal = ordinal,
                stopId = "stop-%02d".format(stop),
                titlePt = titlePt,
                titleEn = titleEn,
                familyId = familyId,
                learningTags = tags,
                revision = revision,
                isDoubleNeon = ordinal % 10 == 0 && ordinal % 50 != 0,
                tutorial = if (ordinal <= 10) {
                    TutorialDto(
                        steps = listOf(
                            TutorialStepDto(
                                textKey = "tut_%02d_step_1".format(ordinal),
                                highlightCols = listOf(3),
                                forcedCol = 3,
                            ),
                        ),
                    )
                } else {
                    null
                },
                variants = variants,
            )
        }
    }
}

/** Promotes solved candidates into the published catalog. */
object Certifier {

    fun certify(root: File, stop: Int): List<LevelFileDto> {
        val candidateDir = File(root, "candidates/stop-%02d".format(stop))
        require(candidateDir.isDirectory) { "no candidates for stop $stop" }
        val levelsDir = File(root, "levels/stop-%02d".format(stop))
        val promoted = mutableListOf<LevelFileDto>()
        val json = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }
        for (file in candidateDir.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.name }) {
            val level = Certificates.decodeLevel(file.readText())
            val errors = Validator.validateLevel(level)
            if (errors.isNotEmpty()) {
                println("certify: ${level.id} rejected by validation (${errors.first()})")
                continue
            }
            var ok = true
            for (difficulty in Difficulty.entries) {
                val certificate = Certificates.solve(level, difficulty)
                if (certificate == null) {
                    println("certify: ${level.id}/${difficulty.name.lowercase()} has no three star solution")
                    ok = false
                    break
                }
                val out = File(root, "certificates/${level.id}/${difficulty.name.lowercase()}.json")
                out.parentFile.mkdirs()
                out.writeText(json.encodeToString(CertificateDto.serializer(), certificate) + "\n")
            }
            if (!ok) continue
            promoted += level
        }
        levelsDir.deleteRecursively()
        levelsDir.mkdirs()
        for (level in promoted.sortedBy { it.ordinal }) {
            val target = File(levelsDir, "${level.id}.json")
            target.writeText(json.encodeToString(LevelFileDto.serializer(), level) + "\n")
        }
        rebuildCatalog(root, stop, promoted)
        return promoted
    }

    /** Rebuilds content/catalog.json from the published levels. */
    fun rebuildCatalog(root: File, stop: Int, promoted: List<LevelFileDto>) {
        val existing = mutableListOf<LevelFileDto>()
        val levelsDir = File(root, "levels")
        if (levelsDir.isDirectory) {
            existing += levelsDir.walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .map { Certificates.decodeLevel(it.readText()) }
        }
        val all = (existing + promoted).distinctBy { it.id }.sortedBy { it.ordinal }
        val stops = (1..12).map { number ->
            val inStop = all.filter { it.stopId == "stop-%02d".format(number) }
            CatalogStopDto(
                id = "stop-%02d".format(number),
                namePt = stopNamePt(number),
                nameEn = stopNameEn(number),
                from = (number - 1) * 50 + 1,
                to = (number - 1) * 50 + (inStop.maxOfOrNull { it.ordinal - (number - 1) * 50 } ?: 0),
            )
        }.filter { stopDto -> all.any { it.stopId == stopDto.id } }
        val catalog = CatalogDto(
            engineVersion = pt.tiago.neonline.core.rules.GameEngine.ENGINE_VERSION,
            stops = stops,
            levels = all.map { level ->
                CatalogEntryDto(
                    path = "levels/${level.stopId}/${level.id}.json",
                    revision = level.revision,
                    sha256 = sha256(levelJson(level)),
                )
            },
        )
        val pretty = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }
        File(root, "catalog.json").writeText(
            pretty.encodeToString(CatalogDto.serializer(), catalog) + "\n",
        )
    }

    private fun stopNamePt(number: Int): String = STOPS_PT.getOrElse(number - 1) { "Paragem $number" }

    private fun stopNameEn(number: Int): String = STOPS_EN.getOrElse(number - 1) { "Stop $number" }

    private val STOPS_PT = listOf(
        "Posto 24 Horas", "Diner da Meia-Noite", "Motel Flamingo", "Bowling Strike",
        "Cinema Drive-In", "Lavandaria Bolha", "Rinque Disco", "Karaoke Lua",
        "Arcada Pixel", "Feira Noturna", "Casino Ás", "Avenida das Luzes",
    )

    private val STOPS_EN = listOf(
        "24-Hour Gas", "Midnight Diner", "Flamingo Motel", "Strike Lanes",
        "Drive-In", "Bubble Laundromat", "Disco Rink", "Moon Karaoke",
        "Pixel Arcade", "Night Fair", "Ace Casino", "Boulevard of Lights",
    )
}
