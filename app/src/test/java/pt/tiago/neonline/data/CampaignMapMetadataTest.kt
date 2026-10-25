package pt.tiago.neonline.data

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelKind

class CampaignMapMetadataTest {
    private fun texts(): MutableMap<String,String> {
        val root=listOf(File("../content"),File("content")).first { File(it,"catalog.json").isFile }
        val texts=root.walkTopDown().filter { it.isFile && it.extension=="json" }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }.toMutableMap()
        texts[CatalogRepository.CATALOG_PATH]=File(root,"catalog.json").readText()
        texts[CatalogRepository.MANIFEST_PATH]=texts.entries.joinToString("\n") { "${it.key}=${CatalogRepository.sha256(it.value)}" }
        return texts
    }
    @Test fun `all verified map variants expose actual goals boss double neon and cached metadata`() {
        val texts=texts(); var reads=0
        val repo=CatalogRepository(object: AssetSource { override fun readText(path:String):String? { reads++; return texts[path] } })
        val metadata=repo.campaignLevels()
        assertEquals(1_800,metadata.size)
        for(entry in metadata) {
            val level=Json.decodeFromString(LevelFileDto.serializer(),texts.getValue(repo.load()!!.levels.first { it.id == entry.id }.path))
            val variant=level.variants.getValue(difficultyKey(entry.difficulty))
            assertEquals(variant.kindEnum(),entry.kind); assertEquals(variant.kind.winInMoves,entry.winInMoves)
            assertEquals(level.isDoubleNeon,entry.doubleNeon); assertEquals(level.titlePt,entry.titlePt)
            assertEquals(variant.rival,entry.rival)
            assertEquals(variant.rules.popOut,entry.popOut)
            assertEquals(variant.rules.rotateEvery,entry.rotateEvery)
            assertEquals(variant.rules.fadeAfter,entry.fadeAfter)
        }
        assertEquals(36,metadata.count { it.kind==LevelKind.BOSS && it.ordinal in setOf(50,100,150,200,250,300,350,400,450,500,550,600) })
        assertEquals(12,metadata.count { it.kind==LevelKind.SHAPE })
        assertEquals(setOf(101,104,107,109),metadata.filter { it.kind==LevelKind.SHAPE }.map { it.ordinal }.toSet())
        assertEquals(setOf(10,20,30,40,60,70,80,90,110,120,130,140,160,170,180,190,210,220,230,240,260,270,280,290,310,320,330,340,360,370,380,390,410,420,430,440,460,470,480,490,500,510,520,530,540,550,560,570,580,590,600),metadata.filter { it.doubleNeon }.map { it.ordinal }.toSet())
        assertEquals((151..200).toSet(), metadata.filter { it.rival == "strike" }.map { it.ordinal }.toSet())
        assertEquals((201..250).toSet(), metadata.filter { it.rival == "projectionist" }.map { it.ordinal }.toSet())
        assertEquals((251..300).toSet(), metadata.filter { it.rival == "centrifuge" }.map { it.ordinal }.toSet())
        assertEquals((301..350).toSet(), metadata.filter { it.rival == "skater" }.map { it.ordinal }.toSet())
        assertEquals((351..400).toSet(), metadata.filter { it.rival == "diva" }.map { it.ordinal }.toSet())
        assertEquals((401..450).toSet(), metadata.filter { it.rival == "pixel" }.map { it.ordinal }.toSet())
        assertEquals((451..500).toSet(), metadata.filter { it.rival == "wheel" }.map { it.ordinal }.toSet())
        assertEquals((501..550).toSet(), metadata.filter { it.rival == "ace" }.map { it.ordinal }.toSet())
        assertEquals((551..600).toSet(), metadata.filter { it.rival == "queen" }.map { it.ordinal }.toSet())
        assertEquals(setOf(325, 328, 335, 338, 345, 348, 355, 358, 365, 368, 375, 378, 385, 388, 395, 398, 503, 505, 507, 510, 518, 519, 524, 528, 532, 537, 542, 546, 547, 555, 557, 558, 565, 567, 568, 575, 577, 578, 585, 587, 588, 595, 597, 598), metadata.filter { it.rotateEvery == 6 }.map { it.ordinal }.toSet())
        assertEquals(setOf(552, 558, 562, 568, 572, 578, 582, 588, 592, 598), metadata.filter { it.popOut && it.ordinal in 551..600 }.map { it.ordinal }.toSet())
        assertEquals(setOf(554, 564, 574, 584, 594), metadata.filter { it.fadeAfter != null && it.ordinal in 551..600 }.map { it.ordinal }.toSet())
        assertEquals(setOf(322, 326), metadata.filter { it.popOut && it.ordinal in 321..330 }.map { it.ordinal }.toSet())
        assertEquals(setOf(332, 336), metadata.filter { it.popOut && it.ordinal in 331..340 }.map { it.ordinal }.toSet())
        assertEquals(setOf(342, 346), metadata.filter { it.popOut && it.ordinal in 341..350 }.map { it.ordinal }.toSet())
        assertEquals(setOf(355, 358, 365, 368, 375, 378, 385, 388, 395, 398), metadata.filter { it.rotateEvery == 6 && it.ordinal in 351..400 }.map { it.ordinal }.toSet())
        assertEquals(setOf(352, 356, 362, 366, 372, 376, 382, 386, 392, 396), metadata.filter { it.popOut && it.ordinal in 351..400 }.map { it.ordinal }.toSet())
        assertEquals((351..400).toSet(), metadata.filter { it.fadeAfter != null && it.ordinal in 351..400 }.map { it.ordinal }.toSet())
        val loadedReads=reads; assertSame(metadata,repo.campaignLevels()); assertEquals(loadedReads,reads)
    }
    @Test fun `map metadata comes from the validated catalog pass, not from a later read`() {
        val texts=texts()
        val repo=CatalogRepository(object: AssetSource { override fun readText(path:String)=texts[path] })
        assertNotNull(repo.load())
        val validated=repo.campaignLevels()
        texts["levels/stop-01/level-0001.json"]="{}"
        assertSame(validated,repo.campaignLevels())
    }
    @Test fun `map metadata is empty when a level fails validation`() {
        val texts=texts()
        texts["levels/stop-01/level-0001.json"]="{}"
        val repo=CatalogRepository(object: AssetSource { override fun readText(path:String)=texts[path] })
        assertTrue(repo.campaignLevels().isEmpty())
    }
    @Test fun `synthetic rule metadata describes actual variants without inventing double neon`() {
        val text=texts().getValue("levels/stop-01/level-0001.json")
        val level=Json.decodeFromString(LevelFileDto.serializer(),text)
        val original=level.variants.getValue("easy")
        val variant=original.copy(rules=RulesDto(popOut=true,rotateEvery=6,fadeAfter=3,moveClockSec=10),
            inventory=mapOf("P1" to mapOf("bomb" to 1)),connect=5)
        val metadata=CampaignMapLevel.from(level.copy(variants=level.variants+("easy" to variant)),Difficulty.EASY)
        assertTrue(metadata.popOut); assertEquals(6,metadata.rotateEvery); assertEquals(3,metadata.fadeAfter)
        assertEquals(10,metadata.clockSeconds); assertTrue(metadata.specials); assertEquals(5,metadata.connect)
        assertFalse(metadata.doubleNeon)
    }
}
