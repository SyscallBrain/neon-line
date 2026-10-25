package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

/** Turn the final certified Drive-In puzzle into the Projectionist boss opening. */
object DriveInBoss {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun run(root: File, output: File) {
        val sourcePath = root.canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(!outputPath.startsWith(sourcePath) && !sourcePath.startsWith(outputPath)) { "Use a separate staging directory" }
        require(!output.exists()) { "Use a fresh staging directory" }
        val published = Files.levels(root)
        require(published.map { it.ordinal } == (1..249).toList()) { "Expected a contiguous published campaign through level 249" }
        val source = published.single { it.ordinal == 249 }
        check(root.copyRecursively(output))
        val variants = source.variants.mapValues { (_, variant) ->
            variant.copy(kind = KindDto("boss", bestOf = 3), rival = "projectionist", bossRounds = emptyList())
        }
        val level = source.copy(
            id = "level-0250", ordinal = 250, stopId = "stop-05",
            titlePt = "Projeção final", titleEn = "Final Projection",
            familyId = "drivein-boss", learningTags = listOf("drivein", "boss", "duel"),
            revision = 1, isDoubleNeon = false, tutorial = null, variants = variants,
        )
        File(output, "certificates/${level.id}").deleteRecursively()
        for (difficulty in Difficulty.entries) {
            val key = difficultyKey(difficulty)
            val previous = Certificates.decodeCertificate(File(root, "certificates/${source.id}/$key.json").readText())
            val steps = previous.steps.map { step ->
                Certificates.Step(requireNotNull(Certificates.parseMove(step.move)), requireNotNull(step.rngHex))
            }
            val certificate = Certificates.fromSteps(level, difficulty, steps)
            check(Certificates.verify(level, certificate).isEmpty()) { "Level 250 first-round witness failed for $key" }
            File(output, "certificates/${level.id}/$key.json").apply {
                parentFile.mkdirs()
                writeText(json.encodeToString(CertificateDto.serializer(), certificate) + "\n")
            }
        }
        File(output, "levels/${level.stopId}/${level.id}.json").writeText(levelJson(level) + "\n")
        Certifier.rebuildCatalog(output, level.engineVersion, Files.levels(output))
    }
}
