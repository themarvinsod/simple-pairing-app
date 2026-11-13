package com.example.pairingapp.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.example.pairingapp.engine.TimerCore

/**
 * Drives a [TimerCore] with a UI-thread ticker for the on-screen clock. The actual time-out ringing
 * is done by an AlarmManager alarm ([TimerAlarm]) so it fires even when the app is backgrounded.
 * [onChange] fires whenever the clock or alarm state changes.
 */
class RoundTimer(ctx: Context, private val onChange: () -> Unit) {
    private val app = ctx.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var key = ""

    var core: TimerCore? = null
        private set
    val ringing get() = AlarmPlayer.ringing

    private val listener: () -> Unit = { onChange() }

    init { AlarmPlayer.listener = listener }

    // Keeps the CPU awake so the on-screen clock keeps ticking with the screen off.
    private val wakeLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pairing:roundtimer").apply { setReferenceCounted(false) }

    private fun syncWakeLock() {
        val c = core
        if (c != null && c.running) wakeLock.acquire(c.remainingMs() + 30_000L)
        else if (wakeLock.isHeld) wakeLock.release()
    }

    /** One pending alarm per running clock: replaced on start/resume, cancelled on pause/reset. */
    private var alarmOk = true // false => AlarmManager refused, so the in-app ticker must ring

    private fun syncAlarm() {
        val c = core
        if (c != null && c.running) {
            alarmOk = TimerAlarm.schedule(
                app, System.currentTimeMillis() + c.remainingMs(),
                if (c.phase == TimerCore.Phase.ROUND) c.extraMs else 0L
            )
        } else {
            alarmOk = true
            TimerAlarm.cancel(app)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val c = core ?: return
            // Normally the AlarmManager receiver rings (and chains the extra-time alarm); this only
            // updates state. If scheduling was refused, ring from here instead.
            if (c.tick()) {
                syncWakeLock()
                if (!alarmOk) AlarmPlayer.ring(app)
            }
            onChange()
            if (c.running) handler.postDelayed(this, 250)
        }
    }

    val enabled get() = core != null

    /** Bind to a tournament's timer settings; a different tournament or settings starts fresh. */
    fun bind(id: String, roundMinutes: Int, extraMinutes: Int) {
        val k = "$id/$roundMinutes/$extraMinutes"
        if (k == key) return
        release()
        key = k
        core = if (roundMinutes > 0) TimerCore(roundMinutes * 60_000L, extraMinutes * 60_000L) else null
    }

    fun toggle() {
        val c = core ?: return
        AlarmPlayer.stop()
        handler.removeCallbacks(tick)
        if (c.running) c.pause() else { c.start(); handler.post(tick) }
        syncWakeLock()
        syncAlarm()
        onChange()
    }

    fun reset() {
        core?.reset()
        handler.removeCallbacks(tick)
        AlarmPlayer.stop()
        syncWakeLock()
        syncAlarm()
        onChange()
    }

    fun silence() = AlarmPlayer.stop()

    /** Drop the clock completely, including its pending alarm. */
    fun release() {
        handler.removeCallbacks(tick)
        if (wakeLock.isHeld) wakeLock.release()
        TimerAlarm.cancel(app)
        AlarmPlayer.stop()
    }

    /** Activity going away: stop UI work but leave any scheduled alarm so it still rings. */
    fun detach() {
        handler.removeCallbacks(tick)
        if (wakeLock.isHeld) wakeLock.release()
        if (AlarmPlayer.listener === listener) AlarmPlayer.listener = null // never clear another instance's listener
    }
}
