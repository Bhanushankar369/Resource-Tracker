package com.resourcetracker.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.resourcetracker.reminder.ReminderDispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs the 6pm digest.
 *
 * Enqueued by [com.resourcetracker.reminder.ReminderAlarmReceiver] when the exact
 * alarm fires. It does nothing but hand off to [ReminderDispatcher], which owns the
 * due/already-sent logic so the periodic backstop in [CardSyncWorker] behaves
 * identically.
 *
 * A worker rather than the receiver itself: the database read and the notification
 * need more time than a BroadcastReceiver is reliably given, especially when this
 * runs from a cold process after the phone has been idle all day.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dispatcher: ReminderDispatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        try {
            when (dispatcher.runFromAlarm()) {
                ReminderDispatcher.Outcome.POSTED -> Result.success()
                // Nothing to review, already sent, or blocked. None of these are worth
                // retrying: retrying cannot create a card or grant a permission.
                else -> Result.success()
            }
        } catch (e: Exception) {
            // Backoff is capped: a persistent failure (typically a database error) must
            // not re-enqueue this worker forever. Up to MAX_ATTEMPTS with WorkManager's
            // exponential backoff, then give up and log the reason.
            if (runAttemptCount < MAX_ATTEMPTS) {
                Log.w(TAG, "Reminder attempt ${runAttemptCount + 1} failed; will retry", e)
                Result.retry()
            } else {
                Log.e(TAG, "Reminder failed after $MAX_ATTEMPTS attempts; giving up", e)
                Result.failure()
            }
        }

    private companion object {
        const val TAG = "ReminderWorker"
        const val MAX_ATTEMPTS = 3
    }
}