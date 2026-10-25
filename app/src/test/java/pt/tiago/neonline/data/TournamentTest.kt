package pt.tiago.neonline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.game.MatchReplay

class TournamentTest {
    private val defeated = RivalGallery.roster.take(9).map { it.id }.toSet()
    private val selected = listOf("diva", "flamingo", "centrifuge", "sparky", "skater", "strike", "projectionist", "dolores")
    private val rules = listOf(FreePlayRules(), FreePlayRules(popOut = true), FreePlayRules(rotation = true))

    @Test fun `creation validates selection and orders rivals weak to strong`() {
        val tournament = Tournament.create(selected, defeated, Difficulty.HARD, "0000000000000001", rules)

        assertEquals(8, tournament.bracket.size)
        assertEquals(tournament.bracket.sortedBy { id -> RivalGallery.roster.indexOfFirst { rival -> rival.id == id } }, tournament.bracket)
        assertThrows(IllegalArgumentException::class.java) {
            Tournament.create(selected.dropLast(1), defeated, Difficulty.HARD, "0000000000000001", rules)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Tournament.create(selected, defeated - "diva", Difficulty.HARD, "0000000000000001", rules)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Tournament.create(selected + selected.first(), defeated, Difficulty.HARD, "0000000000000001", rules)
        }
    }

    @Test fun `seed fixes round rules and starter alternates`() {
        val first = Tournament.create(selected, defeated, Difficulty.MEDIUM, "1234567890abcdef", rules)
        val second = Tournament.create(selected, defeated, Difficulty.MEDIUM, "1234567890abcdef", rules)

        assertEquals(first.rounds, second.rounds)
        assertEquals(8, first.rounds.size)
        assertTrue(first.rounds.withIndex().all { (index, round) -> round.starter == if (index % 2 == 0) Player.P1 else Player.P2 })
        assertTrue(first.rounds.all { it.rules in rules })
        assertEquals(first.rounds.first().seedHex, Tournament.roundConfig(first).seedHex)
    }

    @Test fun `unlocked rules follow campaign introduction stops`() {
        assertEquals(listOf(FreePlayRules()), Tournament.unlockedRules(Profile()))
        val reachedAtStopFive = Profile(progressByDifficulty = mapOf(
            Difficulty.HARD to DifficultyProgress(highestReached = 201),
        ))
        assertTrue(Tournament.unlockedRules(reachedAtStopFive).contains(FreePlayRules(popOut = true)))
        assertTrue(Tournament.unlockedRules(reachedAtStopFive).none { it.rotation || it.specials || it.connect == 5 })
    }

    @Test fun `first draw replays and second draw ends tournament`() {
        val initial = Tournament.create(selected, defeated, Difficulty.EASY, "0000000000000002", rules)
        val replay = Tournament.resolve(initial, TournamentOutcome.DRAW)

        assertEquals(TournamentStatus.ACTIVE, replay.status)
        assertEquals(initial.roundIndex, replay.roundIndex)
        assertEquals(initial.rounds, replay.rounds)
        assertTrue(replay.drawReplayUsed)

        val finished = Tournament.resolve(replay, TournamentOutcome.DRAW)
        assertEquals(TournamentStatus.LOST, finished.status)
        assertEquals(initial.roundIndex, finished.roundIndex)
    }

    @Test fun `wins advance and a loss terminates`() {
        val initial = Tournament.create(selected, defeated, Difficulty.EASY, "0000000000000003", rules)
        val next = Tournament.resolve(initial, TournamentOutcome.WIN)

        assertEquals(TournamentStatus.ACTIVE, next.status)
        assertEquals(1, next.roundIndex)
        assertEquals(false, next.drawReplayUsed)

        val finished = Tournament.resolve(next, TournamentOutcome.LOSS)
        assertEquals(TournamentStatus.LOST, finished.status)
    }

    @Test fun `round index cannot advance without a contiguous win journal`() {
        val initial = Tournament.create(selected, defeated, Difficulty.EASY, "0000000000000013", rules)
        val forged = initial.copy(roundIndex = 7)

        assertFalse(Tournament.isValid(forged))
        assertThrows(IllegalArgumentException::class.java) { Tournament.finish(Profile(), forged) }
    }

    @Test fun `last win completes tournament`() {
        var tournament = Tournament.create(selected, defeated, Difficulty.EASY, "0000000000000004", rules)
        repeat(7) { tournament = Tournament.resolve(tournament, TournamentOutcome.WIN) }

        val finished = Tournament.resolve(tournament, TournamentOutcome.WIN)
        assertEquals(TournamentStatus.WON, finished.status)
        assertEquals(8, finished.roundIndex)
    }

    @Test fun `defeated rivals derive only from completed campaign bosses`() {
        val bossLevels = RivalGallery.roster.take(8).mapIndexed { index, rival ->
            RivalLevel("level-${index + 1}", index + 1, Difficulty.EASY, rival.id, boss = true)
        }
        val profile = bossLevels.take(7).fold(Profile()) { current, level ->
            ProfileReducer.recordWin(current, level.ordinal, level.difficulty, 1, 10, 0)
        }

        assertEquals(7, Tournament.defeatedRivals(profile, bossLevels).size)
        val eighthWin = ProfileReducer.recordWin(profile, bossLevels.last().ordinal, Difficulty.EASY, 1, 10, 0)
        assertEquals(8, Tournament.defeatedRivals(eighthWin, bossLevels).size)
        assertEquals(0, Tournament.defeatedRivals(eighthWin, bossLevels.map { it.copy(boss = false) }).size)
    }

    @Test fun `finished tournament stores best rounds cleared`() {
        val tournament = Tournament.resolve(
            Tournament.resolve(Tournament.create(selected, defeated, Difficulty.HARD, "0000000000000005", rules), TournamentOutcome.WIN),
            TournamentOutcome.LOSS,
        )

        val finished = Tournament.finish(Profile(savedTournament = tournament), tournament)
        assertEquals(1, finished.tournamentBestRounds[Difficulty.HARD])
        assertEquals(null, finished.savedTournament)
        assertEquals(finished, Tournament.finish(finished, tournament))
    }

    @Test fun `stale terminal result cannot clear a different saved tournament`() {
        val oldTerminal = Tournament.resolve(
            Tournament.create(selected, defeated, Difficulty.HARD, "0000000000000009", rules),
            TournamentOutcome.LOSS,
        )
        val active = Tournament.create(selected, defeated, Difficulty.EASY, "000000000000000a", rules)
        val profile = Profile(savedTournament = active)

        assertThrows(IllegalArgumentException::class.java) {
            Tournament.finish(profile, oldTerminal)
        }
        assertEquals(active, profile.savedTournament)
    }

    @Test fun `stale checkpoint cannot replace a different saved tournament`() {
        val oldTournament = Tournament.create(selected, defeated, Difficulty.HARD, "000000000000000b", rules)
        val active = Tournament.create(selected, defeated, Difficulty.EASY, "000000000000000c", rules)
        val staleCheckpoint = oldTournament.copy(
            currentMatch = SavedMatch("free-play", Difficulty.HARD),
        )
        val profile = Profile(savedTournament = active)

        assertThrows(IllegalArgumentException::class.java) {
            Tournament.checkpoint(profile, staleCheckpoint)
        }
        assertEquals(active, profile.savedTournament)
    }

    @Test fun `active round checkpoint restores against its fixed config`() {
        val tournament = Tournament.create(selected, defeated, Difficulty.MEDIUM, "0000000000000006", rules)
        val config = Tournament.roundConfig(tournament)
        val initial = config.initial()
        val match = SavedMatch(
            levelId = initial.levelId,
            difficulty = config.difficulty,
            initialHash = StateHasher.hash(initial),
            stateHash = StateHasher.hash(initial),
            hintsLeft = 0,
            undosLeft = 0,
            clockMs = initial.moveClockSec?.times(1000L),
        )
        val profile = Tournament.checkpoint(
            Profile(rivalsBeaten = tournament.bracket.toSet(), savedTournament = tournament),
            tournament.copy(currentMatch = match),
        )

        assertNotNull(profile.savedTournament)
        assertNotNull(MatchReplay.restore(config.initial(), profile.savedTournament!!.currentMatch!!, 0, 0))
        val corrupted = match.copy(stateHash = "corrupt")
        assertNull(MatchReplay.restore(config.initial(), corrupted, 0, 0))
    }

    @Test fun `profile validation rejects unearned bracket rivals and rules`() {
        val tournament = Tournament.create(selected, defeated, Difficulty.MEDIUM, "0000000000000007", rules)
        assertFalse(Tournament.isValid(Profile(savedTournament = tournament, rivalsBeaten = selected.dropLast(1).toSet())))

        val lockedRules = Tournament.create(selected, defeated, Difficulty.MEDIUM, "0000000000000008", listOf(FreePlayRules(), FreePlayRules(popOut = true)))
        assertFalse(Tournament.isValid(Profile(savedTournament = lockedRules, rivalsBeaten = selected.toSet())))
    }
}
