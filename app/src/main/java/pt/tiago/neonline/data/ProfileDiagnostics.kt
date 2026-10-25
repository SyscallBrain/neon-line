package pt.tiago.neonline.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import pt.tiago.neonline.core.model.Difficulty

@Serializable
data class DiagnosticEnvironment(
    val versionName: String,
    val versionCode: Int,
    val androidApi: Int,
    val androidVersion: String,
    val manufacturer: String,
    val model: String,
)

/** A diagnostic is an explicit export, not a restorable profile or a network upload. */
object ProfileDiagnostics {
    const val MAX_CRASH_CHARS = 256 * 1024
    const val MAX_PERFECT_SEARCH_RECORDS = PerfectSearchDiagnostics.MAX_ENTRIES
    const val MAX_PERFECT_SEARCH_RECORD_CHARS = PerfectSearchDiagnostics.MAX_LINE_CHARS
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    fun encode(profile: Profile, catalog: Catalog?, resumable: Boolean,
               environment: DiagnosticEnvironment, crashReport: String?,
               perfectSearchRecords: List<String> = emptyList()): ByteArray {
        val boundedSearchRecords = perfectSearchRecords.takeLast(MAX_PERFECT_SEARCH_RECORDS)
        val perfectSearchSummary = PerfectSearchDiagnostics.summarize(boundedSearchRecords)
        val report = buildJsonObject {
            put("app", "NeonLine")
            put("diagnosticVersion", 2)
            put("environment", json.encodeToJsonElement(DiagnosticEnvironment.serializer(), environment))
            put("profileVersions", buildJsonObject {
                put("schema", profile.schemaVersion)
                put("content", profile.contentVersion)
                put("engine", profile.engineVersion)
                put("revision", profile.revision)
            })
            put("catalog", buildJsonObject {
                put("available", catalog != null)
                put("levelCount", catalog?.levels?.size ?: 0)
                put("engineVersion", catalog?.engineVersion?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("settings", json.encodeToJsonElement(Settings.serializer(), profile.settings))
            put("difficulties", buildJsonObject {
                for (difficulty in Difficulty.entries) {
                    val summary = CampaignStatistics.summarize(profile, catalog?.levels.orEmpty(), difficulty)
                    put(difficulty.name, buildJsonObject {
                        put("completedLevels", summary.completedLevels)
                        put("stars", summary.stars)
                        put("highestReached", profile.progress(difficulty).highestReached)
                        put("stats", json.encodeToJsonElement(DifficultyStats.serializer(), summary.stats))
                        put("hasLegacyGaps", summary.hasLegacyGaps)
                    })
                }
            })
            put("savedFreePlay", profile.savedFreePlay?.let { json.encodeToJsonElement(SavedFreePlay.serializer(), it) } ?: JsonNull)
            put("freePlayPeeks", json.encodeToJsonElement(Profile.serializer(), profile).jsonObject["freePlayPeeks"] ?: JsonObject(emptyMap()))
            put("freePlayStats", json.encodeToJsonElement(Profile.serializer(), profile).jsonObject["freePlayStats"] ?: JsonObject(emptyMap()))
            put("rivalRecords", json.encodeToJsonElement(Profile.serializer(), profile).jsonObject["rivalRecords"] ?: JsonObject(emptyMap()))
            put("savedMatchResumable", profile.savedMatch != null && resumable)
            put("savedMatch", profile.savedMatch?.let { json.encodeToJsonElement(SavedMatch.serializer(), it) } ?: JsonNull)
            put("dailyHistory", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(DailyRecord.serializer()), profile.dailyHistory))
            put("savedDaily", profile.savedDaily?.let { json.encodeToJsonElement(SavedDaily.serializer(), it) } ?: JsonNull)
            put("localSession", profile.localSession?.let {
                json.encodeToJsonElement(SavedLocalSession.serializer(), it.copy(config = it.config.copy(player1 = "", player2 = "")))
            } ?: JsonNull)
            put("localSessionResumable", profile.localSession?.let { LocalSessionReplay.restore(it) != null } ?: false)
            put("lastCrash", crashReport?.take(MAX_CRASH_CHARS)?.let(::JsonPrimitive) ?: JsonNull)
            put("lastCrashTruncated", (crashReport?.length ?: 0) > MAX_CRASH_CHARS)
            put("perfectSearch", JsonArray(boundedSearchRecords.map {
                JsonPrimitive(it.replace('\r', ' ').replace('\n', ' ').take(MAX_PERFECT_SEARCH_RECORD_CHARS))
            }))
            put("perfectSearchTruncated", perfectSearchRecords.size > MAX_PERFECT_SEARCH_RECORDS)
            put("perfectSearchSummary", buildJsonObject {
                put("sampleCount", perfectSearchSummary.sampleCount)
                put("p95ElapsedMs", perfectSearchSummary.p95ElapsedMs?.let(::JsonPrimitive) ?: JsonNull)
                put("maxElapsedMs", perfectSearchSummary.maxElapsedMs?.let(::JsonPrimitive) ?: JsonNull)
                put("timeoutCount", perfectSearchSummary.timeoutCount)
                put("sourceCounts", buildJsonObject {
                    perfectSearchSummary.sourceCounts.forEach { (source, count) -> put(source, count) }
                })
            })
        }
        return json.encodeToString(JsonObject.serializer(), report).toByteArray(Charsets.UTF_8)
    }
}
