package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BoulevardOfLightsClosingServiceTest {
    @Test fun `closing recipe covers the last Queen block and Double Neon at 590`() {
        val lessons = BoulevardOfLightsClosingService.load(File("content")).lessons
        assertEquals((581..590).toList(), lessons.map { it.ordinal })
        assertEquals(5, lessons.count { it.kind == "duel" })
        assertEquals(3, lessons.count { it.kind == "puzzle" })
        assertEquals(2, lessons.count { it.kind == "defense" })
        assertTrue(lessons.last().isDoubleNeon)
        assertTrue(lessons.dropLast(1).none { it.isDoubleNeon })
        assertTrue(lessons.single { it.ordinal == 586 }.mirrorSourceHard)
        assertEquals(279, lessons.single { it.ordinal == 589 }.sourceHardOrdinal)
    }

    @Test fun `published closing levels feature Queen and replay all certificates`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 581..590 }
        assertEquals((581..590).toList(), levels.map { it.ordinal })
        assertTrue(levels.flatMap { it.variants.values }.all { it.rival == "queen" && it.kind.type != "boss" })
        assertTrue(levels.last().isDoubleNeon)
        for (level in levels) for (key in listOf("easy", "medium", "hard")) {
            val certificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key.json").readText())
            assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
        }
    }
}
