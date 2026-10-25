package pt.tiago.neonline.leveltool

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.rules.GameEngine

/** A frozen pool of existing certified puzzles; publishing different entries requires a new version. */
object DailyPool {
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    fun generate(root: File, output: File, version: Int = 1) {
        require(version > 0)
        val catalog = Json.decodeFromString(CatalogDto.serializer(), File(root, "catalog.json").readText())
        val entries = catalog.levels.flatMap { entry ->
            val file = File(root, entry.path)
            val text = file.readText()
            val level = Json.decodeFromString(LevelFileDto.serializer(), text)
            // Version 1 is frozen to the original chapter even as the campaign grows.
            if (version == 1 && level.ordinal > 50) return@flatMap emptyList()
            require(entry.sha256 == hash(levelJson(level)))
            level.variants.toSortedMap().mapNotNull { (difficulty, variant) ->
                if (variant.kind.type != "puzzle" || (variant.kind.winInMoves ?: 0) < 2) null else {
                    val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$difficulty.json").readText())
                    require(Certificates.verify(level, certificate).isEmpty()) { "Invalid daily certificate: ${level.id}/$difficulty" }
                    DailyEntryDto(level.id, level.ordinal, difficulty, entry.sha256)
                }
            }
        }.sortedWith(compareBy({ it.difficulty }, { it.ordinal }))
        require(listOf("easy", "medium", "hard").all { key -> entries.any { it.difficulty == key } })
        val pool = DailyPoolDto(version, GameEngine.ENGINE_VERSION, entries)
        if (output.isFile) {
            val previous = Json.decodeFromString(DailyPoolDto.serializer(), output.readText())
            require(previous == pool || version > previous.version) { "Changing the daily pool requires a higher version" }
        }
        output.parentFile.mkdirs()
        output.writeText(json.encodeToString(DailyPoolDto.serializer(), pool) + "\n")
        println("daily-pool: ${entries.size} certified puzzles, version $version")
    }
    fun verify(root: File, pool: DailyPoolDto) {
        require(pool.version > 0 && pool.engineVersion == GameEngine.ENGINE_VERSION)
        require(pool.entries.map { it.levelId to it.difficulty }.distinct().size == pool.entries.size)
        val catalog = Json.decodeFromString(CatalogDto.serializer(), File(root, "catalog.json").readText())
        for (entry in pool.entries) {
            val reference = catalog.levels.first { it.path.endsWith("/${entry.levelId}.json") }
            require(reference.sha256 == entry.sha256)
            val level = Json.decodeFromString(LevelFileDto.serializer(), File(root, reference.path).readText())
            require(level.ordinal == entry.ordinal && level.id == entry.levelId)
            val variant = level.variants.getValue(entry.difficulty)
            require(variant.kind.type == "puzzle" && (variant.kind.winInMoves ?: 0) >= 2)
            require(Certificates.verify(level, Certificates.decodeCertificate(File(root, "certificates/${entry.levelId}/${entry.difficulty}.json").readText())).isEmpty())
        }
        require(listOf("easy", "medium", "hard").all { key -> pool.entries.any { it.difficulty == key } })
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
