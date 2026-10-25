package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

/** Promote Pixel Arcade's final certified duel into the Stop 9 boss opening. */
object PixelArcadeBoss {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun toBoss(source: LevelFileDto): LevelFileDto {
        require(source.ordinal == 450 && source.id == "level-0450" && source.stopId == "stop-09")
        require(source.variants.values.all { it.kind.type == "duel" && it.rival == "pixel" })
        val variants = source.variants.mapValues { (_, variant) ->
            variant.copy(kind = KindDto("boss", bestOf = 3), bossRounds = emptyList())
        }
        return source.copy(
            titlePt = "Arcada final do Pixel", titleEn = "Pixel's Final Arcade",
            familyId = "pixel-arcade-boss",
            learningTags = listOf("pixel-arcade", "boss", "duel"),
            revision = 1, isDoubleNeon = false, tutorial = null, variants = variants,
        )
    }

    fun run(root: File, output: File) {
        val rootPath = root.canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(!outputPath.startsWith(rootPath) && !rootPath.startsWith(outputPath)) { "Use a separate staging directory" }
        require(!output.exists()) { "Use a fresh staging directory" }
        val published = Files.levels(root)
        require(published.map { it.ordinal } == (1..450).toList()) { "Expected campaign content through level 450" }
        val source = published.single { it.ordinal == 450 }
        val boss = toBoss(source)
        check(root.copyRecursively(output))
        File(output, "certificates/${boss.id}").deleteRecursively()
        for (difficulty in Difficulty.entries) {
            val key = difficultyKey(difficulty)
            val previous = Certificates.decodeCertificate(File(root, "certificates/${source.id}/$key.json").readText())
            val steps = previous.steps.map { step ->
                Certificates.Step(requireNotNull(Certificates.parseMove(step.move)), requireNotNull(step.rngHex))
            }
            val certificate = Certificates.fromSteps(boss, difficulty, steps)
            check(Certificates.verify(boss, certificate).isEmpty()) { "Level 450 first-round witness failed for $key" }
            File(output, "certificates/${boss.id}/$key.json").apply {
                parentFile.mkdirs()
                writeText(json.encodeToString(CertificateDto.serializer(), certificate) + "\n")
            }
        }
        File(output, "levels/${boss.stopId}/${boss.id}.json").writeText(levelJson(boss) + "\n")
        Certifier.rebuildCatalog(output, boss.engineVersion, Files.levels(output))
    }
}
