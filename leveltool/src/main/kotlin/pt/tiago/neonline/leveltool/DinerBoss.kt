package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

/** Create a certified Dolores boss base from the final published Diner opening. */
object DinerBoss {
    private val json=Json { prettyPrint=true;encodeDefaults=true }

    fun run(root:File,output:File) {
        val sourcePath=root.canonicalFile.toPath()
        val outputPath=output.canonicalFile.toPath()
        require(!outputPath.startsWith(sourcePath) && !sourcePath.startsWith(outputPath)) { "Use a separate staging directory" }
        require(!output.exists()) { "Use a fresh staging directory" }
        val source=Files.levels(root).first { it.ordinal==90 }
        check(root.copyRecursively(output))
        val variants=source.variants.mapValues { (_,variant) ->
            variant.copy(kind=KindDto("boss",bestOf=3),rival="dolores",bossRounds=emptyList())
        }
        File(output,"certificates/level-0100").deleteRecursively()
        val level=source.copy(id="level-0100",ordinal=100,stopId="stop-02",titlePt="Chefe da Meia-Noite",
            titleEn="Midnight Diner boss",familyId="diner-boss",learningTags=listOf("boss","duel","mirror"),
            revision=1,isDoubleNeon=false,tutorial=null,variants=variants)
        for(difficulty in Difficulty.entries) {
            val key=difficultyKey(difficulty)
            val previous=Certificates.decodeCertificate(File(root,"certificates/${source.id}/$key.json").readText())
            val steps=previous.steps.map { step ->
                Certificates.Step(requireNotNull(Certificates.parseMove(step.move)),requireNotNull(step.rngHex))
            }
            val certificate=Certificates.fromSteps(level,difficulty,steps)
            check(Certificates.verify(level,certificate).isEmpty()) { "Level 100 first-round witness failed for $key" }
            File(output,"certificates/${level.id}/$key.json").apply {
                parentFile.mkdirs()
                writeText(json.encodeToString(CertificateDto.serializer(),certificate)+"\n")
            }
        }
        File(output,"levels/${level.stopId}/${level.id}.json").apply {
            parentFile.mkdirs()
            writeText(levelJson(level)+"\n")
        }
    }
}
