package pt.tiago.neonline

import android.content.Context
import android.content.res.AssetManager
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.data.AssetSource
import pt.tiago.neonline.data.CatalogRepository
import pt.tiago.neonline.data.Profile
import pt.tiago.neonline.data.ProfileReducer
import pt.tiago.neonline.data.ProfileStore
import pt.tiago.neonline.game.MatchReplay
import pt.tiago.neonline.data.SavedMatch
import pt.tiago.neonline.core.objectives.LevelOutcome
import pt.tiago.neonline.core.content.TutorialDto
import pt.tiago.neonline.core.content.LevelFileDto
import pt.tiago.neonline.core.content.VariantDto
import pt.tiago.neonline.core.content.difficultyKey
import pt.tiago.neonline.core.content.kindEnum

import pt.tiago.neonline.core.content.toState
import pt.tiago.neonline.core.content.forBossRound
import pt.tiago.neonline.game.BossReplay
import pt.tiago.neonline.core.objectives.LevelKind
import pt.tiago.neonline.core.objectives.LevelObjective
import kotlinx.serialization.json.Json

/** What the game screen needs about one level. */
data class LevelInfo(
    val id: String,
    val ordinal: Int,
    val titleEn: String,
    val titlePt: String,
    val kind: LevelKind,
    val winInMoves: Int?,
    val surviveAiMoves: Int?,
    val rival: String,
    val starsTwo: Int,
    val starsThree: Int,
    val width: Int,
    val height: Int,
    val connect: Int,
    val popOut: Boolean,
    val rotateEvery: Int?,
    val fadeAfter: Int?,
    val moveClockSec: Int?,
    val hints: Int,
    val tutorial: TutorialDto?,
    val bossRoundStates: List<GameState> = emptyList(),
)

/**
 * The app container: the profile, the exported content and the audio controller. Created once in
 * the activity and shared by the screens.
 */
class AppContainer(context: Context) {

    private val context: Context = context.applicationContext

    private val json = Json { ignoreUnknownKeys = true }

    val profileStore: ProfileStore = ProfileStore.from(context) { error ->
        pt.tiago.neonline.crash.CrashLog.record(context, error)
    }


    private val assetSource = object : AssetSource {
        override fun readText(path: String): String? = runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private val catalogIndex = object : pt.tiago.neonline.data.CatalogIndexStore {
        private val dir = context.noBackupFilesDir
        // A new install (or update) of the app changes this, so its index is rebuilt once.
        override val buildId: String by lazy {
            val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
            "${BuildConfig.VERSION_CODE}:$installed"
        }
        override fun read(name: String): String? = java.io.File(dir, name).takeIf { it.isFile }?.readText()
        override fun write(name: String, text: String) {
            val file = java.io.File(dir, name)
            val temporary = java.io.File(dir, "$name.tmp")
            temporary.writeText(text)
            check(temporary.renameTo(file)) { "Cannot replace ${file.name}" }
        }
    }

    val catalog = CatalogRepository(assetSource, catalogIndex)

    @Volatile private var warm = false

    /** True once [warmUp] has run; later activities skip the loading screen. */
    fun isWarm(): Boolean = warm

    /** Loads and verifies the catalog and reads the profile; call off the main thread. */
    fun warmUp() {
        catalog.load()
        profile()
        // The home screen checks the saved match; decoding its level here keeps that off the main thread.
        resumableMatch()
        warm = true
    }

    /** Map and rival metadata for the campaign, rivals and free play screens; call off the main thread. */
    fun prefetch() {
        catalog.campaignLevels()
        catalog.rivalLevels()
    }

    fun profile(): Profile = profileStore.reconcileAchievements()

    fun save(profile: Profile) = profileStore.save(profile)

    /** Saves the active match, or records its result and clears it in one profile write. */
    fun checkpoint(ordinal: Int, hintsAvailable: Int, saved: SavedMatch, outcome: LevelOutcome?, humanMoves: Int, rival: String): pt.tiago.neonline.data.CampaignUnlocks {
        val before = profile()
        val after = ProfileReducer.checkpoint(before, ordinal, saved, outcome, humanMoves, hintsAvailable - saved.hintsLeft, rival)
        save(after)
        val previous = before.achievements.map { it.id }.toSet()
        return pt.tiago.neonline.data.CampaignUnlocks(after.achievements.filter { it.id !in previous }.map { it.id },
            pt.tiago.neonline.data.BoardThemes.newlyUnlocked(before, after))
    }

    fun resumableMatch(saved: SavedMatch? = profile().savedMatch): Pair<Int, SavedMatch>? {
        if (saved == null) return null
        val ordinal = catalog.load()?.levels?.firstOrNull { it.id == saved.levelId }?.ordinal ?: return null
        val (info, start) = levelState(ordinal, saved.difficulty) ?: return null
        val undos = if (saved.difficulty == Difficulty.EASY) 3 else 0
        if (info.kind == LevelKind.BOSS) {
            if (BossReplay.restore(info.bossRoundStates, saved, info.hints, undos) == null) return null
        } else {
            if (saved.bossHistory.isNotEmpty()) return null
            val restored = if (info.kind == LevelKind.DEFENSE)
                MatchReplay.restoreDefense(start, saved, info.hints, undos, info.surviveAiMoves ?: return null)
            else MatchReplay.restore(start, saved, info.hints, undos)
            if (restored == null) return null
        }
        return ordinal to saved
    }

    fun resumableFreePlay(saved: pt.tiago.neonline.data.SavedFreePlay? = profile().savedFreePlay): pt.tiago.neonline.data.SavedFreePlay? =
        saved?.takeIf { pt.tiago.neonline.data.FreePlay.restore(it) != null }

    fun resumableTournament(saved: pt.tiago.neonline.data.SavedTournament? = profile().savedTournament): pt.tiago.neonline.data.SavedTournament? {
        if (saved == null || !pt.tiago.neonline.data.Tournament.isValid(saved)) return null
        val match = saved.currentMatch ?: return saved
        val config = runCatching { pt.tiago.neonline.data.Tournament.roundConfig(saved) }.getOrNull() ?: return null
        if (pt.tiago.neonline.game.MatchReplay.restore(config.initial(), match, 0, 0) == null) return null
        return saved
    }

    fun dailyEntry(date: String, difficulty: Difficulty): pt.tiago.neonline.core.content.DailyEntryDto? {
        val pool = catalog.dailyPool() ?: return null
        val profile = profile()
        val active = resumableDaily(profile.savedDaily)?.takeIf { it.date == date && it.match.difficulty == difficulty }
        if (active != null) {
            val reference = catalog.load()?.levels?.firstOrNull { it.id == active.match.levelId && it.sha256 == active.levelSha256 } ?: return null
            return pt.tiago.neonline.core.content.DailyEntryDto(reference.id, reference.ordinal, difficultyKey(difficulty), reference.sha256)
        }
        val record = profile.dailyHistory.firstOrNull { it.date == date && it.difficulty == difficulty }
        if (record != null) {
            val reference = catalog.load()?.levels?.firstOrNull { it.id == record.levelId && it.sha256 == record.levelSha256 } ?: return null
            val info = levelInfo(reference.ordinal, difficulty) ?: return null
            if (info.kind != LevelKind.PUZZLE) return null
            return pt.tiago.neonline.core.content.DailyEntryDto(record.levelId, reference.ordinal, difficultyKey(difficulty), record.levelSha256)
        }
        return pt.tiago.neonline.data.DailyChallenge.choose(pool, date, difficulty)
    }

    fun resumableDaily(saved: pt.tiago.neonline.data.SavedDaily? = profile().savedDaily): pt.tiago.neonline.data.SavedDaily? {
        if (saved == null) return null
        val entry = catalog.load()?.levels?.firstOrNull { it.id == saved.match.levelId && it.sha256 == saved.levelSha256 } ?: return null
        val (info, start) = levelState(entry.ordinal, saved.match.difficulty) ?: return null
        if (info.kind != LevelKind.PUZZLE || MatchReplay.restore(start, saved.match, info.hints, if (saved.match.difficulty == Difficulty.EASY) 3 else 0) == null) return null
        return saved
    }

    fun checkpointDaily(date: String, poolVersion: Int, sha: String, match: SavedMatch, outcome: LevelOutcome?) {
        save(pt.tiago.neonline.data.DailyChallenge.checkpoint(profile(), pt.tiago.neonline.data.SavedDaily(date, poolVersion, sha, match), outcome))
    }

    /** The level at [ordinal], or null when it is not in the packaged catalog. */
    fun levelInfo(ordinal: Int, difficulty: Difficulty): LevelInfo? {
        val catalogModel = catalog.load() ?: return null
        val entry = catalog.levelAt(ordinal, catalogModel) ?: return null
        val level = catalog.level(entry.path) ?: return null
        val variant = level.variants[difficultyKey(difficulty)] ?: return null
        return level.toInfo(variant, difficulty)
    }

    /** The engine state for one level variant, straight from the exported JSON. */
    fun levelState(ordinal: Int, difficulty: Difficulty): Pair<LevelInfo, GameState>? {
        val catalogModel = catalog.load() ?: return null
        val entry = catalog.levelAt(ordinal, catalogModel) ?: return null
        val level = catalog.level(entry.path) ?: return null
        val variant = level.variants[difficultyKey(difficulty)] ?: return null
        return level.toInfo(variant, difficulty) to variant.toState(level.id, difficulty)
    }

    fun rivalProfile(info: LevelInfo, difficulty: Difficulty): AiProfile {
        val variant = variantFor(info.ordinal, difficulty) ?: return AiProfile.EASY
        return AiProfile(
            maxDepth = variant.ai.maxDepth,
            nodeBudget = variant.ai.nodeBudget,
            attack = variant.ai.attack,
            defense = variant.ai.defense,
            center = variant.ai.center,
            trap = variant.ai.trap,
            blunderRate = variant.ai.blunderRate,
            mirror = variant.ai.mirror,
            prefersPop = variant.ai.prefersPop,
            blunderMayMiss = variant.ai.blunderMayMiss,
        )
    }

    private fun variantFor(ordinal: Int, difficulty: Difficulty): VariantDto? {
        val catalogModel = catalog.load() ?: return null
        val entry = catalog.levelAt(ordinal, catalogModel) ?: return null
        val level = catalog.level(entry.path) ?: return null
        return level.variants[difficultyKey(difficulty)]
    }

    private fun LevelFileDto.toInfo(variant: VariantDto, difficulty: Difficulty) = LevelInfo(
        id = id,
        bossRoundStates = if (variant.kindEnum() == LevelKind.BOSS) (0..2).map { variant.forBossRound(it).toState(id, difficulty) } else emptyList(),
        ordinal = ordinal,
        titleEn = titleEn,
        titlePt = titlePt,
        kind = variant.kindEnum(),
        winInMoves = variant.kind.winInMoves,
        surviveAiMoves = variant.kind.surviveAiMoves,
        rival = variant.rival,
        starsTwo = variant.stars.two,
        starsThree = variant.stars.three,
        width = variant.width,
        height = variant.height,
        connect = variant.connect,
        popOut = variant.rules.popOut,
        rotateEvery = variant.rules.rotateEvery,
        fadeAfter = variant.rules.fadeAfter,
        moveClockSec = variant.rules.moveClockSec,
        hints = variant.hints,
        tutorial = if (tutorial == null) null else catalog.readLevel("tutorials/$id/${difficultyKey(difficulty)}.json")?.let { text ->
            runCatching { json.decodeFromString(TutorialDto.serializer(), text) }.getOrNull()
        },
    )

    companion object {
        /** The exported catalog lives at this path in the assets. */
        const val CATALOG_ASSET = "levels/catalog.json"
    }
}

/** Asset manager helper used by the tests. */
fun AssetManager.readAssetText(path: String): String? = runCatching {
    open(path).bufferedReader().use { it.readText() }
}.getOrNull()

/** Objectives follow the level kind. */
fun LevelInfo.objective(): LevelObjective = LevelObjective(
    kind = kind,
    winInMoves = winInMoves,
    surviveAiMoves = surviveAiMoves,
    starsTwo = starsTwo,
    starsThree = starsThree,
)
