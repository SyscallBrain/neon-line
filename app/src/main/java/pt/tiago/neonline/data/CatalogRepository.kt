package pt.tiago.neonline.data

import pt.tiago.neonline.core.content.levelJson
import pt.tiago.neonline.core.content.CatalogDto
import pt.tiago.neonline.core.content.LevelFileDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.model.Difficulty

/** The published catalog: the stop names and one entry per level with its sha256. */
@Serializable
data class Catalog(
    val engineVersion: Int = CURRENT_ENGINE_VERSION,
    val levels: List<CatalogLevel> = emptyList(),
)

@Serializable
data class CatalogLevel(
    val id: String,
    val ordinal: Int,
    val path: String,
    val sha256: String,
)

/** Asset manifest written by :leveltool export-assets. */
@Serializable
data class AssetManifest(val entries: Map<String, String>)

/**
 * Loads the campaign content from the app's assets and verifies it against the manifest, so a
 * tampered or truncated level file is refused instead of crashing the game.
 */
class CatalogRepository(private val assets: AssetSource, private val indexStore: CatalogIndexStore? = null) {

    private val json = Json { ignoreUnknownKeys = true }

    private var cachedRivalLevels: List<RivalLevel>? = null
    private var cachedRush:RushPack? = null
    private var cachedDaily: pt.tiago.neonline.core.content.DailyPoolDto? = null
    private var cachedCatalog: Catalog? = null
    private var cachedCampaignLevels: List<CampaignMapLevel>? = null
    private var cachedManifest: AssetManifest? = null
    private var cachedManifestSha: String? = null
    /** The few levels the screens ask for repeatedly (current level, rival profile, resume checks). */
    private val recentLevels = object : LinkedHashMap<String, LevelFileDto>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LevelFileDto>) = size > RECENT_LEVELS
    }

    /**
     * Reads and verifies every level once. The map and rival metadata are built in the same pass, so
     * opening the campaign, the rivals or free play never reads the 600 level files again. After the
     * first launch of an install, the stored index replaces this pass (see [restoreIndex]).
     */
    @Synchronized
    fun load(): Catalog? {
        cachedCatalog?.let { return it }
        restoreIndex()?.let { return it }
        return verifyAll()
    }

    private class VerifiedLevel(val entry: CatalogLevel, val campaign: List<CampaignMapLevel>, val rivals: List<RivalLevel>)

    private fun verifyAll(): Catalog? {
        val manifest = manifest() ?: return null
        val text = verifiedText(CATALOG_PATH, manifest) ?: return null
        return runCatching {
            val dto = json.decodeFromString(CatalogDto.serializer(), text)
            require(dto.engineVersion == CURRENT_ENGINE_VERSION)
            // Each level verifies on its own, so the work is spread over the cores. Workers only touch
            // the local manifest, never this repository's lock.
            val verified = dto.levels.parallelStream()
                .map { entry -> verifyLevel(entry, dto.engineVersion, manifest) }
                .collect(java.util.stream.Collectors.toList())
            val levels = verified.map { it.entry }
            require(levels.map { it.ordinal }.distinct().size == levels.size)
            val campaign = verified.flatMap { it.campaign }
            val rivals = verified.flatMap { it.rivals }
            cachedCampaignLevels = campaign
            cachedRivalLevels = rivals
            Catalog(dto.engineVersion, levels.sortedBy { it.ordinal }).also {
                cachedCatalog = it
                saveIndex(it, campaign, rivals)
            }
        }.getOrNull()
    }

    private fun verifyLevel(entry: pt.tiago.neonline.core.content.CatalogEntryDto, engineVersion: Int, manifest: AssetManifest): VerifiedLevel {
        val levelText = verifiedText(entry.path, manifest) ?: error("Missing or invalid level: ${entry.path}")
        val level = json.decodeFromString(LevelFileDto.serializer(), levelText)
        require(sha256(levelJson(level)) == entry.sha256)
        require(level.revision == entry.revision)
        require(level.engineVersion == engineVersion)
        val campaign = Difficulty.entries.map { CampaignMapLevel.from(level, it) }
        val rivals = Difficulty.entries.map { difficulty ->
            val variant = level.variants.getValue(pt.tiago.neonline.core.content.difficultyKey(difficulty))
            RivalLevel(level.id, level.ordinal, difficulty, variant.rival, variant.kind.type == "boss")
        }
        return VerifiedLevel(CatalogLevel(level.id, level.ordinal, entry.path, entry.sha256), campaign, rivals)
    }

    /** True when the last [load] came from the stored index instead of a full verification. */
    var restoredFromIndex = false
        private set

    /**
     * The result of a full verification, stored privately and keyed by the manifest sha256 and by the
     * installed build. The manifest names every asset with its sha256, so the same manifest in the
     * same install means the same verified content. Levels are still checked one by one when played.
     * The small level list is read at startup; the map and rival metadata only when first needed.
     */
    private fun restoreIndex(): Catalog? {
        val store = indexStore ?: return null
        val key = indexKey(store) ?: return null
        val index = runCatching { json.decodeFromString(CatalogIndex.serializer(), store.read(INDEX_LEVELS) ?: return null) }.getOrNull() ?: return null
        if (index.key != key || index.catalog.engineVersion != CURRENT_ENGINE_VERSION || index.catalog.levels.isEmpty()) return null
        cachedCatalog = index.catalog
        restoredFromIndex = true
        return index.catalog
    }

    /** Fills the map and rival metadata from the stored index, or verifies everything again if it is unusable. */
    private fun ensureMetadata() {
        load() ?: return
        if (cachedCampaignLevels != null && cachedRivalLevels != null) return
        val store = indexStore
        val key = store?.let(::indexKey)
        val index = if (store == null || key == null) null else runCatching {
            json.decodeFromString(MetadataIndex.serializer(), store.read(INDEX_METADATA) ?: error("missing"))
        }.getOrNull()?.takeIf { it.key == key }
        if (index != null) {
            cachedCampaignLevels = index.campaign
            cachedRivalLevels = index.rivals
        } else {
            cachedCatalog = null
            restoredFromIndex = false
            verifyAll()
        }
    }

    private fun saveIndex(catalog: Catalog, campaign: List<CampaignMapLevel>, rivals: List<RivalLevel>) {
        val store = indexStore ?: return
        val key = indexKey(store) ?: return
        runCatching {
            // Metadata first: a level list on disk always has its metadata next to it.
            store.write(INDEX_METADATA, json.encodeToString(MetadataIndex.serializer(), MetadataIndex(key, campaign, rivals)))
            store.write(INDEX_LEVELS, json.encodeToString(CatalogIndex.serializer(), CatalogIndex(key, catalog)))
        }
    }

    private fun indexKey(store: CatalogIndexStore): String? {
        manifest() ?: return null
        return "$INDEX_FORMAT:${store.buildId}:${cachedManifestSha ?: return null}"
    }

    @Synchronized
    fun manifest(): AssetManifest? {
        cachedManifest?.let { return it }
        val text = assets.readText(MANIFEST_PATH) ?: return null
        cachedManifestSha = sha256(text)
        val entries = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            if (line.startsWith("#") || line.isBlank() || '=' !in line) return@forEach
            val (path, sha) = line.split("=", limit = 2)
            entries[path.trim()] = sha.trim()
        }
        return AssetManifest(entries).also { cachedManifest = it }
    }

    /** The level file at [path], verified against the manifest sha256. */
    fun readLevel(path: String): String? = verifiedText(path, manifest() ?: return null)

    private fun verifiedText(path: String, manifest: AssetManifest): String? {
        val expected = manifest.entries[path] ?: return null
        val text = assets.readText(path) ?: return null
        if (sha256(text) != expected) return null
        return text
    }

    /** The decoded level file at [path], verified like [readLevel]; recent levels stay in memory. */
    @Synchronized
    fun level(path: String): LevelFileDto? {
        recentLevels[path]?.let { return it }
        val text = readLevel(path) ?: return null
        return runCatching { json.decodeFromString(LevelFileDto.serializer(), text) }.getOrNull()
            ?.also { recentLevels[path] = it }
    }

    /** All variants are read through the same integrity checks as playable campaign content. */
    @Synchronized
    fun rivalLevels(): List<RivalLevel> {
        ensureMetadata()
        return cachedRivalLevels.orEmpty()
    }

    /** Map presentation shares the catalog and manifest identity checks; no unchecked fallback metadata. */
    @Synchronized
    fun campaignLevels(): List<CampaignMapLevel> {
        ensureMetadata()
        return cachedCampaignLevels.orEmpty()
    }

    @Synchronized
    @JvmOverloads
    fun rushPack(checkCancelled: () -> Unit = {}): RushPack? {
        checkCancelled()
        cachedRush?.let { return it }
        val text=readLevel("rush/pool.json") ?: return null
        return runCatching {
            val pool=json.decodeFromString(pt.tiago.neonline.core.content.RushPoolDto.serializer(),text)
            RushPack.from(pool,checkCancelled).also { cachedRush=it }
        }.getOrElse { error ->
            if(error is java.util.concurrent.CancellationException) throw error
            null
        }
    }

    @Synchronized
    fun dailyPool(): pt.tiago.neonline.core.content.DailyPoolDto? {
        cachedDaily?.let { return it }
        return runCatching {
            val pool = json.decodeFromString(pt.tiago.neonline.core.content.DailyPoolDto.serializer(), requireNotNull(readLevel("daily/pool.json")))
            require(pool.version > 0 && pool.engineVersion == CURRENT_ENGINE_VERSION)
            val levels = requireNotNull(load()).levels
            require(pool.entries.map { it.levelId to it.difficulty }.distinct().size == pool.entries.size)
            require(difficultyKeys().all { key -> pool.entries.any { it.difficulty == key } })
            for (entry in pool.entries) {
                val reference = levels.first { it.id == entry.levelId }
                require(reference.ordinal == entry.ordinal && reference.sha256 == entry.sha256)
                val level = requireNotNull(level(reference.path))
                val variant = level.variants.getValue(entry.difficulty)
                require(variant.kind.type == "puzzle" && (variant.kind.winInMoves ?: 0) >= 2)
            }
            pool.also { cachedDaily = it }
        }.getOrNull()
    }

    fun levelAt(ordinal: Int, catalog: Catalog): CatalogLevel? =
        catalog.levels.firstOrNull { it.ordinal == ordinal }

    fun difficultyKeys(): List<String> = Difficulty.entries.map { it.name.lowercase() }

    companion object {
        const val CATALOG_PATH = "levels/catalog.json"
        const val MANIFEST_PATH = "manifest.txt"
        private const val RECENT_LEVELS = 8
        private const val INDEX_FORMAT = 2
        private const val INDEX_LEVELS = "catalog-index-levels.json"
        private const val INDEX_METADATA = "catalog-index-metadata.json"
        private val HEX = "0123456789abcdef".toCharArray()

        fun sha256(text: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(text.toByteArray())
            val out = CharArray(bytes.size * 2)
            bytes.forEachIndexed { i, b ->
                out[i * 2] = HEX[(b.toInt() shr 4) and 0xF]
                out[i * 2 + 1] = HEX[b.toInt() and 0xF]
            }
            return String(out)
        }
    }
}

@Serializable
private data class CatalogIndex(val key: String, val catalog: Catalog)

@Serializable
private data class MetadataIndex(val key: String, val campaign: List<CampaignMapLevel>, val rivals: List<RivalLevel>)

/** Private storage for the verified catalog index files; [buildId] changes with every install of the app. */
interface CatalogIndexStore {
    val buildId: String
    fun read(name: String): String?
    fun write(name: String, text: String)
}

/** Where the assets come from; the app provides it with its AssetManager. */
interface AssetSource {
    fun readText(path: String): String?
}
