package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BoulevardOfLightsServiceTest {
    @Test fun `opening block recipe has five duels three puzzles two defenses and Double Neon at 560`() {
        val lessons = BoulevardOfLightsService.load(File("content")).lessons
        assertEquals((551..560).toList(), lessons.map { it.ordinal })
        assertEquals(5, lessons.count { it.kind == "duel" })
        assertEquals(3, lessons.count { it.kind == "puzzle" })
        assertEquals(2, lessons.count { it.kind == "defense" })
        assertTrue(lessons.last().isDoubleNeon)
        assertTrue(lessons.dropLast(1).none { it.isDoubleNeon })
        assertTrue(Calibrator.isNoveltyIntroduction(551))
    }

    @Test fun `published opening levels feature the Queen and replay without creating the stop boss`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 551..560 }
        assertEquals((551..560).toList(), levels.map { it.ordinal })
        assertTrue(levels.flatMap { it.variants.values }.all { it.rival == "queen" && it.kind.type != "boss" })
        assertTrue(levels.last().isDoubleNeon)
        for (level in levels) for (key in listOf("easy", "medium", "hard")) {
            val certificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key.json").readText())
            assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
        }
    }
}
