package com.resourcetracker.reminder

import android.util.Log
import com.resourcetracker.data.local.CardDao
import com.resourcetracker.data.local.toDomain
import com.resourcetracker.data.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place that decides whether the daily digest should go out, and posts it.
 *
 * Two things fire it:
 *
 *   * [com.resourcetracker.reminder.ReminderAlarmReceiver], from the exact alarm
 *   * [com.resourcetracker.work.CardSyncWorker], as a backstop
 *
 * The second one exists because a single exact alarm is not a guarantee. If the user
 * has not granted "Alarms & reminders", or the OEM battery manager defers the alarm,
 * the digest would silently never arrive - and "the reminder did not come" is the
 * worst failure a reminder can have. The periodic sync runs roughly every 15 minutes
 * and picks the digest up if the time has passed and nothing has gone out today.
 *
 * Both paths go through here, so the "already sent today" guard is applied once and
 * the user cannot get two identical notifications.
 */
@Singleton
class ReminderDispatcher @Inject constructor(
    private val cardDao: CardDao,
    private val notifier: ReminderNotifier,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) {

    enum class Outcome {
        /** A notification was posted. */
        POSTED,

        /** No card is awaiting review, so there is nothing to say. */
        NOTHING_DUE,

        /** The digest already went out on this date. */
        ALREADY_SENT,

        /** The reminder time has not arrived yet. Only reachable from the backstop. */
        NOT_YET_DUE,
    }

    /**
     * Serialises the read-check-post sequence.
     *
     * The exact alarm and the periodic backstop can fire (nearly) at the same time.
     * Both used to read `lastReminderEpochDay`, see "not sent yet", and post, which is
     * a duplicate notification. This is a single process-wide lock because the alarm
     * worker and the backstop worker share one [ReminderDispatcher] singleton.
     */
    private val mutex = Mutex()

    /**
     * Posts the digest if the configured time has passed and it has not been sent
     * today. Safe to call as often as the periodic sync runs.
     */
    suspend fun runIfDue(): Outcome = mutex.withLock {
        val today = LocalDate.now(clock)
        val lastSent = settingsStore.lastReminderEpochDay.first()
        if (lastSent == today.toEpochDay()) {
            Log.i(TAG, "Backstop: digest already went out today ($today), nothing to do")
            return@withLock Outcome.ALREADY_SENT
        }
        if (!hasReminderTimePassed(today)) return@withLock Outcome.NOT_YET_DUE
        post(today)
    }

    /**
     * Posts the digest now, used when the exact alarm actually fired.
     *
     * The already-sent check still applies: an alarm that fires and then re-arms, plus
     * the backstop, must not produce two notifications for one evening.
     */
    suspend fun runFromAlarm(): Outcome = mutex.withLock {
        val today = LocalDate.now(clock)
        val lastSent = settingsStore.lastReminderEpochDay.first()
        Log.i(
            TAG,
            "Alarm fired on $today; last digest was $lastSent, " +
                "today's epoch day is ${today.toEpochDay()}",
        )
        if (lastSent == today.toEpochDay()) {
            Log.i(TAG, "Digest for $today already went out; not sending a second one")
            return@withLock Outcome.ALREADY_SENT
        }
        post(today)
    }

    /** Test/telemetry helper: true when the configured minute has passed today. */
    private suspend fun hasReminderTimePassed(today: LocalDate): Boolean {
        val minutes = settingsStore.currentReminderTimeMinutes()
        val now = ZonedDateTime.now(clock)
        val todayAtReminder = now
            .withHour(minutes / 60)
            .withMinute(minutes % 60)
            .withSecond(0)
            .withNano(0)
        return !now.isBefore(todayAtReminder)
    }

    private suspend fun post(today: LocalDate): Outcome {
        val due = cardDao.getDue(today.toEpochDay())
        Log.i(TAG, "Cards awaiting review on $today: ${due.size}")
        if (due.isEmpty()) {
            Log.i(TAG, "Nothing awaiting review on $today; no notification posted")
            return Outcome.NOTHING_DUE
        }

        val posted = notifier.notifyPendingReview(due.map { it.toDomain() })
        if (!posted) {
            // Almost always POST_NOTIFICATIONS being denied. Logged loudly because
            // this is invisible to the user otherwise.
            Log.w(
                TAG,
                "Digest for ${due.size} card(s) could not be posted - " +
                    "POST_NOTIFICATIONS is probably denied",
            )
            return Outcome.NOTHING_DUE
        }

        settingsStore.markReminderPosted(today.toEpochDay())
        Log.i(TAG, "Digest posted for ${due.size} card(s)")
        return Outcome.POSTED
    }

    private companion object {
        const val TAG = "ReminderDispatcher"
    }
}