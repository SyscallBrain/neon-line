package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

/** Promotes Stop 12's final Double Neon duel into Queen's three-round boss. */
object BoulevardOfLightsFinaleBoss {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun toBoss(source: LevelFileDto): LevelFileDto {
        require(source.ordinal == 600 && source.id == "level-0600" && source.stopId == "stop-12")
        require(source.isDoubleNeon && source.variants.values.all { it.kind.type == "duel" && it.rival == "queen" })
        require(source.variants.values.all { it.inventory.values.flatMap { it.keys }.toSet() == setOf("bomb", "wild") })
        return source.copy(
            titlePt = "Rainha da Avenida", titleEn = "Queen of the Avenue",
            familyId = "boulevard-of-lights-boss",
            learningTags = listOf("boulevard-of-lights", "boss", "duel", "double-neon"),
            revision = source.revision + 1,
            variants = source.variants.mapValues { (_, variant) ->
                variant.copy(kind = KindDto("boss", bestOf = 3), bossRounds = emptyList())
            },
        )
    }

    fun run(root: File, output: File) {
        require(root.canonicalFile.toPath() != output.canonicalFile.toPath() && !output.exists()) { "Use a fresh staging directory" }
        val published = Files.levels(root)
        require(published.map { it.ordinal } == (1..600).toList()) { "Expected campaign content through level 600" }
        val source = published.single { it.ordinal == 600 }
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
            check(Certificates.verify(boss, certificate).isEmpty()) { "Level 600 first-round witness failed for $key" }
            File(output, "certificates/${boss.id}/$key.json").apply {
                parentFile.mkdirs()
                writeText(json.encodeToString(CertificateDto.serializer(), certificate) + "\n")
            }
        }
        File(output, "levels/${boss.stopId}/${boss.id}.json").writeText(levelJson(boss) + "\n")
        Certifier.rebuildCatalog(output, boss.engineVersion, Files.levels(output))
    }
}
