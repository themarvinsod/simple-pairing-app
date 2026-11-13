package com.example.pairingapp.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Time-outs are scheduled with AlarmManager.setAlarmClock so they fire on time even when the app
 * is in the background or the phone is idle. The receiver rings; if extra time is pending it also
 * schedules the follow-up alarm itself, so the chain works without the app being alive.
 */
object TimerAlarm {
    const val EXTRA_MS = "extraMs"

    private fun operation(ctx: Context, extraMs: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, TimerAlarmReceiver::class.java).putExtra(EXTRA_MS, extraMs),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Ring at [atMillis]; if [extraMs] > 0 the receiver then schedules a second alarm that far ahead. */
    fun schedule(ctx: Context, atMillis: Long, extraMs: Long): Boolean = runCatching {
        val app = ctx.applicationContext
        // Same intent as the launcher icon, so tapping the system's next-alarm entry brings the
        // existing task forward instead of starting a second MainActivity.
        // getLaunchIntentForPackage sets a package that launcher icons don't, so the intents would not
        // match; clear it and add SINGLE_TOP so the running MainActivity is reused in every case.
        val launch = (app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(app, MainActivity::class.java))
            .setPackage(null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val show = PendingIntent.getActivity(app, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), operation(app, extraMs))
    }.isSuccess // false if the platform refuses exact alarms; the caller then rings from inside the app

    fun cancel(ctx: Context) {
        runCatching {
            val app = ctx.applicationContext
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(operation(app, 0L))
        }
    }
}

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extraMs = intent.getLongExtra(TimerAlarm.EXTRA_MS, 0L)
        if (extraMs > 0) TimerAlarm.schedule(context, System.currentTimeMillis() + extraMs, 0L) // best effort
        val pending = goAsync() // keep the process alive while the ringtone plays
        AlarmPlayer.ring(context) { pending.finish() }
    }
}

/** Vibrates and plays the default alarm ringtone for a few seconds. Main thread only. */
object AlarmPlayer {
    private const val RING_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var done: (() -> Unit)? = null
    private val autoStop = Runnable { stop() }

    /** Called whenever ringing starts or stops so the UI can refresh. */
    var listener: (() -> Unit)? = null
    var ringing = false
        private set

    fun ring(ctx: Context, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        stop()
        done = onDone
        var p: MediaPlayer? = null
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            p = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }
            player = p
            ringing = true
            handler.postDelayed(autoStop, RING_MS)
        }.onFailure { runCatching { p?.release() }; player = null }
        vibrate(app)
        if (!ringing) finish() // nothing to wait for if the sound could not start
        listener?.invoke()
    }

    fun stop() {
        handler.removeCallbacks(autoStop)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        val wasRinging = ringing
        ringing = false
        finish()
        if (wasRinging) listener?.invoke()
    }

    private fun finish() {
        val d = done
        done = null
        d?.invoke()
    }

    private fun vibrate(ctx: Context) {
        runCatching {
            @Suppress("DEPRECATION")
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 600, 300, 600, 300, 900)
            // Alarm usage so the buzz is not dropped when the receiver runs with the app in the background.
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            @Suppress("DEPRECATION")
            when {
                Build.VERSION.SDK_INT >= 33 -> v.vibrate(
                    VibrationEffect.createWaveform(pattern, -1),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
                )
                Build.VERSION.SDK_INT >= 26 -> v.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs)
                else -> v.vibrate(pattern, -1, attrs)
            }
        }
    }
}
