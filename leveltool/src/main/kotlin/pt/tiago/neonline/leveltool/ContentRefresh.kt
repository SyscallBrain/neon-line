package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.rules.GameEngine

/** Rebuild hashes for existing witnesses, refusing to publish if a changed rule invalidates one. */
object ContentRefresh {
    fun run(root: File, onlyOrdinals: Set<Int>? = null) {
        val allLevels = Files.levels(root)
        val refreshed = allLevels.filter { onlyOrdinals == null || it.ordinal in onlyOrdinals }.map { old ->
            require(old.engineVersion <= GameEngine.ENGINE_VERSION)
            val level = old.copy(engineVersion = GameEngine.ENGINE_VERSION)
            val certificates = Difficulty.entries.map { difficulty ->
                val oldCert = Certificates.decodeCertificate(File(root,"certificates/${level.id}/${difficultyKey(difficulty)}.json").readText())
                val steps = oldCert.steps.map { Certificates.Step(requireNotNull(Certificates.parseMove(it.move)), requireNotNull(it.rngHex)) }
                val cert = Certificates.fromSteps(level,difficulty,steps)
                check(Certificates.verify(level,cert).isEmpty()) { "Witness changed for ${level.id}/$difficulty" }
                cert
            }
            level to certificates
        }
        val json = Json { prettyPrint=true; encodeDefaults=true }
        refreshed.forEach { (level,certificates) ->
            File(root,"levels/${level.stopId}/${level.id}.json").writeText(levelJson(level)+"\n")
            certificates.forEach { cert ->
                File(root,"certificates/${level.id}/${cert.difficulty}.json").writeText(json.encodeToString(CertificateDto.serializer(),cert)+"\n")
            }
        }
        Certifier.rebuildCatalog(root,1,refreshed.map { it.first })
        println("refresh-certificates: ${refreshed.size} levels, engine ${GameEngine.ENGINE_VERSION}, all witnesses replayed")
    }
}
