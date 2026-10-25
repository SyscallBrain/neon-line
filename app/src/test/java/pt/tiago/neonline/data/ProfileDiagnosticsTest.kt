package pt.tiago.neonline.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty

class ProfileDiagnosticsTest {
    private val environment = DiagnosticEnvironment("0.11.0", 18, 36, "16", "Test", "Phone")
    private val catalog = Catalog(levels = listOf(CatalogLevel("level-0001", 1, "levels/first.json", "hash")))
    private fun report(profile: Profile = Profile(), catalog: Catalog? = this.catalog,
                       resumable: Boolean = false, crash: String? = null): JsonObject =
        Json.parseToJsonElement(ProfileDiagnostics.encode(profile, catalog, resumable, environment, crash).toString(Charsets.UTF_8)).jsonObject

    @Test fun `diagnostic contains environment and profile versions`() {
        val report = report()
        assertEquals("NeonLine", report["app"]!!.jsonPrimitive.content)
        assertEquals(18, report["environment"]!!.jsonObject["versionCode"]!!.jsonPrimitive.int)
        assertEquals(2, report["diagnosticVersion"]!!.jsonPrimitive.int)
        assertEquals(CURRENT_ENGINE_VERSION, report["profileVersions"]!!.jsonObject["engine"]!!.jsonPrimitive.int)
        assertEquals(1, report["catalog"]!!.jsonObject["levelCount"]!!.jsonPrimitive.int)
    }
    @Test fun `missing catalog and absent crash remain explicit and exportable`() {
        val report = report(catalog = null)
        assertFalse(report["catalog"]!!.jsonObject["available"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, report["lastCrash"])
        assertEquals(JsonNull, report["savedMatch"])
        assertFalse(report["savedMatchResumable"]!!.jsonPrimitive.boolean)
        assertTrue(report["perfectSearch"]!!.jsonArray.isEmpty())
        assertEquals(0, report["perfectSearchSummary"]!!.jsonObject["sampleCount"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, report["perfectSearchSummary"]!!.jsonObject["p95ElapsedMs"])
    }
    @Test fun `diagnostic export carries the latest bounded perfect search records`() {
        val records = (0..ProfileDiagnostics.MAX_PERFECT_SEARCH_RECORDS).map {
            "perfect_search ply=$it elapsed_ms=$it source=book exact=true timed_out=false nodes=0"
        }

        val report = Json.parseToJsonElement(ProfileDiagnostics.encode(Profile(), catalog, false, environment,
            crashReport = null, perfectSearchRecords = records).toString(Charsets.UTF_8)).jsonObject
        val exported = report["perfectSearch"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(ProfileDiagnostics.MAX_PERFECT_SEARCH_RECORDS, exported.size)
        assertEquals("perfect_search ply=1 elapsed_ms=1 source=book exact=true timed_out=false nodes=0", exported.first())
        assertEquals(
            "perfect_search ply=${ProfileDiagnostics.MAX_PERFECT_SEARCH_RECORDS} elapsed_ms=${ProfileDiagnostics.MAX_PERFECT_SEARCH_RECORDS} source=book exact=true timed_out=false nodes=0",
            exported.last(),
        )
        val summary = report["perfectSearchSummary"]!!.jsonObject
        assertEquals(ProfileDiagnostics.MAX_PERFECT_SEARCH_RECORDS, summary["sampleCount"]!!.jsonPrimitive.int)
        assertNotEquals(JsonNull, summary["p95ElapsedMs"])
    }
    @Test fun `statistics include all difficulties and retain legacy warning`() {
        val profile = ProfileReducer.recordWin(Profile(), 1, Difficulty.HARD, 3, 4, 1).copy(
            statsByDifficulty = mapOf(Difficulty.EASY to DifficultyStats(losses = 2)))
        val stats = report(profile)["difficulties"]!!.jsonObject
        assertEquals(3, stats.size)
        assertEquals(3, stats["HARD"]!!.jsonObject["stars"]!!.jsonPrimitive.int)
        assertTrue(stats["EASY"]!!.jsonObject["hasLegacyGaps"]!!.jsonPrimitive.boolean)
    }
    @Test fun `settings and saved journal survive diagnostic JSON including escaped crash text`() {
        val match = SavedMatch("level-0001", Difficulty.EASY,
            steps = listOf(SavedPly("drop", 3, aiStateHex = "rng", afterHash = "hash")))
        val profile = Profile(settings = Settings(soundVolume = .25f, largerText = true), savedMatch = match)
        val crash = "Error: \"boom\"\n at test.method(Test.kt:3)"
        val report = report(profile, resumable = true, crash = crash)
        assertEquals(profile.settings, Json.decodeFromJsonElement(Settings.serializer(), report["settings"]!!))
        assertEquals(match, Json.decodeFromJsonElement(SavedMatch.serializer(), report["savedMatch"]!!))
        assertEquals(crash, report["lastCrash"]!!.jsonPrimitive.content)
        assertTrue(report["savedMatchResumable"]!!.jsonPrimitive.boolean)
    }
    @Test fun `resumable flag cannot claim an absent saved match`() {
        assertFalse(report(resumable = true)["savedMatchResumable"]!!.jsonPrimitive.boolean)
        assertFalse(report(Profile(savedMatch = SavedMatch("obsolete", Difficulty.HARD)))["savedMatchResumable"]!!.jsonPrimitive.boolean)
    }
    @Test fun `crash text is bounded with explicit truncation indicator`() {
        val report = report(crash = "x".repeat(ProfileDiagnostics.MAX_CRASH_CHARS + 100))
        assertEquals(ProfileDiagnostics.MAX_CRASH_CHARS, report["lastCrash"]!!.jsonPrimitive.content.length)
        assertTrue(report["lastCrashTruncated"]!!.jsonPrimitive.boolean)
    }
    @Test fun `diagnostic cannot be imported as a progress backup and does not change profile`() {
        val profile = ProfileReducer.recordWin(Profile(), 1, Difficulty.MEDIUM, 2, 5, 0)
        val snapshot = profile.copy()
        val bytes = ProfileDiagnostics.encode(profile, catalog, false, environment, null)
        assertTrue(runCatching { ProfileBackup.decode(bytes) }.isFailure)
        assertEquals(snapshot, profile)
    }
}
