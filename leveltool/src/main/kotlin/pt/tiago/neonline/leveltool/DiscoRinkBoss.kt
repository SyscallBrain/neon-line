package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

/** Turn level 350's certified Skater duel into the final boss base for Stop 7. */
object DiscoRinkBoss {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun toBoss(source: LevelFileDto): LevelFileDto {
        require(source.ordinal == 350 && source.id == "level-0350" && source.stopId == "stop-07")
        require(source.variants.values.all { it.kind.type == "duel" && it.rival == "skater" })
        val variants = source.variants.mapValues { (_, variant) ->
            variant.copy(kind = KindDto("boss", bestOf = 3), bossRounds = emptyList())
        }
        return source.copy(
            titlePt = "Última volta do Patins", titleEn = "Skater's Final Spin",
            familyId = "disco-rink-skater-boss",
            learningTags = listOf("disco-rink", "boss", "duel", "clock"),
            revision = 1, isDoubleNeon = false, tutorial = null, variants = variants,
        )
    }

    fun run(root: File, output: File) {
        val rootPath = root.canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(!outputPath.startsWith(rootPath) && !rootPath.startsWith(outputPath)) { "Use a separate staging directory" }
        require(!output.exists()) { "Use a fresh staging directory" }
        val source = Files.levels(root).single { it.ordinal == 350 }
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
            check(Certificates.verify(boss, certificate).isEmpty()) { "Level 350 first-round witness failed for $key" }
            File(output, "certificates/${boss.id}/$key.json").apply {
                parentFile.mkdirs()
                writeText(json.encodeToString(CertificateDto.serializer(), certificate) + "\n")
            }
        }
        File(output, "levels/${boss.stopId}/${boss.id}.json").writeText(levelJson(boss) + "\n")
        Certifier.rebuildCatalog(output, boss.engineVersion, Files.levels(output))
    }
}
