package pt.tiago.neonline.core.content

import kotlinx.serialization.Serializable

@Serializable
data class DailyPoolDto(val version: Int = 1, val engineVersion: Int, val entries: List<DailyEntryDto>)
@Serializable
data class DailyEntryDto(val levelId: String, val ordinal: Int, val difficulty: String, val sha256: String)
