package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

/** Promotes the last Ace Casino duel into the stop-ending Double Neon boss. */
object AceCasinoBoss {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun toBoss(source: LevelFileDto): LevelFileDto {
        require(source.ordinal == 550 && source.id == "level-0550" && source.stopId == "stop-11")
        require(source.isDoubleNeon && source.variants.values.all { it.kind.type == "duel" && it.rival == "ace" })
        require(source.variants.values.all { variant ->
            variant.inventory.values.flatMap { it.keys }.toSet() == setOf("wild", "lock")
        })
        return source.copy(
            titlePt = "A última mão do Ás", titleEn = "Ace's Last Hand",
            familyId = "ace-casino-boss",
            learningTags = listOf("ace-casino", "boss", "duel", "double-neon"),
            revision = source.revision + 1,
            variants = source.variants.mapValues { (_, variant) ->
                variant.copy(kind = KindDto("boss", bestOf = 3), bossRounds = emptyList())
            },
        )
    }

    fun run(root: File, output: File) {
        require(root.canonicalFile.toPath() != output.canonicalFile.toPath() && !output.exists()) { "Use a fresh staging directory" }
        val published = Files.levels(root)
        require(published.map { it.ordinal } == (1..550).toList()) { "Expected campaign content through level 550" }
        val source = published.single { it.ordinal == 550 }
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
            check(Certificates.verify(boss, certificate).isEmpty()) { "Level 550 first-round witness failed for $key" }
            File(output, "certificates/${boss.id}/$key.json").apply {
                parentFile.mkdirs()
                writeText(json.encodeToString(CertificateDto.serializer(), certificate) + "\n")
            }
        }
        File(output, "levels/${boss.stopId}/${boss.id}.json").writeText(levelJson(boss) + "\n")
        Certifier.rebuildCatalog(output, boss.engineVersion, Files.levels(output))
    }
}
