package pt.tiago.neonline.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.security.MessageDigest

/** Portable, bounded backup with an integrity checksum over the exact profile payload. */
object ProfileBackup {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    private data class Envelope(val app: String, val version: Int, val payload: String, val sha256: String)

    fun encode(profile: Profile): ByteArray {
        require(isValid(profile))
        val payload = json.encodeToString(Profile.serializer(), profile)
        val bytes = json.encodeToString(Envelope.serializer(), Envelope("NeonLine", 1, payload, hash(payload))).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        return bytes
    }

    fun read(input: InputStream): Profile {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_BYTES) { "Backup too large" }
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): Profile {
        require(bytes.size <= MAX_BYTES)
        val envelope = json.decodeFromString(Envelope.serializer(), bytes.toString(Charsets.UTF_8))
        require(envelope.app == "NeonLine" && envelope.version == 1)
        require(envelope.sha256 == hash(envelope.payload)) { "Damaged backup" }
        val profile = json.decodeFromString(Profile.serializer(), envelope.payload)
        require(isValid(profile)) { "Invalid profile" }
        return profile
    }

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    internal fun isValid(profile: Profile): Boolean {
        if (!Validator.isValid(profile) || profile.revision == Long.MAX_VALUE) return false
        if (profile.schemaVersion !in 1..CURRENT_PROFILE_SCHEMA || profile.contentVersion !in 1..CURRENT_CONTENT_VERSION || profile.engineVersion !in 1..CURRENT_ENGINE_VERSION) return false
        for ((difficulty, progress) in profile.progressByDifficulty) {
            val allowed = (1..CAMPAIGN_LEVELS).map { ProfileReducer.variantId(it, difficulty) }.toSet()
            if (!allowed.containsAll(progress.completedVariantIds) || !allowed.containsAll(progress.bestByVariantId.keys)) return false
            if (progress.bestByVariantId.values.any { (it.fewestMoves ?: 0) < 0 || it.hintsUsed < 0 }) return false
        }
        if (profile.achievements.any { it.unlockedAtRevision < 0 }) return false
        // A saved journal is untrusted until replayed by AppContainer, independently of this checksum.
        val saved = profile.savedMatch ?: return true
        return saved.steps.size <= 10000 && saved.bossHistory.size <= 5 && saved.bossHistory.all { it.steps.size <= 10000 }
    }
}
