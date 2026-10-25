package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoRinkBossTest {
    @Test fun `level 350 becomes the Skater boss without changing its board rules`() {
        val published = Files.levels(File("content")).single { it.ordinal == 350 }
        val source = published.copy(
            titleEn = "Last Spin", titlePt = "Última volta", familyId = "disco-rink-last-spin",
            learningTags = listOf("disco-rink", "duel", "clock"),
            isDoubleNeon = true,
            variants = published.variants.mapValues { (_, variant) ->
                variant.copy(kind = pt.tiago.neonline.core.content.KindDto("duel"), bossRounds = emptyList())
            },
        )
        val boss = DiscoRinkBoss.toBoss(source)

        assertEquals(350, boss.ordinal)
        assertEquals("stop-07", boss.stopId)
        assertEquals("Skater's Final Spin", boss.titleEn)
        assertEquals("Última volta do Patins", boss.titlePt)
        assertEquals("disco-rink-skater-boss", boss.familyId)
        assertFalse(boss.isDoubleNeon)
        assertTrue(boss.learningTags.containsAll(listOf("disco-rink", "boss", "duel", "clock")))
        for ((key, variant) in boss.variants) {
            assertEquals("boss", variant.kind.type)
            assertEquals(3, variant.kind.bestOf)
            assertEquals("skater", variant.rival)
            assertEquals(10, variant.rules.moveClockSec)
            assertEquals(source.variants.getValue(key).pieces, variant.pieces)
            assertTrue(variant.bossRounds.isEmpty())
        }
    }
}
