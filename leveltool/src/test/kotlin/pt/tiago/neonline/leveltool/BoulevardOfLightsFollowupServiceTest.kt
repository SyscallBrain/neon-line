package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BoulevardOfLightsFollowupServiceTest {
    @Test fun `followup recipe continues the five three two mix and closes at 570`() {
        val lessons = BoulevardOfLightsFollowupService.load(File("content")).lessons
        assertEquals((561..570).toList(), lessons.map { it.ordinal })
        assertEquals(5, lessons.count { it.kind == "duel" })
        assertEquals(3, lessons.count { it.kind == "puzzle" })
        assertEquals(2, lessons.count { it.kind == "defense" })
        assertTrue(lessons.last().isDoubleNeon)
        assertTrue(lessons.dropLast(1).none { it.isDoubleNeon })
    }

    @Test fun `published followup levels feature Queen and replay without creating stop boss`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 561..570 }
        assertEquals((561..570).toList(), levels.map { it.ordinal })
        assertTrue(levels.flatMap { it.variants.values }.all { it.rival == "queen" && it.kind.type != "boss" })
        assertTrue(levels.last().isDoubleNeon)
        for (level in levels) for (key in listOf("easy", "medium", "hard")) {
            val certificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key.json").readText())
            assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
        }
    }
}
