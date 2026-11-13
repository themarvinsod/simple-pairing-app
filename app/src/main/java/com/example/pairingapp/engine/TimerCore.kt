package com.example.pairingapp.engine

/**
 * Round countdown. Counts down [roundMs]; if [extraMs] > 0 it then restarts automatically with the
 * additional time. [tick] returns true each time a time-out happens so the caller can sound the alarm.
 * The clock is injectable so the logic can be unit tested.
 */
class TimerCore(
    val roundMs: Long,
    val extraMs: Long,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    enum class Phase { IDLE, ROUND, EXTRA, DONE }

    var phase = Phase.IDLE
        private set
    var running = false
        private set

    private var remaining = roundMs
    private var endAt = 0L

    fun remainingMs(): Long = if (running) maxOf(0L, endAt - now()) else remaining

    fun start() {
        if (running || phase == Phase.DONE) return
        if (phase == Phase.IDLE) { phase = Phase.ROUND; remaining = roundMs }
        endAt = now() + remaining
        running = true
    }

    fun pause() {
        if (!running) return
        remaining = maxOf(0L, endAt - now())
        running = false
    }

    fun reset() {
        running = false
        phase = Phase.IDLE
        remaining = roundMs
    }

    /** Call regularly while running. True = a time-out just happened (ring + buzz now). */
    fun tick(): Boolean {
        if (!running || now() < endAt) return false
        if (phase == Phase.ROUND && extraMs > 0) {
            phase = Phase.EXTRA
            remaining = extraMs
            endAt = now() + extraMs
        } else {
            phase = Phase.DONE
            running = false
            remaining = 0
        }
        return true
    }
}
