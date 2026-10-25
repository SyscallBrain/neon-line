package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.KindDto
import pt.tiago.neonline.core.model.Difficulty

class DefenseCertificateTest {
    @Test fun `defense goals must be present positive and fit a board length`() {
        val root=listOf(File("../content"),File("content")).first { File(it,"catalog.json").isFile }
        val level=Certificates.decodeLevel(File(root,"levels/stop-01/level-0011.json").readText())
        for(goal in listOf(null,-1,0,43)) {
            val bad=level.copy(variants=level.variants.mapValues { (_,v)->v.copy(kind=KindDto("defense",surviveAiMoves=goal)) })
            assertTrue(Validator.validateLevel(bad).any { it.contains("surviveAiMoves") })
        }
    }
    @Test fun `defense witnesses cannot continue past the required rival turns to claim three stars`() {
        val root=listOf(File("../content"),File("content")).first { File(it,"catalog.json").isFile }
        val original=Certificates.decodeLevel(File(root,"levels/stop-01/level-0011.json").readText())
        val path=Certificates.decodeCertificate(File(root,"certificates/level-0011/easy.json").readText()).steps.map {
            Certificates.Step(requireNotNull(Certificates.parseMove(it.move)),requireNotNull(it.rngHex))
        }
        assertTrue(path.size>2)
        val defense=original.copy(variants=original.variants.mapValues { (_,v)->v.copy(kind=KindDto("defense",surviveAiMoves=1)) })
        val late=Certificates.fromSteps(defense,Difficulty.EASY,path)
        assertTrue(Certificates.verify(defense,late).any { it.contains("defense already complete") })
        val enough=defense.copy(variants=defense.variants.mapValues { (_,v)->v.copy(kind=KindDto("defense",surviveAiMoves=42)) })
        assertTrue(Certificates.verify(enough,Certificates.fromSteps(enough,Difficulty.EASY,path)).isEmpty())
    }
}
