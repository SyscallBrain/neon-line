package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as TempFiles
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher

class DifficultyRegressionTest {
    private val root = File("content")
    @Test fun `only the first lesson and new shape introductions start with a one move win`() {
        val expected = listOf(1, 101, 104, 107, 109)
        for (difficulty in Difficulty.entries) {
            val immediate = Files.levels(root).filter { level ->
                val state = level.variants.getValue(difficultyKey(difficulty)).toState(level.id, difficulty)
                GameEngine.validMoves(state).any { (GameEngine.apply(state,it).state.status as? Status.Won)?.player == Player.P1 }
            }
            assertEquals(difficulty.name, expected, immediate.map { it.ordinal })
        }
    }

    @Test fun `puzzles require their advertised own move count against every defence`() {
        for (level in Files.levels(root)) for ((key, variant) in level.variants) {
            val n = variant.kind.winInMoves ?: continue
            val start = variant.toState(level.id, Difficulty.valueOf(key.uppercase()))
            val solver = ForcedWin(120_000)
            assertEquals("${level.id}/$key", true, solver.canWin(start, n))
            if(n > 1) assertEquals("${level.id}/$key was trivial", false, solver.canWin(start,n-1))
        }
    }

    @Test fun `first chapter hard duel witnesses keep five player moves and no hard blunders`() {
        for (level in Files.levels(root).filter { it.ordinal in 2..50 }) {
            val variant = level.variants.getValue("hard")
            assertFalse(variant.ai.blunderMayMiss)
            assertEquals(0, variant.ai.blunderRate)
            if(variant.kind.type == "puzzle") continue
            val cert = Certificates.decodeCertificate(File(root,"certificates/${level.id}/hard.json").readText())
            assertTrue(cert.humanMoves >= 5)
            assertEquals((cert.steps.size + 1)/2,cert.humanMoves)
        }
    }

    @Test fun `certificates contain after state hashes and reject false counters`() {
        val level = Files.levels(root).first()
        val cert = Certificates.decodeCertificate(File(root,"certificates/${level.id}/easy.json").readText())
        val state = level.variants.getValue("easy").toState(level.id,Difficulty.EASY)
        val after = GameEngine.apply(state,Move.Drop(3)).state
        assertEquals(StateHasher.hash(after),cert.steps.first().afterStateHash)
        assertTrue(Certificates.verify(level,cert.copy(humanMoves=cert.humanMoves+1)).any { it.contains("count") })
        assertTrue(Certificates.verify(level,cert.copy(stars=2)).isNotEmpty())
    }

    @Test fun `generation from curated recipes reproduces the revised fifty levels`() {
        val dir = TempFiles.createTempDirectory("neonline-recipes").toFile()
        try {
            val templates = File(dir,"templates").apply { mkdirs() }
            File(root,"templates/stop-01-blueprints.json").copyTo(File(templates,"stop-01-blueprints.json"))
            assertEquals(Files.levels(root).filter { it.ordinal<=50 },Generator.generate(dir,1,1,50))
            assertEquals(Files.levels(root).slice(10..11),Generator.generate(dir,1,11,12))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `validator rejects reintroduced trivial openings`() {
        val level = Files.levels(root).first { it.ordinal == 11 }
        val broken = level.copy(variants=level.variants.mapValues { (_,v) -> v.copy(pieces=(0..2).map { PieceDto(5,it,"P1") }) })
        assertTrue(Validator.validateLevel(broken).any { it.contains("immediate winning") })
        assertTrue(Validator.validateLevel(broken).any { it.contains("same opening") })
    }
}
