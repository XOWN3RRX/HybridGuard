package com.hybridguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.hybridguard.data.Alarm
import com.hybridguard.data.AlarmLevel
import com.hybridguard.data.AlarmView
import com.hybridguard.data.Role
import com.hybridguard.data.shortLabel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** All notifications: the status one (live values) and the alarms. */
object Notifier {
    const val STATUS_ID = 1
    private const val ALARM_ID = 2
    private const val QUESTION_ID = 3
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_WAITING = "waiting"
    private const val CHANNEL_ALARM = "alarm"
    private const val CHANNEL_CAUTION = "caution"
    private const val CHANNEL_QUESTION = "question"
    const val ACTION_ACK = "com.hybridguard.app.ACK"
    const val ACTION_STOP = "com.hybridguard.app.STOP"
    const val ACTION_CHECK = "com.hybridguard.app.CHECK"


    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Live values", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The pumps and temperatures while driving, and what the app is waiting for"
                setShowBadge(false)
            },
        )
        // Its own channel so the owner can switch it off in Android (the monitor keeps
        // running; the live values and alarms use the other channels). Android shows a
        // foreground service's notification at LOW at least, so MIN only folds it.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WAITING, "Waiting for the car", NotificationManager.IMPORTANCE_MIN).apply {
                description = "What the app waits for while the car is off (quiet)"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUESTION, "Questions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Something the app needs to know about the car, e.g. its model year (quiet)"
                setSound(null, null)
                enableVibration(false)
            },
        )
        // Sound and vibration are played by the app itself, so the settings switches work.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "STOP alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A pump stopped or something is overheating: stop the car safely"
                setSound(null, null)
                enableVibration(false)
                enableLights(true)
                lightColor = Color.RED
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CAUTION, "Cautions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Something is not right: drive gently and keep an eye on it"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, ActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    // ---------------------------------------------------------------- status

    /** The ongoing notification: live values while monitoring, else what the app waits for. */
    fun status(context: Context, st: MonitorStatus, s: Settings): Notification {
        val waiting = st.phase != MonitorPhase.MONITORING || st.dashboard == null
        val b = NotificationCompat.Builder(context, if (waiting) CHANNEL_WAITING else CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(if (waiting) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop watching", broadcast(context, ACTION_STOP, 1))
        val dash = st.dashboard
        if (st.phase == MonitorPhase.MONITORING && dash != null) {
            val lines = s.notifRoles.map { role -> "${role.shortLabel}  ${valueText(role, dash.values[role], dash.units[role])}" }
            val top = st.alarms.top
            val title = when {
                top != null -> (if (top.level == AlarmLevel.STOP) "STOP: " else "Caution: ") + top.title
                dash.ready == true -> "READY · all OK"
                else -> "Monitoring · all OK"
            }
            val since = st.sessionStartEpochMs?.let { " · since " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }.orEmpty()
            b.setContentTitle(title)
                .setContentText(lines.take(3).joinToString(" · "))
                .setSubText("Monitoring$since")
                .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
            if (top != null) b.setColorized(true).setColor(if (top.level == AlarmLevel.STOP) Color.RED else Color.rgb(0xFF, 0xA0, 0x00))
        } else {
            val last = st.lastSession?.let { l ->
                val f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                "Last session ${f.format(Date(l.startEpochMs))}, " + (if (l.alarms.isEmpty()) "no alarms" else "${l.alarms.size} alarm(s)")
            }
            val volts = st.volts?.let { " · 12 V %.1f V".format(Locale.US, it) }.orEmpty()
            b.setContentTitle(st.phase.label + volts)
                .setContentText(st.message ?: last ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(st.message, last).joinToString("\n")))
                .addAction(0, "Check now", broadcast(context, ACTION_CHECK, 2))
        }
        return b.build()
    }

    /**
     * What the status notification shows, without the parts that change on every
     * reading while waiting: it is only posted again when this changes, so a
     * notification the user swiped away (Android 14+) does not keep coming back.
     */
    fun statusKey(st: MonitorStatus, s: Settings): String {
        val dash = st.dashboard
        return if (st.phase == MonitorPhase.MONITORING && dash != null) {
            "M|${st.alarms.top?.title}|${st.alarms.top?.level}|${s.notifRoles.joinToString { valueText(it, dash.values[it], dash.units[it]) }}"
        } else {
            "W|${st.phase}|${st.message}|${st.volts?.let { "%.1f".format(Locale.US, it) }}|${st.lastSession?.startEpochMs}|${st.lastSession?.alarms?.size}"
        }
    }

    fun valueText(role: Role, v: Double?, unit: String?): String {
        if (v == null) return "--"
        val digits = when {
            unit == "V" -> 1
            role == Role.SOC || unit == "°C" -> 0
            else -> 0
        }
        return String.format(Locale.US, "%.${digits}f", v) + (unit?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
    }

    // ---------------------------------------------------------------- questions

    /** The car's VIN does not give its model year: a quiet notification asking for it (answered in the app). */
    fun askModelYear(context: Context, profileName: String?, assumedYear: Int?) {
        val car = profileName ?: "car"
        val assumed = assumedYear?.let { " Until then the app assumes $it." }.orEmpty()
        val text = "Your $car's VIN does not say its model year, and some values depend on it.$assumed " +
            "Open the app when parked to choose it."
        val n = NotificationCompat.Builder(context, CHANNEL_QUESTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Which model year is your car?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(QUESTION_ID, n)
    }

    fun clearModelYearQuestion(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(QUESTION_ID)
    }

    // ---------------------------------------------------------------- alarms

    /** Shows (silently updates) the alarm notification for what is on now. */
    fun showAlarms(context: Context, view: AlarmView) {
        val top = view.top ?: return
        context.getSystemService(NotificationManager::class.java).notify(ALARM_ID, alarmNotification(context, top, view, alert = false))
    }

    fun alarmsAcknowledged(context: Context, view: AlarmView) {
        AlarmSound.stop()
        // Re-posted silently: a STOP alarm's full-screen banner stays pinned until the notification is replaced.
        context.getSystemService(NotificationManager::class.java).cancel(ALARM_ID)
        if (view.alarms.isNotEmpty()) showAlarms(context, view) else clearAlarms(context) // e.g. the test alarm
    }

    fun clearAlarms(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ALARM_ID)
        AlarmSound.stop()
    }

    /** A new or repeated alarm: heads-up (full screen for STOP), sound, vibration, voice. */
    fun announce(context: Context, alarm: Alarm, view: AlarmView, s: Settings) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(ALARM_ID, alarmNotification(context, alarm, view, alert = true))
        val stop = alarm.level == AlarmLevel.STOP
        if (s.alarmVibrate) vibrate(context, stop)
        val words = (if (stop) "Warning. " else "Caution. ") + alarm.title + ". " + alarm.advice
        AlarmSound.play(
            context,
            tone = s.alarmSound,
            loud = stop && s.loudStopAlarm,
            words = words.takeIf { s.alarmVoice },
            pauseMusic = s.pauseMusic,
            alarmVolume = if (stop) s.stopVolumePercent else 0,
            voiceVolume = if (stop) s.voiceVolumePercent else 0,
        )
    }

    private fun alarmNotification(context: Context, alarm: Alarm, view: AlarmView, alert: Boolean): Notification {
        val stop = alarm.level == AlarmLevel.STOP
        val others = view.alarms.drop(1).joinToString("\n") { "• " + it.title }
        val text = listOf(alarm.advice, alarm.detail, others).filter { it.isNotBlank() }.joinToString("\n")
        val b = NotificationCompat.Builder(context, if (stop) CHANNEL_ALARM else CHANNEL_CAUTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle((if (stop) "STOP: " else "Caution: ") + alarm.title)
            .setContentText(alarm.advice)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(if (stop) Color.RED else Color.rgb(0xFF, 0xA0, 0x00))
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .setContentIntent(openApp(context))
        if (view.unacknowledged) b.addAction(0, "OK, seen", broadcast(context, ACTION_ACK, 3))
        if (stop && alert && view.unacknowledged) {
            val full = PendingIntent.getActivity(
                context, 4, Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            b.setFullScreenIntent(full, true)
        }
        return b.build()
    }

    private fun vibrate(context: Context, stop: Boolean) {
        val pattern = if (stop) longArrayOf(0, 600, 200, 600, 200, 600, 200, 1200) else longArrayOf(0, 300, 200, 300)
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }
        runCatching { v?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }
}
