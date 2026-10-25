package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class DinerServiceContentTest {
    @Test fun `published service lessons satisfy their physical families and exact puzzle goals`() {
        val root = File("content")
        val recipes = DinerService.load(root)
        val all = Files.levels(root)
        val levels = all.filter { it.ordinal in 61..99 }
        assertEquals((61..99).toList(),levels.map { it.ordinal })
        val seen = mutableSetOf<String>()
        for (level in all.filter { it.ordinal in 61..99 }) for (variant in level.variants.values) {
            val state = variant.toState(level.id,Difficulty.EASY)
            val rows = (0..5).map { r -> (0..6).joinToString("") { c -> state.pieceAt(r,c)?.owner?.ordinal?.toString() ?: "." } }
            val physical = minOf(rows.joinToString("/"),rows.joinToString("/") { it.reversed() })
            assertTrue("Duplicate or reflected opening ${level.id}",seen.add(physical))
        }
        for (level in levels) {
            val lesson = recipes.lessons.first { it.ordinal == level.ordinal }
            val family = recipes.families.first { it.id == lesson.familyId }
            assertEquals(family.id,level.familyId)
            assertEquals(level.ordinal==70 || level.ordinal==80 || level.ordinal==90,level.isDoubleNeon)
            assertNull(level.tutorial)
            for ((key,variant) in level.variants) {
                assertEquals("dolores",variant.rival);assertTrue(variant.ai.mirror)
                assertEquals(family.kind,variant.kind.type)
                val state = variant.toState(level.id,Difficulty.valueOf(key.uppercase()))
                assertTrue(variant.pieces.size in 2*family.minOpeningPairs..2*family.maxOpeningPairs)
                assertTrue(GameEngine.validMoves(state).none { GameEngine.apply(state,it).state.status == Status.Won(Player.P1) })
                if (family.kind == "defense") {
                    val probe = state.copy(toMove=Player.P2)
                    val threats = GameEngine.validMoves(probe).filter { GameEngine.apply(probe,it).state.status == Status.Won(Player.P2) }
                    assertTrue(threats.isNotEmpty())
                    assertTrue(threats.all { (it as Move.Drop).col in family.threatColumns })
                }
                if (family.kind == "puzzle") {
                    val target = variant.kind.winInMoves!!
                    assertEquals(if(key=="hard")4 else if(key=="medium" || family.id=="diner-long-puzzle")3 else 2,target)
                    val proof = ForcedWin(150_000)
                    assertEquals(false,proof.canWin(state,target-1))
                    val roots = proof.winningMoves(state,target)!!
                    assertTrue(roots.isNotEmpty())
                    if(key!="easy")assertEquals(1,roots.size)
                }
                val cert = Certificates.decodeCertificate(File(root,"certificates/${level.id}/$key.json").readText())
                assertTrue(Certificates.verify(level,cert).isEmpty())
                assertTrue((cert.steps.size+1)/2 >= family.minimumWitnessMoves)
            }
        }
    }
}
