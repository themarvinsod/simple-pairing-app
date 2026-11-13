package com.example.pairingapp

import com.example.pairingapp.engine.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class EngineTest {
    private fun players(n: Int) = (1..n).map { Player("p$it", "Player $it") }

    private fun tournament(n: Int, stages: List<StageConfig>, seed: Int = 1, pts: PointSystem = PointSystem()) =
        Tournament("t", "T", pts, stages, players(n), rng = Random(seed)).also { it.start() }

    /** Reports random results for the round and advances until finished. */
    private fun playOut(t: Tournament, rnd: Random = Random(7), pick: ((Match) -> Result)? = null) {
        var guard = 0
        while (t.status == Status.ACTIVE && guard++ < 200) {
            for (m in t.roundMatches(t.currentRound)) {
                if (m.isBye) continue
                val r = pick?.invoke(m) ?: if (m.bracket == Bracket.SWISS)
                    listOf(Result.P1_WIN, Result.P2_WIN, Result.DRAW, Result.DOUBLE_LOSS).random(rnd)
                else listOf(Result.P1_WIN, Result.P2_WIN).random(rnd)
                assertTrue(t.setResult(m, r))
            }
            assertTrue(t.advance())
        }
        assertEquals(Status.FINISHED, t.status)
    }

    @Test fun swissNoRematchesAndEveryonePaired() {
        val t = tournament(8, listOf(StageConfig(StageType.SWISS, 5)))
        playOut(t)
        val seen = mutableSetOf<String>()
        for (m in t.matches.filter { !it.isBye }) assertTrue("rematch", seen.add(SwissPairer.key(m.p1, m.p2!!)))
        for (r in 1..5) assertEquals(8, t.roundMatches(r).sumOf { if (it.isBye) 1 else 2 as Int })
    }

    @Test fun swissOddFieldGivesEachPlayerAtMostOneBye() {
        val t = tournament(5, listOf(StageConfig(StageType.SWISS, 3)))
        playOut(t)
        val byes = t.matches.filter { it.isBye }.map { it.p1 }
        assertEquals(3, byes.size)
        assertEquals(3, byes.toSet().size)
    }

    @Test fun singleElimWithByesCrownsOneChampion() {
        for (n in listOf(2, 3, 5, 6, 8, 11)) {
            val t = tournament(n, listOf(StageConfig(StageType.SINGLE_ELIM)), seed = n)
            playOut(t)
            assertEquals("n=$n", n - 1, t.matches.count { !it.isBye })
            val s = t.standings()
            assertEquals("Champion", s[0].note)
            assertEquals(1, s.count { it.note == "Champion" })
        }
    }

    @Test fun doubleElimFinishesAndEveryoneLosesTwiceExceptChampion() {
        for (n in listOf(2, 3, 4, 5, 7, 8, 13)) {
            val t = tournament(n, listOf(StageConfig(StageType.DOUBLE_ELIM)), seed = n)
            playOut(t, Random(n))
            val losses = HashMap<String, Int>()
            for (m in t.matches) if (!m.isBye) m.loser()?.let { losses[it] = (losses[it] ?: 0) + 1 }
            val champ = t.standings()[0]
            assertEquals("Champion", champ.note)
            for (p in t.players) if (p.id != champ.player.id) assertEquals("n=$n ${p.name}", 2, losses[p.id] ?: 0)
        }
    }

    @Test fun doubleElimBracketResetWhenLosersChampionWinsFirstFinal() {
        val t = tournament(4, listOf(StageConfig(StageType.DOUBLE_ELIM)))
        playOut(t, pick = { m -> if (m.bracket == Bracket.FINAL) Result.P2_WIN else Result.P1_WIN })
        assertTrue(t.matches.any { it.bracket == Bracket.RESET })
    }

    @Test fun stageResultsAreFrozenAtTheEndOfEachStage() {
        val t = tournament(10, listOf(StageConfig(StageType.SWISS, 3), StageConfig(StageType.SINGLE_ELIM, 0, 4)))
        playOut(t)
        val results = t.stageResults()
        assertEquals(2, results.size)
        assertTrue(results[0].label.startsWith("Stage 1: Swiss"))
        assertTrue(results[1].label.startsWith("Stage 2: Single elimination"))
        // Swiss result predates the top cut: no champion yet, everyone listed
        assertEquals(10, results[0].standings.size)
        assertTrue(results[0].standings.none { it.note == "Champion" })
        // Final result has the champion on top
        assertEquals("Champion", results[1].standings[0].note)
    }

    @Test fun singleStageTournamentHasOneResult() {
        val t = tournament(6, listOf(StageConfig(StageType.SWISS, 2)))
        playOut(t)
        assertEquals(1, t.stageResults().size)
    }

    @Test fun mixedSwissIntoTopCut() {
        val t = tournament(10, listOf(StageConfig(StageType.SWISS, 4), StageConfig(StageType.SINGLE_ELIM, 0, 4)))
        playOut(t)
        assertEquals(4, t.matches.count { it.stage == 1 && it.round == t.matches.first { m -> m.stage == 1 }.round } * 2)
        val s = t.standings()
        assertEquals("Champion", s[0].note)
        // top-cut participants rank above everyone else
        assertTrue(s.take(4).all { it.note == "Champion" || it.note == "Out" })
        assertTrue(s.drop(4).all { it.note.isEmpty() })
    }

    @Test fun customPointsAndTiebreakers() {
        val pts = PointSystem(win = 5, draw = 2, loss = -1, doubleLoss = -3, bye = 4)
        val t = tournament(3, listOf(StageConfig(StageType.SWISS, 1)), pts = pts)
        val real = t.matches.first { !it.isBye }
        val bye = t.matches.first { it.isBye }
        t.setResult(real, Result.DOUBLE_LOSS)
        val s = t.standings().associateBy { it.player.id }
        assertEquals(4, s[bye.p1]!!.points)
        assertEquals(-3, s[real.p1]!!.points)
        assertEquals(-3, s[real.p2]!!.points)
        assertEquals(1, s[bye.p1]!!.byes)
        assertEquals(1, s[real.p1]!!.losses)
        assertEquals(bye.p1, t.standings()[0].player.id)
    }

    @Test fun matchWinPercentagesUseFloorAndOpponentAverages() {
        val t = tournament(2, listOf(StageConfig(StageType.SWISS, 2)))
        val m = t.matches.first()
        t.setResult(m, Result.P1_WIN)
        t.advance()
        // 2 players can only rematch; second round draw
        t.setResult(t.matches.last(), Result.DRAW)
        val w = t.standings().first { it.player.id == m.p1 }
        val l = t.standings().first { it.player.id == m.p2 }
        assertEquals((1 + 1 / 3.0) / 2, w.matchWinPct, 1e-9)
        // loser: 0 wins + 1 draw over 2 rounds = 0.167, which must be lifted to the 0.33 floor
        assertEquals(0.33, l.matchWinPct, 1e-9)
        assertEquals(l.matchWinPct, w.oppMatchWinPct, 1e-9)
    }

    @Test fun reinstatedPlayerIsPairedAgain() {
        val t = tournament(6, listOf(StageConfig(StageType.SWISS, 4)))
        for (m in t.roundMatches(1)) t.setResult(m, Result.P1_WIN)
        t.setDropped("p1", true)
        t.advance()
        assertTrue(t.roundMatches(2).none { it.p1 == "p1" || it.p2 == "p1" })
        for (m in t.roundMatches(2)) if (!m.isBye) t.setResult(m, Result.P1_WIN)
        t.setDropped("p1", false)
        t.advance()
        assertTrue(t.roundMatches(3).any { it.p1 == "p1" || it.p2 == "p1" })
    }

    @Test fun doubleElimWithSingleEntrantFinishes() {
        val t = tournament(1, listOf(StageConfig(StageType.DOUBLE_ELIM)))
        assertTrue(t.advance())
        assertEquals(Status.FINISHED, t.status)
        assertEquals("Champion", t.standings()[0].note)
    }

    @Test fun swissWithMoreRoundsThanPlayersStillPairsEveryone() {
        val t = tournament(2, listOf(StageConfig(StageType.SWISS, 3)))
        playOut(t)
        for (r in 1..3) assertEquals(1, t.roundMatches(r).count { !it.isBye })
    }

    @Test fun swissPairingOfLargeOddFieldIsFastAndRematchFree() {
        val t = tournament(27, listOf(StageConfig(StageType.SWISS, 5)))
        val start = System.nanoTime()
        playOut(t)
        assertTrue("pairing too slow", (System.nanoTime() - start) / 1_000_000 < 15_000)
        val seen = mutableSetOf<String>()
        for (m in t.matches.filter { !it.isBye }) assertTrue(seen.add(SwissPairer.key(m.p1, m.p2!!)))
    }

    @Test fun historicalStandingsAreFrozenInTime() {
        val t = tournament(4, listOf(StageConfig(StageType.SWISS, 3)))
        for (m in t.roundMatches(1)) t.setResult(m, Result.P1_WIN)
        t.advance()
        for (m in t.roundMatches(2)) t.setResult(m, Result.P1_WIN)
        assertEquals(2, t.standings(1).sumOf { it.wins })
        assertEquals(4, t.standings(2).sumOf { it.wins })
    }
}
