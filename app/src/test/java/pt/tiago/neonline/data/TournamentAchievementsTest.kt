package pt.tiago.neonline.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty

class TournamentAchievementsTest {
    private val defeated = RivalGallery.roster.take(8).map { it.id }.toSet()

    private fun tournament(outcome: TournamentOutcome): SavedTournament {
        var state = Tournament.create(defeated.toList(), defeated, Difficulty.HARD, "0123456789abcdef", listOf(FreePlayRules()))
        repeat(7) { state = Tournament.resolve(state, TournamentOutcome.WIN) }
        return Tournament.resolve(state, outcome)
    }

    @Test fun `only a full tournament win unlocks one global achievement`() {
        val lostResult = tournament(TournamentOutcome.LOSS)
        val lost = Tournament.finish(Profile(savedTournament = lostResult), lostResult)
        assertFalse(TournamentAchievements.progress(lost).unlocked)

        val wonResult = tournament(TournamentOutcome.WIN)
        val won = Tournament.finish(Profile(savedTournament = wonResult), wonResult)
        assertTrue(TournamentAchievements.progress(won).unlocked)
        assertEquals(1, won.achievements.count { it.id == TournamentAchievements.ID })
        assertEquals(1L, won.revision)
        assertEquals(won, Tournament.finish(won, wonResult))
    }

    @Test fun `legacy complete best backfills badge once`() {
        val profile = Profile(revision = 4, tournamentBestRounds = mapOf(Difficulty.EASY to Tournament.ROUNDS))

        val reconciled = ProfileAchievements.reconcile(profile)

        assertTrue(TournamentAchievements.progress(reconciled).unlocked)
        assertEquals(5L, reconciled.revision)
        assertEquals(1, reconciled.achievements.count { it.id == TournamentAchievements.ID })
        assertEquals(reconciled, ProfileAchievements.reconcile(reconciled))
    }

    @Test fun `campaign reset preserves tournament best and badge`() {
        val dir = Files.createTempDirectory("tournament-achievement-reset").toFile()
        try {
            val profile = Profile(
                tournamentBestRounds = mapOf(Difficulty.MEDIUM to Tournament.ROUNDS),
                achievements = listOf(Achievement(TournamentAchievements.ID, 3)),
            )
            val store = ProfileStore(dir)
            store.save(profile)

            val reset = store.resetProgress()

            assertEquals(profile.tournamentBestRounds, reset.tournamentBestRounds)
            assertTrue(TournamentAchievements.progress(reset).unlocked)
        } finally {
            dir.deleteRecursively()
        }
    }
}
