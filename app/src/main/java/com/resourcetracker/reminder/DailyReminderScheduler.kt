package com.resourcetracker.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.resourcetracker.data.settings.SettingsStore
import com.resourcetracker.domain.ReminderTime
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the daily review reminder at the time chosen in Settings (default 6pm).
 *
 * Why AlarmManager and not WorkManager: a `PeriodicWorkRequest` can only promise
 * "every 15 minutes at the system's discretion" and it is not a real clock alarm.
 * A daily digest the user is told to expect at a fixed time has to survive Doze, so
 * it is an exact alarm.
 *
 * The alarm itself does nothing. It only hands off to [ReminderWorker], which does
 * the database read and posts the notification. Broadcast receivers cannot touch the
 * network and have tight execution limits, so keeping the receiver trivial is what
 * makes this reliable.
 */
@Singleton
class DailyReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) {
    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * Exact alarms require a user-granted permission from Android 12 (API 31).
     * Without it the app can still post a daily reminder, just with OS-chosen drift.
     */
    val canScheduleExactAlarms: Boolean
        get() = alarmManager?.let { am ->
            // canScheduleExactAlarms() is API 31+; below that an exact alarm needs no
            // special grant at all, so it is always allowed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.canScheduleExactAlarms()
            } else {
                true
            }
        } ?: false

    /**
     * True when a daily alarm is currently registered.
     *
     * PendingIntent matching ignores extras but considers action, data, type,
     * categories and component - so building the same intent with FLAG_NO_CREATE
     * returns the existing alarm, or null when there is none.
     */
    fun isScheduled(): Boolean = alarmIntent(PendingIntent.FLAG_NO_CREATE) != null

    /** The next reminder instant in the device's own time zone, today or tomorrow. */
    fun nextTriggerAt(
        minutesSinceMidnight: Int = ReminderTime.DEFAULT_MINUTES,
        now: ZonedDateTime = ZonedDateTime.now(clock),
    ): ZonedDateTime {
        val todayAtReminderTime = now
            .withHour(minutesSinceMidnight / 60)
            .withMinute(minutesSinceMidnight % 60)
            .withSecond(0)
            .withNano(0)
        return if (todayAtReminderTime.isAfter(now)) todayAtReminderTime else todayAtReminderTime.plusDays(1)
    }

    /** (Re)registers the alarm at the time currently stored in settings. */
    suspend fun schedule() {
        val am = alarmManager ?: return
        val intent = alarmIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val minutes = settingsStore.currentReminderTimeMinutes()
        val triggerAtMillis = nextTriggerAt(minutes).toInstant().toEpochMilli()
        val useExact = canScheduleExactAlarms

        try {
            if (useExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
            } else {
                // Inexact but still delivered out of Doze. The user has not granted
                // "Alarms & reminders" special access, so this is the best available.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
            }
        } catch (e: SecurityException) {
            // Possible if permission was revoked between the check and the call.
            Log.w(TAG, "Exact alarm denied, falling back to inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        }

        Log.i(TAG, "Next daily reminder at $triggerAtMillis (exact=$useExact)")
    }

    fun cancel() {
        alarmIntent(PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            alarmManager?.cancel(pending)
            pending.cancel()
        }
    }

    private fun alarmIntent(flags: Int): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_DAILY_REVIEW
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_DAILY_REVIEW = "com.resourcetracker.action.DAILY_REVIEW"
        private const val REQUEST_CODE = 6100
        private const val TAG = "DailyReminder"
    }
}
