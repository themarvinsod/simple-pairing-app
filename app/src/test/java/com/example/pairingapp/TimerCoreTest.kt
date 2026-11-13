package com.example.pairingapp

import com.example.pairingapp.engine.TimerCore
import com.example.pairingapp.engine.TimerCore.Phase
import org.junit.Assert.*
import org.junit.Test

class TimerCoreTest {
    private var clock = 0L
    private fun core(round: Long, extra: Long) = TimerCore(round, extra) { clock }

    @Test fun countsDownThenTimesOutOnceWithoutExtraTime() {
        clock = 0
        val t = core(60_000, 0)
        assertEquals(Phase.IDLE, t.phase)
        assertEquals(60_000, t.remainingMs())
        t.start()
        clock = 59_000
        assertFalse(t.tick())
        assertEquals(1_000, t.remainingMs())
        clock = 60_000
        assertTrue(t.tick())
        assertEquals(Phase.DONE, t.phase)
        assertFalse(t.running)
        assertEquals(0, t.remainingMs())
        clock = 90_000
        assertFalse("must not ring twice", t.tick())
    }

    @Test fun extraTimeRestartsAutomaticallyAndRingsAgainAtZero() {
        clock = 0
        val t = core(60_000, 30_000)
        t.start()
        clock = 60_000
        assertTrue(t.tick())                       // first time-out
        assertEquals(Phase.EXTRA, t.phase)
        assertTrue(t.running)
        assertEquals(30_000, t.remainingMs())
        clock = 89_000
        assertFalse(t.tick())
        clock = 90_000
        assertTrue(t.tick())                       // second time-out
        assertEquals(Phase.DONE, t.phase)
        assertFalse(t.running)
    }

    @Test fun pauseKeepsRemainingTimeAndResumeContinues() {
        clock = 0
        val t = core(60_000, 0)
        t.start()
        clock = 20_000
        t.pause()
        clock = 500_000
        assertFalse(t.tick())
        assertEquals(40_000, t.remainingMs())
        t.start()
        clock = 540_000
        assertTrue(t.tick())
    }

    @Test fun resetReturnsToFullRoundTime() {
        clock = 0
        val t = core(60_000, 30_000)
        t.start()
        clock = 60_000
        t.tick()
        t.reset()
        assertEquals(Phase.IDLE, t.phase)
        assertFalse(t.running)
        assertEquals(60_000, t.remainingMs())
    }
}
