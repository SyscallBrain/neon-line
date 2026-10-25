package pt.tiago.neonline.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.FileOutputStream
import java.io.File

/**
 * The profile store: atomic writes with a recovery copy, and an unreadable profile is set aside
 * instead of being silently overwritten.
 *
 * The serialization itself is pure and tested on the JVM; only the file access needs a context.
 */
class ProfileStore(
    private val filesDir: File,
    /**
     * The app keeps the profile in memory and writes it on a background thread, so a move never
     * waits for the disk. Tests keep the default: every call goes to the files.
     */
    private val background: Boolean = false,
    private val onWriteError: (Throwable) -> Unit = {},
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var cached: Profile? = null
    private var lastReconciled: Profile? = null
    private var pending: Profile? = null
    private var writeScheduled = false
    /** Bumped by every save or replacement; a background write that is no longer the latest is dropped. */
    @Volatile private var generation = 0L
    private val fileLock = Any()
    private val writer by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "neonline-profile").apply { isDaemon = true } }
    }

    @Synchronized
    fun load(): Profile {
        cached?.let { return it }
        return readFromDisk().also { if (background) cached = it }
    }

    private fun readFromDisk(): Profile {
        val main = File(filesDir, PROFILE_FILE)
        val recovery = File(filesDir, RECOVERY_FILE)
        for (file in listOf(main, recovery)) {
            if (!file.isFile) continue
            val restored = runCatching { decode(file.readText()) }.getOrNull()
            if (restored != null) return restored
            // Preserve damaged data before trying the independent recovery copy.
            check(file.renameTo(File(filesDir, "$CORRUPT_PREFIX${System.nanoTime()}.json"))) {
                "Cannot preserve damaged profile"
            }
        }
        return Profile()
    }

    /** Backfill only milestones justified by stored results; ordinary reads stay idempotent. */
    @Synchronized
    fun reconcileAchievements(): Profile {
        val previous = load()
        // The in-memory profile was already reconciled; skip the full achievements pass.
        if (previous === lastReconciled) return previous
        val updated = ProfileAchievements.reconcile(previous)
        if (updated != previous) save(updated)
        val result = if (background) load() else updated
        if (background) lastReconciled = result
        return result
    }

    @Synchronized
    fun save(profile: Profile) {
        val stamped = profile.copy(contentVersion = CURRENT_CONTENT_VERSION, engineVersion = CURRENT_ENGINE_VERSION)
        if (!background) {
            writeFiles(json.encodeToString(Profile.serializer(), stamped))
            return
        }
        cached = stamped
        generation++
        pending = stamped
        if (!writeScheduled) {
            writeScheduled = true
            writer.execute(::drainPendingWrite)
        }
    }

    /** Blocks until the latest saved profile is on disk; called when the app goes to the background. */
    fun flush() {
        if (!background) return
        runCatching { writer.submit {}.get() }
    }

    private fun drainPendingWrite() {
        val (profile, expected) = synchronized(this) {
            writeScheduled = false
            (pending ?: return) to generation.also { pending = null }
        }
        val text = json.encodeToString(Profile.serializer(), profile)
        synchronized(fileLock) {
            // A newer save, an import or a reset came after this one; do not overwrite it with older data.
            if (generation != expected) return
            runCatching { writeFiles(text) }.onFailure(onWriteError)
        }
    }

    private fun writeFiles(text: String) {
        filesDir.mkdirs()
        atomicWrite(File(filesDir, PROFILE_FILE), File(filesDir, TEMP_FILE), text)
        atomicWrite(File(filesDir, RECOVERY_FILE), File(filesDir, "$RECOVERY_FILE.tmp"), text)
    }

    /** Preserve the pre-import profile before any replacement. Never called during preview. */
    @Synchronized
    fun importProfile(profile: Profile) {
        require(ProfileBackup.isValid(profile))
        replaceProfile(profile, BEFORE_IMPORT_FILE)
    }

    /** Reset only campaign data. Read settings at confirmation, not when the dialog opened. */
    @Synchronized
    fun resetProgress(): Profile {
        val current = load()
        val fresh = Profile(settings = current.settings, localSession = current.localSession, savedDaily = current.savedDaily, dailyHistory = current.dailyHistory, savedFreePlay = current.savedFreePlay, freePlayStats = current.freePlayStats, freePlayPeeks = current.freePlayPeeks, rushBest = current.rushBest,
            achievements = current.achievements.filter { it.id in RushAchievements.ids || it.id == TournamentAchievements.ID },
            tournamentBestRounds = current.tournamentBestRounds)
        return replaceProfile(fresh, BEFORE_RESET_FILE)
    }

    private fun replaceProfile(profile: Profile, preservedFile: String): Profile = synchronized(fileLock) {
        // Any queued background write is older than this replacement and must not land after it.
        generation++
        pending = null
        val previous = load()
        filesDir.mkdirs()
        atomicWrite(File(filesDir, preservedFile), File(filesDir, "$preservedFile.tmp"),
            json.encodeToString(Profile.serializer(), previous))
        val restored = ProfileAchievements.reconcile(profile.copy(
            revision = maxOf(previous.revision, profile.revision).coerceAtMost(Long.MAX_VALUE - 1) + 1,
            contentVersion = CURRENT_CONTENT_VERSION, engineVersion = CURRENT_ENGINE_VERSION), advanceRevision = false)
        val text = json.encodeToString(Profile.serializer(), restored)
        // Commit the main file last. A failed commit leaves the original main profile intact.
        atomicWrite(File(filesDir, RECOVERY_FILE), File(filesDir, "$RECOVERY_FILE.tmp"), text)
        try {
            atomicWrite(File(filesDir, PROFILE_FILE), File(filesDir, TEMP_FILE), text)
        } catch (error: Exception) {
            runCatching { atomicWrite(File(filesDir, RECOVERY_FILE), File(filesDir, "$RECOVERY_FILE.tmp"),
                json.encodeToString(Profile.serializer(), previous)) }
            throw error
        }
        if (background) { cached = restored; lastReconciled = null }
        restored
    }

    private fun atomicWrite(target: File, temporary: File, text: String) {
        FileOutputStream(temporary).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(temporary.renameTo(target)) { "Cannot atomically replace ${target.name}" }
    }

    fun decode(text: String): Profile? = runCatching {
        val profile = json.decodeFromString(Profile.serializer(), text)
        if (Validator.isValid(profile)) profile else null
    }.getOrNull()

    companion object {
        const val BEFORE_RESET_FILE = "neonline-profile-before-reset.json"
        const val BEFORE_IMPORT_FILE = "neonline-profile-before-import.json"
        const val PROFILE_FILE = "neonline-profile.json"
        const val TEMP_FILE = "neonline-profile.tmp"
        const val RECOVERY_FILE = "neonline-profile.recovery.json"
        const val CORRUPT_PREFIX = "neonline-profile-corrupt-"

        fun from(context: Context, onWriteError: (Throwable) -> Unit = {}): ProfileStore =
            ProfileStore(context.filesDir, background = true, onWriteError = onWriteError)
    }
}

/** Structural rules a stored profile must satisfy. */
object Validator {
    fun isValid(profile: Profile): Boolean {
        if (profile.schemaVersion > CURRENT_PROFILE_SCHEMA) return false
        if (profile.contentVersion > CURRENT_CONTENT_VERSION) return false
        if (profile.engineVersion > CURRENT_ENGINE_VERSION) return false
        if (profile.revision < 0) return false
        if (!RivalGallery.roster.map { it.id }.containsAll(profile.rivalsBeaten)) return false
        if (listOfNotNull(profile.savedMatch, profile.savedDaily?.match, profile.savedFreePlay?.match, profile.savedTournament?.currentMatch).any { it.elapsedMs < 0L || it.peekCount !in 0..pt.tiago.neonline.game.PeekVisibility.MAX_COUNT }) return false
        if (profile.localSession?.isValid() == false || !DailyChallenge.isValid(profile) || !RivalGallery.isValid(profile) || !FreePlay.isValid(profile) || !PuzzleRush.isValid(profile) || !Tournament.isValid(profile)) return false
        if (!profile.settings.soundVolume.isFinite() || profile.settings.soundVolume !in 0f..1f) return false
        if (profile.settings.aiSpeed !in setOf("normal", "fast")) return false
        for ((difficulty, progress) in profile.progressByDifficulty) {
            if (progress.highestReached !in 0..CAMPAIGN_LEVELS) return false
            if (progress.bestByVariantId.any { (_, best) -> best.stars !in 0..3 }) return false
        }
        for (stats in profile.statsByDifficulty.values) {
            if (stats.wins < 0 || stats.losses < 0 || stats.draws < 0 || stats.moves < 0 || stats.hintsUsed < 0 || stats.timeMs < 0 || stats.decisiveMovesReviewed < 0) return false
        }
        return true
    }
}
